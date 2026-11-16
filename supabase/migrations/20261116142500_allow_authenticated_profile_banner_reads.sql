-- Keep social profile media private by default while allowing signed-in users
-- to view profile banners, published photos, and active stories.
drop policy if exists "social media read allowed" on storage.objects;
create policy "social media read allowed"
on storage.objects
for select
to authenticated
using (
  bucket_id = 'nexura-social-media'
  and (
    (storage.foldername(name))[2] = 'banners'
    or (
      (storage.foldername(name))[2] = 'photos'
      and exists (
        select 1 from public.profile_photos p where p.storage_path = name
      )
    )
    or (
      (storage.foldername(name))[2] = 'stories'
      and exists (
        select 1 from public.user_stories s
        where s.storage_path = name
          and (s.expires_at > now() or s.user_id = auth.uid())
      )
    )
  )
);
