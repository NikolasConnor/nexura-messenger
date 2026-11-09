use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jint, jobjectArray, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use nexura_crypto_core::{
    accept_inbound_session, create_device_keys, decrypt_message, encrypt_message,
    generate_additional_one_time_keys, mark_keys_as_published, pickle_account, pickle_session, start_outbound_session, unpickle_account,
    unpickle_session, verify_signed_one_time_key, PublicDeviceKeys, SignedOneTimeKey,
};

fn set_error(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

fn byte_array_result(env: &mut JNIEnv, values: Vec<Vec<u8>>) -> Result<jobjectArray, String> {
    let array = env
        .new_object_array(values.len() as jint, "[B", JObject::null())
        .map_err(|e| e.to_string())?;
    for (index, value) in values.iter().enumerate() {
        let bytes = env.byte_array_from_slice(value).map_err(|e| e.to_string())?;
        env.set_object_array_element(&array, index as jint, bytes)
            .map_err(|e| e.to_string())?;
    }
    Ok(array.into_raw())
}

fn read_string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value).map(|s| s.into()).map_err(|e| e.to_string())
}

fn read_bytes(env: &mut JNIEnv, value: &JByteArray) -> Result<Vec<u8>, String> {
    env.convert_byte_array(value).map_err(|e| e.to_string())
}

fn utf8<'a>(bytes: &'a [u8], field: &str) -> Result<&'a str, String> {
    std::str::from_utf8(bytes).map_err(|_| format!("{field} is not valid UTF-8"))
}

