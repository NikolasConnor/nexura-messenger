-- DESIGN ONLY: review and convert to a timestamped Supabase migration before use.
-- This file is NOT applied to the production Supabase project.
-- Stores public device keys only. Private keys and Olm pickles remain on-device.

create schema if not exists private;

create table if not exists public.e2ee_devices (
    user_id uuid not null references auth.users(id) on delete cascade,
    device_id uuid not null,
    curve25519_key text not null,
    ed25519_key text not null,
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

create policy e2ee_otk_read_conversation_peers
on public.e2ee_one_time_keys for select to authenticated
using (
    user_id = (select auth.uid())
    or exists (
        select 1
        from public.conversation_members mine
        join public.conversation_members peer
          on peer.conversation_id = mine.conversation_id
        where mine.user_id = (select auth.uid())
          and peer.user_id = e2ee_one_time_keys.user_id
    )
);

create policy e2ee_otk_insert_owner
on public.e2ee_one_time_keys for insert to authenticated
with check (user_id = (select auth.uid()));

create policy e2ee_otk_update_owner
on public.e2ee_one_time_keys for update to authenticated
using (user_id = (select auth.uid()))
with check (user_id = (select auth.uid()));

create policy e2ee_otk_delete_owner
on public.e2ee_one_time_keys for delete to authenticated
using (user_id = (select auth.uid()));

grant select, insert, update, delete on public.e2ee_devices to authenticated;
grant select, insert, update, delete on public.e2ee_one_time_keys to authenticated;

-- Atomic one-time-key consumption is intentionally left for a separate
-- reviewed RPC. Never let a client directly set consumed_at on another user's
-- row. The RPC must check auth.uid() and both users' membership in the supplied
-- conversation, then SELECT ... FOR UPDATE SKIP LOCKED and mark one key consumed.
