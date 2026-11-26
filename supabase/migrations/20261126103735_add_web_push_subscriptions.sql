create table public.web_push_subscriptions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  endpoint text not null unique,
  p256dh text not null,
  auth text not null,
  expiration_time bigint,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint web_push_endpoint_https check (endpoint like 'https://%'),
  constraint web_push_p256dh_length check (char_length(p256dh) between 80 and 100),
  constraint web_push_auth_length check (char_length(auth) between 16 and 64)
);

create index web_push_subscriptions_user_id_idx on public.web_push_subscriptions(user_id);
alter table public.web_push_subscriptions enable row level security;

create policy "Users can read own web push subscriptions"
  on public.web_push_subscriptions for select to authenticated
  using ((select auth.uid()) = user_id);
create policy "Users can register own web push subscriptions"
  on public.web_push_subscriptions for insert to authenticated
  with check ((select auth.uid()) = user_id);
create policy "Users can update own web push subscriptions"
  on public.web_push_subscriptions for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);
create policy "Users can delete own web push subscriptions"
  on public.web_push_subscriptions for delete to authenticated
  using ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.web_push_subscriptions to authenticated;
