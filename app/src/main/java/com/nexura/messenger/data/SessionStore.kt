package com.nexura.messenger.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

class SessionStore(context: Context) {
    private val appContext = context.applicationContext
    val mediaCacheDir: java.io.File
        get() {
            val dir = java.io.File(appContext.cacheDir, "nexura-media-cache")
            if (!dir.exists()) dir.mkdirs()
            val expireBefore = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            dir.listFiles()?.filter { it.lastModified() < expireBefore }?.forEach { it.delete() }
            return dir
        }
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

    data class PendingCloudMessage(val id: String, val chatId: String, val text: String, val replyToId: String?)

    @Synchronized
    fun savePendingCloudMessage(id: String, chatId: String, text: String, replyToId: String?) {
        val all = readPendingCloudMessages().filterNot { it.id == id }.toMutableList()
        all.add(PendingCloudMessage(id, chatId, text, replyToId))
        val array = JSONArray()
        all.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("chat_id", item.chatId)
                .put("text", item.text).put("reply_to_id", item.replyToId ?: JSONObject.NULL))
        }
        prefs.edit().putString("pending_cloud_messages", array.toString()).apply()
    }

    @Synchronized
    fun pendingCloudMessages(): List<PendingCloudMessage> = readPendingCloudMessages()

    @Synchronized
    fun removePendingCloudMessage(id: String) {
        val array = JSONArray()
        readPendingCloudMessages().filterNot { it.id == id }.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("chat_id", item.chatId)
                .put("text", item.text).put("reply_to_id", item.replyToId ?: JSONObject.NULL))
        }
        prefs.edit().putString("pending_cloud_messages", array.toString()).apply()
    }

    private fun readPendingCloudMessages(): List<PendingCloudMessage> {
        val raw = prefs.getString("pending_cloud_messages", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val chatId = item.optString("chat_id")
                val text = item.optString("text")
                if (id.isBlank() || chatId.isBlank() || text.isBlank()) null
                else PendingCloudMessage(id, chatId, text, item.optString("reply_to_id").takeIf { it.isNotBlank() && it != "null" })
            }
        }.getOrDefault(emptyList())
    }

    fun clear() { prefs.edit().clear().apply() }
}

