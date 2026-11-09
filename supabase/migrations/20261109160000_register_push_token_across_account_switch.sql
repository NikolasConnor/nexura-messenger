-- Reassign an FCM token safely when the same Android install changes accounts.
-- The token is globally unique; ordinary RLS upsert cannot transfer ownership
-- from the previous account to the currently authenticated account.
create or replace function private.register_own_device_token_impl(p_token text)
returns void
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_user_id uuid := auth.uid();
  v_token text := btrim(coalesce(p_token, ''));
begin
  if v_user_id is null then
    raise exception 'Authentication required' using errcode = '28000';
  end if;
  if length(v_token) < 20 or length(v_token) > 4096 then
    raise exception 'Invalid device token' using errcode = '22023';
  end if;

  insert into public.device_tokens(user_id, token, platform, updated_at)
  values (v_user_id, v_token, 'android', now())
  on conflict (token) do update
    set user_id = excluded.user_id,
        platform = 'android',
        updated_at = now();
end;
$function$;

revoke all on function private.register_own_device_token_impl(text) from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.register_own_device_token_impl(text) to authenticated;

create or replace function public.register_own_device_token(p_token text)
returns void
language sql
security invoker
set search_path = ''
as $function$
  select private.register_own_device_token_impl(p_token);
$function$;

revoke all on function public.register_own_device_token(text) from public, anon;
grant execute on function public.register_own_device_token(text) to authenticated;
