create table if not exists public.saved_messages (
  user_id uuid not null references auth.users(id) on delete cascade,
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  message_id uuid not null references public.messages(id) on delete cascade,
  saved_at timestamptz not null default now(),
  primary key (user_id, message_id)
);
create index if not exists saved_messages_user_saved_idx
  on public.saved_messages(user_id, saved_at desc);
create index if not exists saved_messages_conversation_idx
  on public.saved_messages(conversation_id);
create index if not exists saved_messages_message_idx
  on public.saved_messages(message_id);
alter table public.saved_messages enable row level security;
drop policy if exists "Users can view their saved messages" on public.saved_messages;
create policy "Users can view their saved messages"
  on public.saved_messages for select to authenticated
  using (
    user_id = (select auth.uid())
    and private.is_conversation_member(conversation_id)
    and exists (
      select 1 from public.messages m
      where m.id = saved_messages.message_id
        and m.conversation_id = saved_messages.conversation_id
    )
  );
drop policy if exists "Users can save messages from their chats" on public.saved_messages;
create policy "Users can save messages from their chats"
  on public.saved_messages for insert to authenticated
  with check (
    user_id = (select auth.uid())
    and private.is_conversation_member(conversation_id)
    and exists (
      select 1 from public.messages m
      where m.id = saved_messages.message_id
        and m.conversation_id = saved_messages.conversation_id
    )
  );
drop policy if exists "Users can remove their saved messages" on public.saved_messages;
create policy "Users can remove their saved messages"
  on public.saved_messages for delete to authenticated
  using (user_id = (select auth.uid()));
revoke all on table public.saved_messages from anon, public, authenticated;
grant select, insert, delete on table public.saved_messages to authenticated;

drop policy if exists "Group admins can manage member roles" on public.conversation_members;
create policy "Group admins can manage member roles"
  on public.conversation_members for update to authenticated
  using (
    private.is_conversation_admin(conversation_id)
    and exists (select 1 from public.conversations c where c.id = conversation_members.conversation_id and c.kind = 'group')
  )
  with check (
    private.is_conversation_admin(conversation_id)
    and member_role in ('admin', 'member')
    and exists (select 1 from public.conversations c where c.id = conversation_members.conversation_id and c.kind = 'group')
  );
