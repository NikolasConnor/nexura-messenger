create table if not exists public.conversation_typing (
    conversation_id uuid not null references public.conversations(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    updated_at timestamptz not null default now(),
    primary key (conversation_id, user_id)
);
alter table public.conversation_typing enable row level security;
grant select, insert, update, delete on public.conversation_typing to authenticated;
drop policy if exists "Chat members can read typing status" on public.conversation_typing;
create policy "Chat members can read typing status" on public.conversation_typing
for select to authenticated using (
    exists (select 1 from public.conversation_members cm
            where cm.conversation_id = conversation_typing.conversation_id
              and cm.user_id = (select auth.uid()))
);
drop policy if exists "Users can publish own typing status" on public.conversation_typing;
create policy "Users can publish own typing status" on public.conversation_typing
for insert to authenticated with check (
    user_id = (select auth.uid()) and exists (
        select 1 from public.conversation_members cm
        where cm.conversation_id = conversation_typing.conversation_id
          and cm.user_id = (select auth.uid()))
);
drop policy if exists "Users can update own typing status" on public.conversation_typing;
create policy "Users can update own typing status" on public.conversation_typing
for update to authenticated using (user_id = (select auth.uid()))
with check (user_id = (select auth.uid()) and exists (
    select 1 from public.conversation_members cm
    where cm.conversation_id = conversation_typing.conversation_id
      and cm.user_id = (select auth.uid())));
drop policy if exists "Users can clear own typing status" on public.conversation_typing;
create policy "Users can clear own typing status" on public.conversation_typing
for delete to authenticated using (user_id = (select auth.uid()));
create index if not exists conversation_typing_updated_at_idx on public.conversation_typing(updated_at);
