-- Profile cover videos are stored in the private social bucket under {user_id}/banners/.
-- Any signed-in Nexura user may view a profile cover; upload and delete remain owner-only.
drop policy if exists "social profile banners readable" on storage.objects;
create policy "social profile banners readable"
on storage.objects
for select
to authenticated
using (
  bucket_id = 'nexura-social-media'
  and (storage.foldername(name))[2] = 'banners'
);
