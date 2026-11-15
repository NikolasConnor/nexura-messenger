-- Every Nexura account has one public, case-insensitive username.
-- Empty values remain NULL until the user completes mandatory onboarding.
alter table public.profiles
    add column if not exists username text;

-- Keep the first existing owner of a duplicated handle; affected accounts are
-- prompted to choose a new unique username on their next Android sign-in.
with ranked as (
    select id,
           row_number() over (
               partition by lower(btrim(username))
               order by id
           ) as position
    from public.profiles
    where username is not null
      and btrim(username) <> ''
)
update public.profiles as p
set username = null
from ranked as r
where p.id = r.id
  and r.position > 1;

update public.profiles
set username = lower(btrim(username))
where username is not null
  and username <> lower(btrim(username));

create unique index if not exists profiles_username_lower_unique_idx
    on public.profiles (lower(username))
    where username is not null
      and btrim(username) <> '';
