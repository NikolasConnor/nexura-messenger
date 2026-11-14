-- Preserve all existing conversations as secret chats. New conversations default to cloud mode.
alter table public.conversations
    add column if not exists chat_mode text;

update public.conversations
set chat_mode = 'secret'
where chat_mode is null;

alter table public.conversations
    alter column chat_mode set default 'cloud';

alter table public.conversations
    alter column chat_mode set not null;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'conversations_chat_mode_check'
          and conrelid = 'public.conversations'::regclass
    ) then
        alter table public.conversations
            add constraint conversations_chat_mode_check
            check (chat_mode in ('cloud', 'secret'));
    end if;
end
$$;

comment on column public.conversations.chat_mode is
    'cloud = Telegram-style server-managed encrypted cloud history; secret = end-to-end encrypted device sessions.';
