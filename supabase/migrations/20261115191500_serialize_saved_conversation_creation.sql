-- Serialize concurrent first-open requests for a user's Saved Messages chat.
create or replace function public.get_or_create_saved_conversation()
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_me uuid := auth.uid();
  v_id uuid;
begin
  if v_me is null then raise exception 'Authentication required'; end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_me::text || ':nexura-saved-chat', 0));
  select c.id into v_id
  from public.conversations c
  where c.created_by = v_me and c.kind = 'group' and c.title = '⭐ Избранное'
  limit 1;
  if v_id is null then
    insert into public.conversations(kind, title, created_by, chat_mode)
    values ('group', '⭐ Избранное', v_me, 'cloud')
    returning id into v_id;
    insert into public.conversation_members(conversation_id, user_id, member_role)
    values (v_id, v_me, 'owner')
    on conflict (conversation_id, user_id) do nothing;
  end if;
  return v_id;
end;
$$;
revoke all on function public.get_or_create_saved_conversation() from public, anon;
grant execute on function public.get_or_create_saved_conversation() to authenticated;
