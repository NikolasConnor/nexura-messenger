create table if not exists public.e2ee_state_backups (
    user_id uuid primary key references auth.users(id) on delete cascade,
    format_version smallint not null default 1 check (format_version = 1),
    encrypted_payload text not null,
    updated_at timestamptz not null default now(),
    constraint e2ee_state_backups_payload_size check (octet_length(encrypted_payload) between 1 and 16777216)
);

alter table public.e2ee_state_backups enable row level security;

drop policy if exists "Users can read their own encrypted E2EE backup" on public.e2ee_state_backups;
create policy "Users can read their own encrypted E2EE backup"
    on public.e2ee_state_backups for select
    to authenticated
    using (auth.uid() = user_id);

drop policy if exists "Users can create their own encrypted E2EE backup" on public.e2ee_state_backups;
create policy "Users can create their own encrypted E2EE backup"
    on public.e2ee_state_backups for insert
    to authenticated
    with check (auth.uid() = user_id);

drop policy if exists "Users can update their own encrypted E2EE backup" on public.e2ee_state_backups;
create policy "Users can update their own encrypted E2EE backup"
    on public.e2ee_state_backups for update
    to authenticated
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "Users can delete their own encrypted E2EE backup" on public.e2ee_state_backups;
create policy "Users can delete their own encrypted E2EE backup"
    on public.e2ee_state_backups for delete
    to authenticated
    using (auth.uid() = user_id);

revoke all on public.e2ee_state_backups from anon;
grant select, insert, update, delete on public.e2ee_state_backups to authenticated;
