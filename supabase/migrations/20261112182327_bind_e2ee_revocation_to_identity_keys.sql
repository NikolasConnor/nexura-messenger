-- Bind a revocation tombstone to the cryptographic identity, not just its device UUID.
alter table private.e2ee_device_revocations
    add column if not exists curve25519_key text,
    add column if not exists ed25519_key text;

update private.e2ee_device_revocations r
   set curve25519_key = d.curve25519_key,
       ed25519_key = d.ed25519_key
  from public.e2ee_devices d
 where d.user_id = r.user_id
   and d.device_id = r.device_id
   and r.curve25519_key is null
   and r.ed25519_key is null;

create unique index if not exists e2ee_devices_identity_key_pair_uq
    on public.e2ee_devices(user_id, curve25519_key, ed25519_key);
create unique index if not exists e2ee_device_revocations_identity_key_pair_uq
    on private.e2ee_device_revocations(user_id, curve25519_key, ed25519_key)
    where curve25519_key is not null and ed25519_key is not null;

create or replace function private.prevent_revoked_e2ee_device_registration()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
    if exists (
        select 1 from private.e2ee_device_revocations r
        where r.user_id = new.user_id
          and (r.device_id = new.device_id or
               (r.curve25519_key = new.curve25519_key and r.ed25519_key = new.ed25519_key))
    ) then
        raise exception 'E2EE device identity has been revoked and cannot be registered again'
            using errcode = '42501';
    end if;
    return new;
end;
$$;

create or replace function private.revoke_my_other_e2ee_devices(p_keep_device_id uuid)
returns integer language plpgsql security definer set search_path = '' as $$
declare
    v_user_id uuid := auth.uid();
    v_device_ids uuid[];
    v_count integer := 0;
begin
    if v_user_id is null then raise exception 'authentication required' using errcode = '28000'; end if;
    if p_keep_device_id is null or not exists (
        select 1 from public.e2ee_devices d
        where d.user_id = v_user_id and d.device_id = p_keep_device_id and d.is_active = true
    ) then raise exception 'current active E2EE device not found' using errcode = 'P0002'; end if;

    select coalesce(array_agg(d.device_id), array[]::uuid[]) into v_device_ids
    from public.e2ee_devices d
    where d.user_id = v_user_id and d.is_active = true and d.device_id <> p_keep_device_id;
    if cardinality(v_device_ids) = 0 then return 0; end if;

    insert into private.e2ee_device_revocations(user_id, device_id, curve25519_key, ed25519_key)
    select d.user_id, d.device_id, d.curve25519_key, d.ed25519_key
    from public.e2ee_devices d
    where d.user_id = v_user_id and d.device_id = any(v_device_ids)
    on conflict do nothing;

    update public.e2ee_devices set is_active = false, updated_at = pg_catalog.now()
    where user_id = v_user_id and device_id = any(v_device_ids) and is_active = true;
    get diagnostics v_count = row_count;
    delete from public.e2ee_one_time_keys where user_id = v_user_id and device_id = any(v_device_ids);
    return v_count;
end;
$$;

create or replace function private.revoke_my_e2ee_device(p_device_id uuid)
returns boolean language plpgsql security definer set search_path = '' as $$
declare
    v_user_id uuid := auth.uid();
    v_found boolean := false;
begin
    if v_user_id is null then raise exception 'authentication required' using errcode = '28000'; end if;
    if p_device_id is null then raise exception 'device id required' using errcode = '22004'; end if;
    select exists (
        select 1 from public.e2ee_devices d where d.user_id = v_user_id and d.device_id = p_device_id
    ) into v_found;
    if not v_found then return false; end if;

    insert into private.e2ee_device_revocations(user_id, device_id, curve25519_key, ed25519_key)
    select d.user_id, d.device_id, d.curve25519_key, d.ed25519_key
    from public.e2ee_devices d where d.user_id = v_user_id and d.device_id = p_device_id
    on conflict do nothing;

    update public.e2ee_devices set is_active = false, updated_at = pg_catalog.now()
    where user_id = v_user_id and device_id = p_device_id and is_active = true;
    delete from public.e2ee_one_time_keys where user_id = v_user_id and device_id = p_device_id;
    return true;
end;
$$;
