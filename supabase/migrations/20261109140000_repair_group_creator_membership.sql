-- Ensure the group creator is a real, visible member and group owner.
-- Backfill existing groups first so older conversations become visible again.
insert into public.conversation_members (conversation_id, user_id, member_role)
select c.id, c.created_by, 'owner'
from public.conversations c
where c.kind = 'group'
  and exists (select 1 from public.profiles p where p.id = c.created_by)
on conflict (conversation_id, user_id)
do update set member_role = 'owner';

create or replace function private.create_group_conversation_impl(
  p_title text,
  p_member_ids uuid[]
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_me uuid := auth.uid();
  v_id uuid;
  v_member uuid;
  v_count integer;
begin
  if v_me is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;

  if p_title is null or length(btrim(p_title)) < 2 or length(btrim(p_title)) > 60 then
    raise exception 'Название группы должно содержать от 2 до 60 символов';
  end if;

  if p_member_ids is null then
    raise exception 'Выберите участников группы';
  end if;

  select count(distinct x) into v_count
  from unnest(p_member_ids) as x
  where x is not null and x <> v_me;

  if v_count < 1 or v_count > 49 then
    raise exception 'В группе должно быть от 2 до 50 участников';
  end if;

  if exists (
    select 1
    from unnest(p_member_ids) as x
    where x is not null
      and x <> v_me
      and not exists (select 1 from public.profiles p where p.id = x)
  ) then
    raise exception 'Один или несколько пользователей не найдены';
  end if;

  insert into public.conversations(kind, title, created_by)
  values ('group', btrim(p_title), v_me)
  returning id into v_id;

  insert into public.conversation_members(conversation_id, user_id, member_role)
  values (v_id, v_me, 'owner');

  for v_member in
    select distinct x
    from unnest(p_member_ids) as x
    where x is not null and x <> v_me
  loop
    insert into public.conversation_members(conversation_id, user_id, member_role)
    values (v_id, v_member, 'member')
    on conflict (conversation_id, user_id) do nothing;
  end loop;

  return v_id;
end;
$function$;

revoke all on function private.create_group_conversation_impl(text, uuid[]) from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.create_group_conversation_impl(text, uuid[]) to authenticated;
