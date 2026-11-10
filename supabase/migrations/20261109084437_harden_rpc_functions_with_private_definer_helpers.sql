create schema if not exists private;
revoke all on schema private from public, anon;
grant usage on schema private to authenticated, service_role;

create or replace function private.create_direct_conversation_impl(p_other_user_id uuid)
returns uuid
language plpgsql
security definer
set search_path to ''
as $function$
declare
  v_me uuid := auth.uid();
  v_id uuid;
  v_lock_key bigint;
begin
  if v_me is null then raise exception 'Authentication required'; end if;
  if p_other_user_id is null or p_other_user_id = v_me then raise exception 'Choose another user'; end if;
  if not exists (select 1 from public.profiles p where p.id = p_other_user_id) then raise exception 'User not found'; end if;
  v_lock_key := pg_catalog.hashtextextended(
    least(v_me::text, p_other_user_id::text) || ':' ||
    greatest(v_me::text, p_other_user_id::text), 0
  );
  perform pg_catalog.pg_advisory_xact_lock(v_lock_key);
  select c.id into v_id from public.conversations c
  where c.kind = 'direct'
    and exists (select 1 from public.conversation_members a where a.conversation_id=c.id and a.user_id=v_me)
    and exists (select 1 from public.conversation_members b where b.conversation_id=c.id and b.user_id=p_other_user_id)
    and (select count(*) from public.conversation_members m where m.conversation_id=c.id) = 2
  limit 1;
  if v_id is null then
    insert into public.conversations(kind, created_by) values ('direct', v_me) returning id into v_id;
    insert into public.conversation_members(conversation_id, user_id, member_role)
    values (v_id, v_me, 'owner'), (v_id, p_other_user_id, 'member')
    on conflict (conversation_id, user_id) do nothing;
  end if;
  return v_id;
end;
$function$;

create or replace function private.create_group_conversation_impl(p_title text, p_member_ids uuid[])
returns uuid language plpgsql security definer set search_path to ''
as $function$
declare v_me uuid := auth.uid(); v_id uuid; v_member uuid; v_count integer;
begin
  if v_me is null then raise exception 'Authentication required'; end if;
  if p_title is null or length(btrim(p_title)) < 2 or length(btrim(p_title)) > 60 then
    raise exception 'Название группы должно содержать от 2 до 60 символов';
  end if;
  if p_member_ids is null then raise exception 'Выберите участников группы'; end if;
  select count(distinct x) into v_count from unnest(p_member_ids) as x where x is not null and x <> v_me;
  if v_count < 1 or v_count > 49 then raise exception 'В группе должно быть от 2 до 50 участников'; end if;
  if exists (select 1 from unnest(p_member_ids) as x where x is not null and x <> v_me
    and not exists (select 1 from public.profiles p where p.id = x)) then
    raise exception 'Один или несколько пользователей не найдены';
  end if;
  insert into public.conversations(kind, title, created_by) values ('group', btrim(p_title), v_me) returning id into v_id;
  for v_member in select distinct x from unnest(p_member_ids) as x where x is not null and x <> v_me loop
    insert into public.conversation_members(conversation_id, user_id, member_role)
    values (v_id, v_member, 'member') on conflict (conversation_id, user_id) do nothing;
  end loop;
  return v_id;
end;
$function$;

create or replace function private.match_contact_users_impl(p_phone_numbers text[])
returns table(phone text, user_id uuid, display_name text, avatar_url text)
language plpgsql security definer set search_path to ''
as $function$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then raise exception 'Authentication required'; end if;
  if p_phone_numbers is null or cardinality(p_phone_numbers) = 0 then return; end if;
  if cardinality(p_phone_numbers) > 500 then raise exception 'Можно проверить не более 500 контактов за раз'; end if;
  if exists (select 1 from unnest(p_phone_numbers) n where n is null or n !~ '^\+[1-9][0-9]{7,14}$') then
    raise exception 'Некорректный формат номера телефона';
  end if;
  return query select distinct u.phone, p.id,
    coalesce(nullif(p.display_name, ''), 'Пользователь'),
    coalesce(nullif(p.avatar_url, ''), '🙂')
  from auth.users u join public.profiles p on p.id = u.id
  where u.phone = any(p_phone_numbers) and u.phone_confirmed_at is not null
    and u.id <> v_uid and coalesce(u.phone, '') <> '';
end;
$function$;

revoke all on function private.create_direct_conversation_impl(uuid) from public, anon;
revoke all on function private.create_group_conversation_impl(text, uuid[]) from public, anon;
revoke all on function private.match_contact_users_impl(text[]) from public, anon;
grant execute on function private.create_direct_conversation_impl(uuid) to authenticated, service_role;
grant execute on function private.create_group_conversation_impl(text, uuid[]) to authenticated, service_role;
grant execute on function private.match_contact_users_impl(text[]) to authenticated, service_role;

create or replace function public.create_direct_conversation(p_other_user_id uuid)
returns uuid language sql security invoker set search_path to ''
as $function$ select private.create_direct_conversation_impl(p_other_user_id); $function$;
create or replace function public.create_group_conversation(p_title text, p_member_ids uuid[] default '{}'::uuid[])
returns uuid language sql security invoker set search_path to ''
as $function$ select private.create_group_conversation_impl(p_title, p_member_ids); $function$;
create or replace function public.match_contact_users(p_phone_numbers text[])
returns table(phone text, user_id uuid, display_name text, avatar_url text)
language sql security invoker set search_path to ''
as $function$ select * from private.match_contact_users_impl(p_phone_numbers); $function$;

revoke all on function public.create_direct_conversation(uuid) from public, anon;
revoke all on function public.create_group_conversation(text, uuid[]) from public, anon;
revoke all on function public.match_contact_users(text[]) from public, anon;
grant execute on function public.create_direct_conversation(uuid) to authenticated, service_role;
grant execute on function public.create_group_conversation(text, uuid[]) to authenticated, service_role;
grant execute on function public.match_contact_users(text[]) to authenticated, service_role;
