alter table public.profiles
    add column if not exists username text;

create unique index if not exists profiles_username_lower_unique_idx
    on public.profiles (lower(username))
    where username is not null
      and btrim(username) <> '';
