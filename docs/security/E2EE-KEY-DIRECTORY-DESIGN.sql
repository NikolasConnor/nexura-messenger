-- DESIGN ONLY: review and convert to a timestamped Supabase migration before use.
-- This file is NOT applied to the production Supabase project.
-- Stores public device keys only. Private keys and Olm pickles remain on-device.

create schema if not exists private;

create table if not exists public.e2ee_devices (
    user_id uuid not null references auth.users(id) on delete cascade,
    device_id uuid not null,
    curve25519_key text not null,
    ed25519_key text not null,
    protocol text not null default 'olm-v1' check (protocol = 'olm-v1'),
    bundle_version smallint not null default 1 check (bundle_version = 1),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    is_active boolean not null default true,
    primary key (user_id, device_id),
    constraint e2ee_devices_curve25519_nonempty check (length(curve25519_key) between 40 and 64),
    constraint e2ee_devices_ed25519_nonempty check (length(ed25519_key) between 40 and 64)
);

create table if not exists public.e2ee_one_time_keys (
    user_id uuid not null,
    device_id uuid not null,
    key_id uuid not null,
    curve25519_key text not null,
    signature text not null,
    created_at timestamptz not null default now(),
    consumed_at timestamptz,
    primary key (user_id, device_id, key_id),
    foreign key (user_id, device_id)
        references public.e2ee_devices(user_id, device_id) on delete cascade,
    constraint e2ee_otk_key_nonempty check (length(curve25519_key) between 40 and 64),
    constraint e2ee_otk_signature_nonempty check (length(signature) between 80 and 100)
);

create index if not exists e2ee_devices_user_active_idx
    on public.e2ee_devices(user_id, is_active);
create index if not exists e2ee_otk_available_idx
    on public.e2ee_one_time_keys(user_id, device_id, created_at)
    where consumed_at is null;

alter table public.e2ee_devices enable row level security;
alter table public.e2ee_one_time_keys enable row level security;

-- Public key material is visible only to the owner and people sharing
-- at least one conversation with that owner.
create policy e2ee_devices_read_conversation_peers
on public.e2ee_devices for select to authenticated
using (
    user_id = (select auth.uid())
    or exists (
        select 1
        from public.conversation_members mine
        join public.conversation_members peer
          on peer.conversation_id = mine.conversation_id
        where mine.user_id = (select auth.uid())
          and peer.user_id = e2ee_devices.user_id
    )
);

create policy e2ee_devices_insert_owner
on public.e2ee_devices for insert to authenticated
with check (user_id = (select auth.uid()));

create policy e2ee_devices_update_owner
on public.e2ee_devices for update to authenticated
using (user_id = (select auth.uid()))
with check (user_id = (select auth.uid()));

create policy e2ee_devices_delete_owner
on public.e2ee_devices for delete to authenticated
using (user_id = (select auth.uid()));

-- OTK rows are visible only to their owner. Peers receive a single key
-- through the atomic, membership-checked RPC below.

create policy e2ee_otk_insert_owner
on public.e2ee_one_time_keys for insert to authenticated
with check (user_id = (select auth.uid()));

create policy e2ee_otk_delete_owner
on public.e2ee_one_time_keys for delete to authenticated
using (user_id = (select auth.uid()));

grant select, insert, update, delete on public.e2ee_devices to authenticated;
grant select, insert, delete on public.e2ee_one_time_keys to authenticated;

-- Clients cannot update consumed_at. Only the membership-checked RPC below can consume a key.


create policy e2ee_otk_read_owner
on public.e2ee_one_time_keys for select to authenticated
using (user_id = (select auth.uid()));

-- Private SECURITY DEFINER implementation. The caller can consume exactly one
-- recipient key only if both users belong to the requested conversation.
create or replace function private.consume_e2ee_one_time_key(
    p_conversation_id uuid,
    p_recipient_user_id uuid,
    p_device_id uuid
)
returns table (
    device_id uuid,
    curve25519_key text,
    ed25519_key text,
    one_time_key_id uuid,
    one_time_key text,
    one_time_key_signature text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_device public.e2ee_devices%rowtype;
    v_key public.e2ee_one_time_keys%rowtype;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    if not exists (
        select 1 from public.conversation_members cm
        where cm.conversation_id = p_conversation_id
          and cm.user_id = auth.uid()
    ) or not exists (
        select 1 from public.conversation_members cm
        where cm.conversation_id = p_conversation_id
          and cm.user_id = p_recipient_user_id
    ) then
        raise exception 'conversation membership required' using errcode = '42501';
    end if;

    select d.* into v_device
    from public.e2ee_devices d
    where d.user_id = p_recipient_user_id
      and d.device_id = p_device_id
      and d.is_active = true
    for share;

    if not found then
        raise exception 'active E2EE device not found' using errcode = 'P0002';
    end if;

    select k.* into v_key
    from public.e2ee_one_time_keys k
    where k.user_id = p_recipient_user_id
      and k.device_id = p_device_id
      and k.consumed_at is null
    order by k.created_at
    limit 1
    for update skip locked;

    if not found then
        raise exception 'no available one-time key' using errcode = 'P0002';
    end if;

    update public.e2ee_one_time_keys k
       set consumed_at = pg_catalog.now()
     where k.user_id = v_key.user_id
       and k.device_id = v_key.device_id
       and k.key_id = v_key.key_id
       and k.consumed_at is null;

    if not found then
        raise exception 'one-time key was already consumed' using errcode = '40001';
    end if;

    return query
    select v_device.device_id,
           v_device.curve25519_key,
           v_device.ed25519_key,
           v_key.key_id,
           v_key.curve25519_key,
           v_key.signature;
end;
$$;

revoke all on function private.consume_e2ee_one_time_key(uuid, uuid, uuid) from public;
grant usage on schema private to authenticated;
grant execute on function private.consume_e2ee_one_time_key(uuid, uuid, uuid) to authenticated;

-- Public invoker wrapper for PostgREST. The implementation remains in the
-- non-exposed private schema and performs all authorization checks itself.
create or replace function public.consume_e2ee_one_time_key(
    p_conversation_id uuid,
    p_recipient_user_id uuid,
    p_device_id uuid
)
returns table (
    device_id uuid,
    curve25519_key text,
    ed25519_key text,
    one_time_key_id uuid,
    one_time_key text,
    one_time_key_signature text
)
language sql
security invoker
set search_path = ''
as $$
    select * from private.consume_e2ee_one_time_key(
        p_conversation_id,
        p_recipient_user_id,
        p_device_id
    );
$$;

revoke all on function public.consume_e2ee_one_time_key(uuid, uuid, uuid) from public, anon;
grant execute on function public.consume_e2ee_one_time_key(uuid, uuid, uuid) to authenticated;
