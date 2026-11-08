package com.nexura.messenger.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatItem(val id: String, val title: String, val kind: String, val updated: String?)
data class MessageItem(val id: String, val senderId: String, val content: String, val createdAt: String)

class NexuraApi(private val store: SessionStore) {
    companion object {
        const val BASE_URL = "https://lyhzawblxcvpgpjrkspc.supabase.co"
        const val PUBLIC_KEY = "sb_publishable_G-EJKck-KO2cgoxaMlg9ag_CQZvDUEY"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private suspend fun call(path: String, method: String = "GET", body: String? = null, auth: Boolean = true, prefer: String? = null): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("$BASE_URL$path")
            .header("apikey", PUBLIC_KEY).header("Accept", "application/json")
        val token = if (auth) store.accessToken else null
        builder.header("Authorization", "Bearer ${token ?: PUBLIC_KEY}")
        if (prefer != null) builder.header("Prefer", prefer)
        val requestBody = body?.toRequestBody(JSON)
        when (method) {
            "POST" -> builder.post(requestBody ?: "".toRequestBody(JSON))
            "PATCH" -> builder.patch(requestBody ?: "".toRequestBody(JSON))
            "DELETE" -> builder.delete(requestBody)
            else -> builder.get()
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = try { JSONObject(text).optString("message").ifBlank { text } } catch (_: Exception) { text }
                throw IOException(message.ifBlank { "HTTP ${response.code}" })
            }
            text
        }
    }

    suspend fun signIn(email: String, password: String) {
        val result = JSONObject(call("/auth/v1/token?grant_type=password", "POST",
            JSONObject().put("email", email).put("password", password).toString(), auth = false))
        saveSession(result)
    }
    suspend fun signUp(email: String, password: String, displayName: String) {
        val result = JSONObject(call("/auth/v1/signup", "POST",
            JSONObject().put("email", email).put("password", password)
                .put("data", JSONObject().put("display_name", displayName)).toString(), auth = false))
        if (result.has("access_token")) saveSession(result)
        else throw IOException("Аккаунт создан. Подтвердите email, затем войдите.")
    }
    private fun saveSession(o: JSONObject) {
        store.accessToken = o.optString("access_token").takeIf { it.isNotBlank() }
        store.refreshToken = o.optString("refresh_token").takeIf { it.isNotBlank() }
        store.userId = o.optJSONObject("user")?.optString("id")
            ?: o.optJSONObject("session")?.optJSONObject("user")?.optString("id")
    }
    suspend fun refreshSession() {
        val refresh = store.refreshToken ?: return
        val o = JSONObject(call("/auth/v1/token?grant_type=refresh_token", "POST",
            JSONObject().put("refresh_token", refresh).toString(), auth = false))
        saveSession(o)
    }
    suspend fun signOut() {
        try { call("/auth/v1/logout", "POST", "{}", auth = true) } finally { store.clear() }
    }
    suspend fun chats(): List<ChatItem> {
        val uid = store.userId ?: return emptyList()
        val raw = JSONArray(call("/rest/v1/conversation_members?select=conversation_id,conversations(id,kind,title,last_message_at)&user_id=eq.$uid&order=joined_at.desc"))
        return (0 until raw.length()).mapNotNull { i ->
            val c = raw.optJSONObject(i)?.optJSONObject("conversations") ?: return@mapNotNull null
            ChatItem(c.optString("id"), c.optString("title").ifBlank { if (c.optString("kind") == "direct") "Личный чат" else "Группа" },
                c.optString("kind"), c.optString("last_message_at").takeIf { it.isNotBlank() })
        }
    }
    suspend fun messages(chatId: String): List<MessageItem> {
        val raw = JSONArray(call("/rest/v1/messages?select=id,sender_id,content,created_at&conversation_id=eq.$chatId&deleted_at=is.null&order=created_at.asc&limit=300"))
        return (0 until raw.length()).map { i ->
            val m = raw.getJSONObject(i)
            MessageItem(m.getString("id"), m.optString("sender_id"), m.optString("content"), m.optString("created_at"))
        }
    }
    suspend fun sendMessage(chatId: String, text: String) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        call("/rest/v1/messages", "POST",
            JSONObject().put("conversation_id", chatId).put("sender_id", uid).put("message_type", "text").put("content", text).toString(),
            prefer = "return=minimal")
    }
    suspend fun findUsers(query: String): List<Pair<String,String>> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val raw = JSONArray(call("/rest/v1/profiles?select=id,display_name,username&or=(display_name.ilike.%25$encoded%25,username.ilike.%25$encoded%25)&limit=20"))
        return (0 until raw.length()).map { i ->
            val p = raw.getJSONObject(i)
            p.optString("id") to (p.optString("display_name").ifBlank { p.optString("username") })
        }.filter { it.first != store.userId }
    }
    suspend fun createDirect(userId: String): String {
        val result = JSONObject(call("/rest/v1/rpc/create_direct_conversation", "POST",
            JSONObject().put("p_other_user_id", userId).toString()))
        return result.trim().removeSurrounding("\\\"")
    }
}
