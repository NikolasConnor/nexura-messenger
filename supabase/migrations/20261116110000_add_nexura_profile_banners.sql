alter table public.profiles add column if not exists banner_url text;
comment on column public.profiles.banner_url is 'Optional public profile cover image URL; only the profile owner can update it.';
