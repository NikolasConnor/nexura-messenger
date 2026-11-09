alter table public.messages
    drop constraint if exists messages_message_type_check;

alter table public.messages
    add constraint messages_message_type_check
    check (message_type = any (array['text','image','media','file','system']::text[]));