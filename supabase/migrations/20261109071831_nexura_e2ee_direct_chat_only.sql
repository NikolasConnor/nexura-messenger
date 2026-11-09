-- Bootstrap the public key directory before defining an RPC that references it.
-- The later 20261109090000 migration adds indexes, RLS policies, and grants.
create schema if not exists private;

create table if not exists public.e2ee_devices (
    user_id uuid not null references auth.users(id) on delete cascade,
    device_id uuid not null,
    curve25519_key text not null,
    ed25519_key text not null,
    protocol text not null default 'olm-v1' check (protocol = 'olm-v1'),
    bundle_version smallint not null default 1 check (bundle_version = 1),
    created_at timestamptz not null default pg_catalog.now(),
    updated_at timestamptz not null default pg_catalog.now(),
    is_active boolean not null default true,
    primary key (user_id, device_id),
    constraint e2ee_devices_curve25519_length check (length(curve25519_key) between 40 and 64),
    constraint e2ee_devices_ed25519_length check (length(ed25519_key) between 40 and 64)
);

create table if not exists public.e2ee_one_time_keys (
    user_id uuid not null,
    device_id uuid not null,
    key_id uuid not null,
    curve25519_key text not null,
    signature text not null,
    created_at timestamptz not null default pg_catalog.now(),
    consumed_at timestamptz,
    primary key (user_id, device_id, key_id),
    foreign key (user_id, device_id)
        references public.e2ee_devices(user_id, device_id) on delete cascade,
    constraint e2ee_otk_key_length check (length(curve25519_key) between 40 and 64),
    constraint e2ee_otk_signature_length check (length(signature) between 80 and 100)
);

-- Restrict OTK consumption to genuine two-member direct conversations.
-- Prevents group members from exhausting another participant's pre-key pool.
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

    if p_recipient_user_id = auth.uid() then
        raise exception 'cannot consume your own one-time key' using errcode = '42501';
    end if;

    if not exists (
        select 1
        from public.conversations c
        where c.id = p_conversation_id
          and c.kind = 'direct'
    ) then
        raise exception 'E2EE one-time keys are available only in direct conversations' using errcode = '42501';
    end if;

    if (
        select count(*)
        from public.conversation_members cm
        where cm.conversation_id = p_conversation_id
    ) <> 2 then
        raise exception 'direct conversation membership is invalid' using errcode = '42501';
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
