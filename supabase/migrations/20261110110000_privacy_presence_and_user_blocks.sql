drop function if exists public.get_user_presence(uuid);
alter table public.profiles add column if not exists read_receipts_enabled boolean not null default true;
create table if not exists public.user_presence (
 user_id uuid primary key references auth.users(id) on delete cascade,
 show_online_status boolean not null default true,
 last_seen_at timestamptz
);
alter table public.user_presence enable row level security;
grant select, insert, update on public.user_presence to authenticated;
drop policy if exists "Users manage own presence" on public.user_presence;
create policy "Users manage own presence" on public.user_presence for all to authenticated
 using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create table if not exists public.user_blocks (
 blocker_id uuid not null references auth.users(id) on delete cascade,
 blocked_id uuid not null references auth.users(id) on delete cascade,
 created_at timestamptz not null default now(),
 constraint user_blocks_pk primary key (blocker_id, blocked_id),
 constraint user_blocks_not_self check (blocker_id <> blocked_id)
);
alter table public.user_blocks enable row level security;
grant select, insert, delete on public.user_blocks to authenticated;
drop policy if exists "Users can view their own blocks" on public.user_blocks;
create policy "Users can view their own blocks" on public.user_blocks for select to authenticated using (blocker_id = (select auth.uid()));
drop policy if exists "Users can block others" on public.user_blocks;
create policy "Users can block others" on public.user_blocks for insert to authenticated with check (blocker_id = (select auth.uid()) and blocked_id <> (select auth.uid()));
drop policy if exists "Users can unblock others" on public.user_blocks;
create policy "Users can unblock others" on public.user_blocks for delete to authenticated using (blocker_id = (select auth.uid()));

create or replace function private.is_direct_chat_blocked(p_conversation_id uuid)
returns boolean language plpgsql stable security definer set search_path = pg_catalog, public, auth
as $$
declare v_uid uuid := auth.uid(); v_peer uuid;
begin
 if v_uid is null then return true; end if;
 select cm.user_id into v_peer from public.conversation_members cm
 join public.conversations c on c.id = cm.conversation_id
 where cm.conversation_id = p_conversation_id and cm.user_id <> v_uid and c.kind = 'direct' limit 1;
 if v_peer is null then return false; end if;
 return exists (select 1 from public.user_blocks b
  where (b.blocker_id = v_uid and b.blocked_id = v_peer) or (b.blocker_id = v_peer and b.blocked_id = v_uid));
end;
$$;
revoke all on function private.is_direct_chat_blocked(uuid) from public, anon;
grant execute on function private.is_direct_chat_blocked(uuid) to authenticated;

create or replace function private.prevent_blocked_direct_member()
returns trigger language plpgsql security definer set search_path = pg_catalog, public
as $$
declare v_peer uuid; v_kind text;
begin
 select c.kind into v_kind from public.conversations c where c.id = new.conversation_id;
 if v_kind = 'direct' then
  select cm.user_id into v_peer from public.conversation_members cm
   where cm.conversation_id = new.conversation_id and cm.user_id <> new.user_id limit 1;
  if v_peer is not null and exists (select 1 from public.user_blocks b
   where (b.blocker_id = new.user_id and b.blocked_id = v_peer) or (b.blocker_id = v_peer and b.blocked_id = new.user_id)) then
   raise exception 'This user is blocked';
  end if;
 end if;
 return new;
end;
$$;
drop trigger if exists prevent_blocked_direct_member on public.conversation_members;
create trigger prevent_blocked_direct_member before insert on public.conversation_members
for each row execute function private.prevent_blocked_direct_member();

drop policy if exists "Blocked direct chats are hidden" on public.conversations;
create policy "Blocked direct chats are hidden" on public.conversations as restrictive for select to authenticated using (not (select private.is_direct_chat_blocked(id)));
drop policy if exists "Blocked direct memberships are hidden" on public.conversation_members;
create policy "Blocked direct memberships are hidden" on public.conversation_members as restrictive for select to authenticated using (not (select private.is_direct_chat_blocked(conversation_id)));
drop policy if exists "Blocked direct messages are hidden" on public.messages;
create policy "Blocked direct messages are hidden" on public.messages as restrictive for select to authenticated using (not (select private.is_direct_chat_blocked(conversation_id)));
drop policy if exists "Cannot send to blocked direct chats" on public.messages;
create policy "Cannot send to blocked direct chats" on public.messages as restrictive for insert to authenticated with check (not (select private.is_direct_chat_blocked(conversation_id)));

