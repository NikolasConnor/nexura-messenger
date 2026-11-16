create table if not exists public.profile_photo_likes (
 id uuid primary key default gen_random_uuid(),
 photo_id uuid not null references public.profile_photos(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 created_at timestamptz not null default now(),
 unique(photo_id,user_id)
);
create index if not exists profile_photo_likes_photo_idx on public.profile_photo_likes(photo_id,created_at desc);
alter table public.profile_photo_likes enable row level security;
drop policy if exists "photo likes visible to authenticated" on public.profile_photo_likes;
create policy "photo likes visible to authenticated" on public.profile_photo_likes for select to authenticated using (
 exists(select 1 from public.profile_photos p where p.id=photo_id)
);
drop policy if exists "users like photos" on public.profile_photo_likes;
create policy "users like photos" on public.profile_photo_likes for insert to authenticated with check (
 auth.uid()=user_id and exists(select 1 from public.profile_photos p where p.id=photo_id)
);
drop policy if exists "users unlike photos" on public.profile_photo_likes;
create policy "users unlike photos" on public.profile_photo_likes for delete to authenticated using (auth.uid()=user_id);
grant select,insert,delete on public.profile_photo_likes to authenticated;

create table if not exists public.profile_photo_comments (
 id uuid primary key default gen_random_uuid(),
 photo_id uuid not null references public.profile_photos(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 content text not null check (char_length(content) between 1 and 1000),
 created_at timestamptz not null default now()
);
create index if not exists profile_photo_comments_photo_idx on public.profile_photo_comments(photo_id,created_at asc);
alter table public.profile_photo_comments enable row level security;
drop policy if exists "photo comments visible to authenticated" on public.profile_photo_comments;
create policy "photo comments visible to authenticated" on public.profile_photo_comments for select to authenticated using (
 exists(select 1 from public.profile_photos p where p.id=photo_id)
);
drop policy if exists "users comment on photos" on public.profile_photo_comments;
create policy "users comment on photos" on public.profile_photo_comments for insert to authenticated with check (
 auth.uid()=user_id and char_length(content) between 1 and 1000 and exists(select 1 from public.profile_photos p where p.id=photo_id)
);
drop policy if exists "users delete own photo comments" on public.profile_photo_comments;
create policy "users delete own photo comments" on public.profile_photo_comments for delete to authenticated using (auth.uid()=user_id);
grant select,insert,delete on public.profile_photo_comments to authenticated;

create table if not exists public.user_story_likes (
 id uuid primary key default gen_random_uuid(),
 story_id uuid not null references public.user_stories(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 created_at timestamptz not null default now(),
 unique(story_id,user_id)
);
create index if not exists user_story_likes_story_idx on public.user_story_likes(story_id,created_at desc);
alter table public.user_story_likes enable row level security;
drop policy if exists "active story likes visible" on public.user_story_likes;
create policy "active story likes visible" on public.user_story_likes for select to authenticated using (
 exists(select 1 from public.user_stories s where s.id=story_id and (s.expires_at>now() or s.user_id=auth.uid()))
);
drop policy if exists "users like active stories" on public.user_story_likes;
create policy "users like active stories" on public.user_story_likes for insert to authenticated with check (
 auth.uid()=user_id and exists(select 1 from public.user_stories s where s.id=story_id and s.expires_at>now())
);
drop policy if exists "users unlike stories" on public.user_story_likes;
create policy "users unlike stories" on public.user_story_likes for delete to authenticated using (auth.uid()=user_id);
grant select,insert,delete on public.user_story_likes to authenticated;
