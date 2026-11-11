-- Keep encrypted call history compatible with the messages type constraint.
-- Call records are encrypted payloads stored in the same messages table.
ALTER TABLE public.messages
  DROP CONSTRAINT IF EXISTS messages_message_type_check;

ALTER TABLE public.messages
  ADD CONSTRAINT messages_message_type_check
  CHECK (message_type = ANY (ARRAY[
    'text'::text,
    'image'::text,
    'media'::text,
    'file'::text,
    'system'::text,
    'photo'::text,
    'video'::text,
    'audio'::text,
    'voice'::text,
    'document'::text,
    'sticker'::text,
    'call'::text
  ]));
