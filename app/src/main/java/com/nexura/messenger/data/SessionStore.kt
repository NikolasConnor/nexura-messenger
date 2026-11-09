package com.nexura.messenger.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionStore(context: Context) {
    private val appContext = context.applicationContext
    val mediaCacheDir: java.io.File get() = java.io.File(appContext.cacheDir, "nexura-media-cache").also { if (!it.exists()) it.mkdirs() }
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "nexura_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) { prefs.edit().putString("access_token", value).apply() }
    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) { prefs.edit().putString("refresh_token", value).apply() }
    var userId: String?
        get() = prefs.getString("user_id", null)
        set(value) { prefs.edit().putString("user_id", value).apply() }
    fun clear() { prefs.edit().clear().apply() }
}

