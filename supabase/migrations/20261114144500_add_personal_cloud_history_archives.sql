create table if not exists public.message_cloud_archives (
    user_id uuid not null references auth.users(id) on delete cascade,
    conversation_id uuid not null references public.conversations(id) on delete cascade,
    message_id uuid not null references public.messages(id) on delete cascade,
    cloud_content text not null,
    created_at timestamptz not null default now(),
    constraint message_cloud_archives_size check (octet_length(cloud_content) between 1 and 262144),
    primary key (user_id, message_id)
);

create index if not exists message_cloud_archives_conversation_idx
    on public.message_cloud_archives (user_id, conversation_id, message_id);

alter table public.message_cloud_archives enable row level security;

drop policy if exists "Users can read their own conversation archives" on public.message_cloud_archives;
create policy "Users can read their own conversation archives"
    on public.message_cloud_archives for select
    to authenticated
    using (
        user_id = (select auth.uid())
        and (select private.is_conversation_member(conversation_id))
    );

drop policy if exists "Members can create their own conversation archives" on public.message_cloud_archives;
create policy "Members can create their own conversation archives"
    on public.message_cloud_archives for insert
    to authenticated
    with check (
        user_id = (select auth.uid())
        and (select private.is_conversation_member(conversation_id))
        and exists (
            select 1
            from public.messages m
            where m.id = message_id
              and m.conversation_id = message_cloud_archives.conversation_id
        )
    );

revoke all on public.message_cloud_archives from anon;
grant select, insert on public.message_cloud_archives to authenticated;