#[no_mangle]
pub extern "system" fn Java_com_nexura_messenger_data_E2eeNative_nativeCreateDevice(
    mut env: JNIEnv,
    _class: JClass,
    count: jint,
) -> jobjectArray {
    let result = (|| {
        let (account, public): (_, PublicDeviceKeys) = create_device_keys(count.max(0) as usize);
        let pickle = pickle_account(&account).map_err(|e| e.to_string())?;
        let public_json = serde_json::to_vec(&public).map_err(|e| e.to_string())?;
        byte_array_result(&mut env, vec![pickle.into_bytes(), public_json])
    })();
    match result {
        Ok(value) => value,
        Err(error) => { set_error(&mut env, &error); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexura_messenger_data_E2eeNative_nativeGenerateOneTimeKeys(
    mut env: JNIEnv,
    _class: JClass,
    account_pickle: JByteArray,
    count: jint,
) -> jobjectArray {
    let result = (|| {
        if count < 1 || count > 50 {
            return Err("one-time-key count must be between 1 and 50".to_string());
        }
        let account_bytes = read_bytes(&mut env, &account_pickle)?;
        let mut account = unpickle_account(utf8(&account_bytes, "account pickle")?)
            .map_err(|e| e.to_string())?;
        let keys = generate_additional_one_time_keys(&mut account, count as usize);
        let updated = pickle_account(&account).map_err(|e| e.to_string())?;
        let keys_json = serde_json::to_vec(&keys).map_err(|e| e.to_string())?;
        byte_array_result(&mut env, vec![updated.into_bytes(), keys_json])
    })();
    match result {
        Ok(value) => value,
        Err(error) => { set_error(&mut env, &error); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexura_messenger_data_E2eeNative_nativeMarkKeysPublished(
    mut env: JNIEnv,
    _class: JClass,
    account_pickle: JByteArray,
) -> jni::sys::jbyteArray {
    let result = (|| {
        let account_bytes = read_bytes(&mut env, &account_pickle)?;
        let mut account = unpickle_account(utf8(&account_bytes, "account pickle")?)
            .map_err(|e| e.to_string())?;
        mark_keys_as_published(&mut account);
        let updated = pickle_account(&account).map_err(|e| e.to_string())?;
        env.byte_array_from_slice(updated.as_bytes())
            .map(|value| value.into_raw())
            .map_err(|e| e.to_string())
    })();
    match result {
        Ok(value) => value,
        Err(error) => { set_error(&mut env, &error); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexura_messenger_data_E2eeNative_nativeVerifyOneTimeKey(
    mut env: JNIEnv,
    _class: JClass,
    identity: JString,
    key: JString,
    signature: JString,
) -> jboolean {
    let result = (|| {
        let identity = read_string(&mut env, &identity)?;
        let key = read_string(&mut env, &key)?;
        let signature = read_string(&mut env, &signature)?;
        let signed = SignedOneTimeKey { key, signature };
        verify_signed_one_time_key(&identity, &signed).map_err(|e| e.to_string())
    })();
    match result {
        Ok(()) => JNI_TRUE,
        Err(error) => { set_error(&mut env, &error); JNI_FALSE }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexura_messenger_data_E2eeNative_nativeStartOutbound(
    mut env: JNIEnv,
    _class: JClass,
    account_pickle: JByteArray,
    recipient_curve: JString,
    recipient_ed: JString,
    recipient_otk: JString,
    recipient_signature: JString,
    plaintext: JByteArray,
) -> jobjectArray {
    let result = (|| {
        let account_bytes = read_bytes(&mut env, &account_pickle)?;
        let account = unpickle_account(utf8(&account_bytes, "account pickle")?)
            .map_err(|e| e.to_string())?;
        let curve = read_string(&mut env, &recipient_curve)?;
        let ed = read_string(&mut env, &recipient_ed)?;
        let key = read_string(&mut env, &recipient_otk)?;
        let signature = read_string(&mut env, &recipient_signature)?;
        verify_signed_one_time_key(&ed, &SignedOneTimeKey { key: key.clone(), signature })
            .map_err(|_| "recipient one-time key signature is invalid".to_string())?;
        let message = read_bytes(&mut env, &plaintext)?;
        let (session, envelope) = start_outbound_session(&account, &curve, &key, &message)
            .map_err(|e| e.to_string())?;
        let session_pickle = pickle_session(&session).map_err(|e| e.to_string())?;
        byte_array_result(&mut env, vec![session_pickle.into_bytes(), envelope.into_bytes()])
    })();
    match result {
        Ok(value) => value,
        Err(error) => { set_error(&mut env, &error); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexura_messenger_data_E2eeNative_nativeAcceptInbound(
    mut env: JNIEnv,
    _class: JClass,
    account_pickle: JByteArray,
    sender_curve: JString,
    envelope: JString,
) -> jobjectArray {
    let result = (|| {
        let account_bytes = read_bytes(&mut env, &account_pickle)?;
        let mut account = unpickle_account(utf8(&account_bytes, "account pickle")?)
            .map_err(|e| e.to_string())?;
        let sender = read_string(&mut env, &sender_curve)?;
        let envelope = read_string(&mut env, &envelope)?;
        let (session, plaintext) = accept_inbound_session(&mut account, &sender, &envelope)
            .map_err(|e| e.to_string())?;
        let account_pickle = pickle_account(&account).map_err(|e| e.to_string())?;
        let session_pickle = pickle_session(&session).map_err(|e| e.to_string())?;
        byte_array_result(&mut env, vec![
            account_pickle.into_bytes(),
            session_pickle.into_bytes(),
            plaintext,
        ])
    })();
    match result {
        Ok(value) => value,
        Err(error) => { set_error(&mut env, &error); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexura_messenger_data_E2eeNative_nativeEncrypt(
    mut env: JNIEnv,
    _class: JClass,
    session_pickle: JByteArray,
    plaintext: JByteArray,
) -> jobjectArray {
    let result = (|| {
        let session_bytes = read_bytes(&mut env, &session_pickle)?;
        let mut session = unpickle_session(utf8(&session_bytes, "session pickle")?)
            .map_err(|e| e.to_string())?;
        let plaintext = read_bytes(&mut env, &plaintext)?;
        let envelope = encrypt_message(&mut session, &plaintext).map_err(|e| e.to_string())?;
        let updated = pickle_session(&session).map_err(|e| e.to_string())?;
        byte_array_result(&mut env, vec![updated.into_bytes(), envelope.into_bytes()])
    })();
    match result {
        Ok(value) => value,
        Err(error) => { set_error(&mut env, &error); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexura_messenger_data_E2eeNative_nativeDecrypt(
    mut env: JNIEnv,
    _class: JClass,
    session_pickle: JByteArray,
    envelope: JString,
) -> jobjectArray {
    let result = (|| {
        let session_bytes = read_bytes(&mut env, &session_pickle)?;
        let mut session = unpickle_session(utf8(&session_bytes, "session pickle")?)
            .map_err(|e| e.to_string())?;
        let envelope = read_string(&mut env, &envelope)?;
        let plaintext = decrypt_message(&mut session, &envelope).map_err(|e| e.to_string())?;
        let updated = pickle_session(&session).map_err(|e| e.to_string())?;
        byte_array_result(&mut env, vec![updated.into_bytes(), plaintext])
    })();
    match result {
        Ok(value) => value,
        Err(error) => { set_error(&mut env, &error); std::ptr::null_mut() }
    }
}
