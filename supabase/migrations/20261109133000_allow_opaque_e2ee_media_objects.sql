-- Ciphertext blobs are uploaded as opaque octet streams. The actual MIME
-- type is inside the Olm-encrypted message payload and is never sent to Storage.
update storage.buckets
set allowed_mime_types = array(
  select distinct mime
  from unnest(coalesce(allowed_mime_types, array[]::text[]) || array['application/octet-stream']::text[]) as mime
)
where id = 'nexura-chat-media';