drop policy if exists "Users can read their own conversation archives" on public.message_cloud_archives;
create policy "Users can read their own conversation archives"
    on public.message_cloud_archives for select
    to authenticated
    using (
        user_id = (select auth.uid())
        and (select private.is_conversation_member(message_cloud_archives.conversation_id))
        and exists (
            select 1
            from public.messages m
            where m.id = message_cloud_archives.message_id
              and m.conversation_id = message_cloud_archives.conversation_id
              and m.deleted_at is null
        )
    );
