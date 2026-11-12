-- Permanent E2EE device revocation.
-- A revoked device ID is tombstoned and can never be registered again.
create table if not exists private.e2ee_device_revocations (
    user_id uuid not null references auth.users(id) on delete cascade,
    device_id uuid not null,
    revoked_at timestamptz not null default pg_catalog.now(),
    primary key (user_id, device_id)
);
revoke all on table private.e2ee_device_revocations from public, anon, authenticated;

drop policy if exists e2ee_devices_delete_owner on public.e2ee_devices;
revoke delete on table public.e2ee_devices from authenticated;

create or replace function private.prevent_revoked_e2ee_device_registration()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    if exists (
        select 1 from private.e2ee_device_revocations r
        where r.user_id = new.user_id and r.device_id = new.device_id
    ) then
        raise exception 'E2EE device has been revoked and cannot be registered again'
            using errcode = '42501';
    end if;
    return new;
end;
$$;
revoke all on function private.prevent_revoked_e2ee_device_registration() from public, anon, authenticated;
drop trigger if exists prevent_revoked_e2ee_device_registration on public.e2ee_devices;
create trigger prevent_revoked_e2ee_device_registration
before insert on public.e2ee_devices
for each row execute function private.prevent_revoked_e2ee_device_registration();

create or replace function private.prevent_otk_for_inactive_e2ee_device()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    if not exists (
        select 1 from public.e2ee_devices d
        where d.user_id = new.user_id and d.device_id = new.device_id and d.is_active = true
    ) or exists (
        select 1 from private.e2ee_device_revocations r
        where r.user_id = new.user_id and r.device_id = new.device_id
    ) then
        raise exception 'E2EE device is inactive or revoked' using errcode = '42501';
    end if;
    return new;
end;
$$;
revoke all on function private.prevent_otk_for_inactive_e2ee_device() from public, anon, authenticated;
drop trigger if exists prevent_otk_for_inactive_e2ee_device on public.e2ee_one_time_keys;
create trigger prevent_otk_for_inactive_e2ee_device
before insert on public.e2ee_one_time_keys
for each row execute function private.prevent_otk_for_inactive_e2ee_device();

create or replace function private.revoke_my_other_e2ee_devices(p_keep_device_id uuid)
returns integer language plpgsql security definer set search_path = '' as $$
declare
    v_user_id uuid := auth.uid();
    v_device_ids uuid[];
    v_count integer := 0;
begin
    if v_user_id is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if p_keep_device_id is null or not exists (
        select 1 from public.e2ee_devices d
        where d.user_id = v_user_id and d.device_id = p_keep_device_id and d.is_active = true
    ) then
        raise exception 'current active E2EE device not found' using errcode = 'P0002';
    end if;
    select coalesce(array_agg(d.device_id), array[]::uuid[])
      into v_device_ids
    from public.e2ee_devices d
    where d.user_id = v_user_id and d.is_active = true and d.device_id <> p_keep_device_id;
    if cardinality(v_device_ids) = 0 then return 0; end if;

    insert into private.e2ee_device_revocations(user_id, device_id)
    select v_user_id, x.device_id from unnest(v_device_ids) as x(device_id)
    on conflict (user_id, device_id) do nothing;

    update public.e2ee_devices
       set is_active = false, updated_at = pg_catalog.now()
     where user_id = v_user_id and device_id = any(v_device_ids) and is_active = true;
    get diagnostics v_count = row_count;

    delete from public.e2ee_one_time_keys
     where user_id = v_user_id and device_id = any(v_device_ids);
    return v_count;
end;
$$;
revoke all on function private.revoke_my_other_e2ee_devices(uuid) from public, anon, authenticated;
grant usage on schema private to authenticated;

create or replace function public.revoke_other_own_e2ee_devices(p_keep_device_id uuid)
returns integer language sql security invoker set search_path = '' as $$
    select private.revoke_my_other_e2ee_devices(p_keep_device_id);
$$;
revoke all on function public.revoke_other_own_e2ee_devices(uuid) from public, anon;
grant execute on function public.revoke_other_own_e2ee_devices(uuid) to authenticated;
