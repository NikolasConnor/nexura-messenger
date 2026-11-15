package com.nexura.messenger.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class PendingTextMessage(val id: String, val chatId: String, val text: String, val replyToId: String?)

class SessionStore(context: Context) {
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
    var limitedAccessMode: Boolean
        get() = prefs.getBoolean("limited_access_mode", false)
        set(value) { prefs.edit().putBoolean("limited_access_mode", value).apply() }

    @Synchronized
    fun enqueuePendingMessage(message: PendingTextMessage) {
        val current = getPendingMessages().filterNot { it.id == message.id }
        val array = JSONArray()
        (current + message).forEach { item ->
            array.put(JSONObject().put("id", item.id).put("chat_id", item.chatId)
                .put("text", item.text).put("reply_to_id", item.replyToId ?: JSONObject.NULL))
        }
        prefs.edit().putString("pending_text_messages", array.toString()).apply()
    }

    @Synchronized
    fun getPendingMessages(): List<PendingTextMessage> {
        val raw = prefs.getString("pending_text_messages", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val chatId = item.optString("chat_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PendingTextMessage(id, chatId, item.optString("text"),
                    item.optString("reply_to_id").takeIf { it.isNotBlank() && it != "null" })
            }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun removePendingMessage(id: String) {
        val array = JSONArray()
        getPendingMessages().filterNot { it.id == id }.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("chat_id", item.chatId)
                .put("text", item.text).put("reply_to_id", item.replyToId ?: JSONObject.NULL))
        }
        prefs.edit().putString("pending_text_messages", array.toString()).apply()
    }

    fun clear() { prefs.edit().clear().apply() }
}
