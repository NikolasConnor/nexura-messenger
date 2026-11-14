drop policy if exists "Members can create their own conversation archives" on public.message_cloud_archives;
create policy "Members can create their own conversation archives"
    on public.message_cloud_archives for insert
    to authenticated
    with check (
        user_id = (select auth.uid())
        and (select private.is_conversation_member(message_cloud_archives.conversation_id))
        and exists (
            select 1
            from public.messages m
            where m.id = message_cloud_archives.message_id
              and m.conversation_id = message_cloud_archives.conversation_id
        )
    );
