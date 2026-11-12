-- Enable the authenticated wrapper and add single-device revocation.
grant execute on function private.revoke_my_other_e2ee_devices(uuid) to authenticated;

create or replace function private.revoke_my_e2ee_device(p_device_id uuid)
returns boolean language plpgsql security definer set search_path = '' as $$
declare
    v_user_id uuid := auth.uid();
    v_found boolean := false;
begin
    if v_user_id is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_device_id is null then
        raise exception 'device id required' using errcode = '22004';
    end if;
    select exists (
        select 1 from public.e2ee_devices d
        where d.user_id = v_user_id and d.device_id = p_device_id
    ) into v_found;
    if not v_found then return false; end if;

    insert into private.e2ee_device_revocations(user_id, device_id)
    values (v_user_id, p_device_id)
    on conflict (user_id, device_id) do nothing;

    update public.e2ee_devices
       set is_active = false, updated_at = pg_catalog.now()
     where user_id = v_user_id and device_id = p_device_id and is_active = true;
    delete from public.e2ee_one_time_keys
     where user_id = v_user_id and device_id = p_device_id;
    return true;
end;
$$;
revoke all on function private.revoke_my_e2ee_device(uuid) from public, anon;
grant execute on function private.revoke_my_e2ee_device(uuid) to authenticated;

create or replace function public.revoke_own_e2ee_device(p_device_id uuid)
returns boolean language sql security invoker set search_path = '' as $$
    select private.revoke_my_e2ee_device(p_device_id);
$$;
revoke all on function public.revoke_own_e2ee_device(uuid) from public, anon;
grant execute on function public.revoke_own_e2ee_device(uuid) to authenticated;
