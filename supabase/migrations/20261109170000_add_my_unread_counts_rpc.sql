create or replace function public.get_my_unread_counts()
returns table (conversation_id uuid, unread_count bigint)
language sql
stable
security invoker
set search_path = ''
as $$
    select
        cm.conversation_id,
        count(m.id)::bigint as unread_count
    from public.conversation_members as cm
    left join public.messages as m
        on m.conversation_id = cm.conversation_id
        and m.sender_id <> auth.uid()
        and m.deleted_at is null
        and m.created_at > coalesce(cm.last_read_at, 'epoch'::timestamptz)
    where cm.user_id = auth.uid()
    group by cm.conversation_id;
$$;

revoke all on function public.get_my_unread_counts() from public, anon;
grant execute on function public.get_my_unread_counts() to authenticated;
