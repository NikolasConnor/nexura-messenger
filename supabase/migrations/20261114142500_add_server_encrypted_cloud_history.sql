-- Add a server-encrypted archive envelope alongside the existing Olm ciphertext.
-- The existing content column and its E2EE payloads remain untouched.
alter table public.messages
    add column if not exists cloud_content text;

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'messages_cloud_content_size'
          and conrelid = 'public.messages'::regclass
    ) then
        alter table public.messages
            add constraint messages_cloud_content_size
            check (cloud_content is null or octet_length(cloud_content) between 1 and 262144);
    end if;
end
$$;

comment on column public.messages.cloud_content is
    'Versioned AES-256-GCM cloud-history envelope. The legacy Olm ciphertext in content is preserved.';