create function public.get_user_presence(p_user_id uuid)
returns table(is_visible boolean, is_online boolean, last_seen_at timestamptz)
language sql stable security definer set search_path = pg_catalog, public, auth
as $$
 select coalesce(p.show_online_status, true),
        coalesce(p.last_seen_at > now() - interval '2 minutes', false),
        case when coalesce(p.show_online_status, true) then p.last_seen_at else null end
 from (select p_user_id as id) target left join public.user_presence p on p.user_id = target.id
 where auth.uid() is not null;
$$;
revoke all on function public.get_user_presence(uuid) from public, anon;
grant execute on function public.get_user_presence(uuid) to authenticated;

alter table public.conversation_members add column if not exists last_receipt_at timestamptz;

create or replace function private.is_user_blocked(p_target_id uuid)
returns boolean language sql stable security definer set search_path = pg_catalog, public, auth
as $$
 select exists (
   select 1 from public.user_blocks b
   where (b.blocker_id = auth.uid() and b.blocked_id = p_target_id)
      or (b.blocker_id = p_target_id and b.blocked_id = auth.uid())
 );
$$;
revoke all on function private.is_user_blocked(uuid) from public, anon;
grant execute on function private.is_user_blocked(uuid) to authenticated;
drop policy if exists "Visible presence is readable" on public.user_presence;
create policy "Visible presence is readable" on public.user_presence
 for select to authenticated using (
   show_online_status = true and not (select private.is_user_blocked(user_id))
 );
drop function if exists public.get_user_presence(uuid);
create function public.get_user_presence(p_user_id uuid)
returns table(is_visible boolean, is_online boolean, last_seen_at timestamptz)
language sql stable security invoker set search_path = pg_catalog, public, auth
as $$
 select coalesce(p.show_online_status, false),
        coalesce(p.last_seen_at > now() - interval '2 minutes', false),
        p.last_seen_at
 from (select p_user_id as id) target
 left join public.user_presence p on p.user_id = target.id
 where auth.uid() is not null;
$$;
revoke all on function public.get_user_presence(uuid) from public, anon;
grant execute on function public.get_user_presence(uuid) to authenticated;

create or replace function public.update_my_privacy_settings(
 p_show_online_status boolean,
 p_read_receipts_enabled boolean
)
returns void language plpgsql security invoker
set search_path = pg_catalog, public, auth
as $$
declare v_uid uuid := auth.uid();
begin
 if v_uid is null then raise exception 'Authentication required'; end if;
 insert into public.user_presence(user_id, show_online_status, last_seen_at)
 values (v_uid, p_show_online_status, now())
 on conflict (user_id) do update
 set show_online_status = excluded.show_online_status, last_seen_at = now();
 update public.profiles set read_receipts_enabled = p_read_receipts_enabled where id = v_uid;
end;
$$;
revoke all on function public.update_my_privacy_settings(boolean, boolean) from public, anon;
grant execute on function public.update_my_privacy_settings(boolean, boolean) to authenticated;

create index if not exists user_blocks_blocked_id_idx on public.user_blocks(blocked_id, blocker_id);

drop policy if exists "Users manage own presence" on public.user_presence;
drop policy if exists "Visible presence is readable" on public.user_presence;
create policy "Presence rows readable by owner or visible peers" on public.user_presence
 for select to authenticated using (
   user_id = (select auth.uid())
   or (show_online_status = true and not (select private.is_user_blocked(user_id)))
 );
create policy "Users insert own presence" on public.user_presence
 for insert to authenticated with check (user_id = (select auth.uid()));
create policy "Users update own presence" on public.user_presence
 for update to authenticated using (user_id = (select auth.uid()))
 with check (user_id = (select auth.uid()));
