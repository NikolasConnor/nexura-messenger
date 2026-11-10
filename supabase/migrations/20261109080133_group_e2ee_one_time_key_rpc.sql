create or replace function private.consume_group_e2ee_one_time_key(
    p_conversation_id uuid,
    p_recipient_user_id uuid,
    p_device_id uuid
)
returns table(
    device_id uuid,
    curve25519_key text,
    ed25519_key text,
    one_time_key_id uuid,
    one_time_key text,
    one_time_key_signature text
)
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_device public.e2ee_devices%rowtype;
    v_key public.e2ee_one_time_keys%rowtype;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    if not exists (
        select 1 from public.conversations c
        where c.id = p_conversation_id and c.kind = 'group'
    ) then
        raise exception 'group conversation required' using errcode = '42501';
    end if;

    if not exists (
        select 1 from public.conversation_members cm
        where cm.conversation_id = p_conversation_id and cm.user_id = auth.uid()
    ) or not exists (
        select 1 from public.conversation_members cm
        where cm.conversation_id = p_conversation_id and cm.user_id = p_recipient_user_id
    ) then
        raise exception 'conversation membership required' using errcode = '42501';
    end if;

    select d.* into v_device
    from public.e2ee_devices d
    where d.user_id = p_recipient_user_id
      and d.device_id = p_device_id
      and d.is_active = true
    for share;

    if not found then
        raise exception 'active E2EE device not found' using errcode = 'P0002';
    end if;

    select k.* into v_key
    from public.e2ee_one_time_keys k
    where k.user_id = p_recipient_user_id
      and k.device_id = p_device_id
      and k.consumed_at is null
    order by k.created_at
    limit 1
    for update skip locked;

    if not found then
        raise exception 'no available one-time key' using errcode = 'P0002';
    end if;

    update public.e2ee_one_time_keys k
       set consumed_at = pg_catalog.now()
     where k.user_id = v_key.user_id
       and k.device_id = v_key.device_id
       and k.key_id = v_key.key_id
       and k.consumed_at is null;

    if not found then
        raise exception 'one-time key was already consumed' using errcode = '40001';
    end if;

    return query
    select v_device.device_id,
           v_device.curve25519_key,
           v_device.ed25519_key,
           v_key.key_id,
           v_key.curve25519_key,
           v_key.signature;
end;
$function$;

revoke all on function private.consume_group_e2ee_one_time_key(uuid, uuid, uuid) from public, anon;
grant execute on function private.consume_group_e2ee_one_time_key(uuid, uuid, uuid) to authenticated;

create or replace function public.consume_group_e2ee_one_time_key(
    p_conversation_id uuid,
    p_recipient_user_id uuid,
    p_device_id uuid
)
returns table(
    device_id uuid,
    curve25519_key text,
    ed25519_key text,
    one_time_key_id uuid,
    one_time_key text,
    one_time_key_signature text
)
language sql
set search_path = ''
as $function$
    select * from private.consume_group_e2ee_one_time_key(
        p_conversation_id,
        p_recipient_user_id,
        p_device_id
    );
$function$;

revoke all on function public.consume_group_e2ee_one_time_key(uuid, uuid, uuid) from public, anon;
grant execute on function public.consume_group_e2ee_one_time_key(uuid, uuid, uuid) to authenticated;
