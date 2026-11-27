create table if not exists public.profile_photos (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references auth.users(id) on delete cascade,
 storage_path text not null unique, caption text not null default '', created_at timestamptz not null default now()
);
create index if not exists profile_photos_user_created_idx on public.profile_photos(user_id, created_at desc);
alter table public.profile_photos enable row level security;
drop policy if exists "profile photos visible to everyone" on public.profile_photos;
create policy "profile photos visible to everyone" on public.profile_photos for select to authenticated using (true);
drop policy if exists "users add own profile photos" on public.profile_photos;
create policy "users add own profile photos" on public.profile_photos for insert to authenticated with check (auth.uid() = user_id);
drop policy if exists "users delete own profile photos" on public.profile_photos;
create policy "users delete own profile photos" on public.profile_photos for delete to authenticated using (auth.uid() = user_id);
grant select, insert, delete on public.profile_photos to authenticated;
create table if not exists public.user_stories (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references auth.users(id) on delete cascade,
 storage_path text not null unique, media_type text not null check (media_type in ('image','video')),
 caption text not null default '', created_at timestamptz not null default now(),
 expires_at timestamptz not null default (now() + interval '24 hours'),
 constraint user_stories_expiry_window check (expires_at > created_at and expires_at <= created_at + interval '24 hours 5 minutes')
);
create index if not exists user_stories_active_idx on public.user_stories(expires_at desc);
create index if not exists user_stories_owner_archive_idx on public.user_stories(user_id, created_at desc);
alter table public.user_stories enable row level security;
drop policy if exists "active stories and own archive visible" on public.user_stories;
create policy "active stories and own archive visible" on public.user_stories for select to authenticated using (expires_at > now() or auth.uid() = user_id);
drop policy if exists "users publish own stories" on public.user_stories;
create policy "users publish own stories" on public.user_stories for insert to authenticated with check (auth.uid() = user_id and expires_at <= created_at + interval '24 hours 5 minutes' and expires_at > created_at);
drop policy if exists "users delete own stories" on public.user_stories;
create policy "users delete own stories" on public.user_stories for delete to authenticated using (auth.uid() = user_id);
grant select, insert, delete on public.user_stories to authenticated;
insert into storage.buckets (id,name,public,file_size_limit,allowed_mime_types)
values ('nexura-social-media','nexura-social-media',false,52428800,array['image/jpeg','image/png','image/webp','image/heic','video/mp4','video/webm','video/quicktime'])
on conflict (id) do update set public=false,file_size_limit=52428800,allowed_mime_types=excluded.allowed_mime_types;
drop policy if exists "social media upload own" on storage.objects;
create policy "social media upload own" on storage.objects for insert to authenticated with check (bucket_id='nexura-social-media' and (storage.foldername(name))[1]=auth.uid()::text);
drop policy if exists "social media read allowed" on storage.objects;
create policy "social media read allowed" on storage.objects for select to authenticated using (
 bucket_id='nexura-social-media' and (
  ((storage.foldername(name))[2]='photos' and exists(select 1 from public.profile_photos p where p.storage_path=name))
  or ((storage.foldername(name))[2]='stories' and exists(select 1 from public.user_stories s where s.storage_path=name and (s.expires_at>now() or s.user_id=auth.uid())))
 )
);
drop policy if exists "social media delete own" on storage.objects;
create policy "social media delete own" on storage.objects for delete to authenticated using (bucket_id='nexura-social-media' and (storage.foldername(name))[1]=auth.uid()::text);
