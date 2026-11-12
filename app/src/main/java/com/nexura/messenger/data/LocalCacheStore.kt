package com.nexura.messenger.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted offline cache. Message bodies are encrypted again at rest with
 * an Android Keystore AES-GCM key; plaintext is never written to SQLite.
 */
class LocalCacheStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "nexura_local_cache.db", null, 1
) {
    companion object {
        private const val KEY_ALIAS = "nexura_local_cache_aes_v1"
        private const val TABLE = "cache_entries"
        private const val MAX_CACHED_MESSAGES = 300
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "user_id TEXT NOT NULL, cache_key TEXT NOT NULL, payload BLOB NOT NULL, updated_at INTEGER NOT NULL, " +
                "PRIMARY KEY(user_id, cache_key))"
        )
        db.execSQL("CREATE INDEX idx_nexura_cache_updated ON $TABLE(user_id, updated_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(userId: String, cacheKey: String, value: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD("$userId:$cacheKey".toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return cipher.iv + encrypted
    }

    private fun decrypt(userId: String, cacheKey: String, payload: ByteArray): String {
        require(payload.size > 12) { "Повреждённая запись локального кэша" }
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD("$userId:$cacheKey".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun put(userId: String, cacheKey: String, value: String) {
        if (userId.isBlank()) return
        val encrypted = encrypt(userId, cacheKey, value)
        val values = ContentValues().apply {
            put("user_id", userId)
            put("cache_key", cacheKey)
            put("payload", encrypted)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun get(userId: String, cacheKey: String): String? {
        if (userId.isBlank()) return null
        readableDatabase.query(
            TABLE, arrayOf("payload"), "user_id=? AND cache_key=?",
            arrayOf(userId, cacheKey), null, null, null, "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return decrypt(userId, cacheKey, cursor.getBlob(0))
        }
    }

    fun saveChats(userId: String, chats: List<ChatItem>) {
        val array = JSONArray()
        chats.forEach { chat ->
            array.put(JSONObject()
                .put("id", chat.id)
                .put("title", chat.title)
                .put("kind", chat.kind)
                .put("updated", chat.updated)
                .put("unread", chat.unreadCount)
                .put("avatar", chat.avatar)
                .put("peer", chat.peerUserId))
        }
        put(userId, "chats", array.toString())
    }

    fun readChats(userId: String): List<ChatItem> = runCatching {
        val array = JSONArray(get(userId, "chats") ?: return emptyList())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            ChatItem(
                id = id,
                title = item.optString("title", "Чат"),
                kind = item.optString("kind", "direct"),
                updated = item.optString("updated").takeIf { it.isNotBlank() && it != "null" },
                unreadCount = item.optInt("unread"),
                avatar = item.optString("avatar", "🙂"),
                peerUserId = item.optString("peer").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }.getOrDefault(emptyList())

    fun saveMessages(userId: String, chatId: String, messages: List<MessageItem>) {
        val array = JSONArray()
        messages.sortedWith(compareBy<MessageItem> { it.createdAt }.thenBy { it.id })
            .takeLast(MAX_CACHED_MESSAGES).forEach { message ->
                array.put(JSONObject()
                    .put("id", message.id)
                    .put("sender", message.senderId)
                    .put("content", message.content)
                    .put("created", message.createdAt)
                    .put("type", message.messageType)
                    .put("reply", message.replyToId)
                    .put("edited", message.editedAt)
                    .put("encrypted", message.encrypted)
                    .put("warning", message.securityWarning)
                    .put("mediaKind", message.mediaKind)
                    .put("mediaCaption", message.mediaCaption)
                    .put("mediaName", message.mediaFileName)
                    .put("mediaMime", message.mediaMimeType))
            }
        put(userId, "messages:$chatId", array.toString())
    }

    fun readMessages(userId: String, chatId: String): List<MessageItem> = runCatching {
        val array = JSONArray(get(userId, "messages:$chatId") ?: return emptyList())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            MessageItem(
                id = id,
                senderId = item.optString("sender"),
                content = item.optString("content"),
                createdAt = item.optString("created"),
                messageType = item.optString("type", "text"),
                replyToId = item.optString("reply").takeIf { it.isNotBlank() && it != "null" },
                editedAt = item.optString("edited").takeIf { it.isNotBlank() && it != "null" },
                encrypted = item.optBoolean("encrypted"),
                securityWarning = item.optBoolean("warning"),
                mediaKind = item.optString("mediaKind").takeIf { it.isNotBlank() && it != "null" },
                mediaCaption = item.optString("mediaCaption").takeIf { it.isNotBlank() && it != "null" },
                mediaFileName = item.optString("mediaName").takeIf { it.isNotBlank() && it != "null" },
                mediaMimeType = item.optString("mediaMime").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }.getOrDefault(emptyList())

    fun clearUser(userId: String) {
        if (userId.isNotBlank()) writableDatabase.delete(TABLE, "user_id=?", arrayOf(userId))
    }
}
