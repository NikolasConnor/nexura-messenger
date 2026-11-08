package com.nexura.messenger.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID

data class ChatItem(val id: String, val title: String, val kind: String, val updated: String?)
data class UserProfile(val id: String, val displayName: String, val username: String, val avatar: String)
data class MessageItem(val id: String, val senderId: String, val content: String, val createdAt: String, val messageType: String = "text")

class NexuraApi(private val store: SessionStore) {
    companion object {
        const val BASE_URL = "https://lyhzawblxcvpgpjrkspc.supabase.co"
        const val PUBLIC_KEY = "sb_publishable_G-EJKck-KO2cgoxaMlg9ag_CQZvDUEY"
        const val AUTH_REDIRECT = "nexura://auth/callback"
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

    suspend fun consumeAuthCallback(uri: Uri) {
        val fragment = uri.fragment.orEmpty()
        val params = Uri.parse("https://nexura.invalid/?$fragment")
        val errorDescription = params.getQueryParameter("error_description")
            ?: uri.getQueryParameter("error_description")
        if (!errorDescription.isNullOrBlank()) throw IOException(Uri.decode(errorDescription))
        val access = params.getQueryParameter("access_token")
            ?: uri.getQueryParameter("access_token")
            ?: throw IOException("В ссылке подтверждения нет токена авторизации. Запросите новое письмо.")
        val refresh = params.getQueryParameter("refresh_token")
            ?: uri.getQueryParameter("refresh_token")
        if (refresh.isNullOrBlank()) throw IOException("Не удалось получить токен обновления. Запросите новое письмо.")
        store.accessToken = access
        store.refreshToken = refresh
        val user = JSONObject(call("/auth/v1/user", auth = true))
        store.userId = user.optString("id").takeIf { it.isNotBlank() }
            ?: throw IOException("Supabase не вернул профиль пользователя.")
    }

    suspend fun sendPhoneCode(phone: String, displayName: String = "") {
        val payload = JSONObject().put("phone", phone.trim()).put("create_user", true)
        if (displayName.isNotBlank()) payload.put("data", JSONObject().put("display_name", displayName.trim()))
        call("/auth/v1/otp", "POST", payload.toString(), auth = false)
    }
    suspend fun verifyPhoneCode(phone: String, code: String) {
        val result = JSONObject(call("/auth/v1/verify", "POST",
            JSONObject().put("phone", phone.trim()).put("token", code.trim()).put("type", "sms").toString(),
            auth = false))
        saveSession(result)
        if (store.accessToken.isNullOrBlank() || store.userId.isNullOrBlank()) {
            throw IOException("Код принят, но Supabase не вернул полноценную сессию. Попробуйте войти ещё раз.")
        }
    }

    suspend fun signIn(email: String, password: String) {
        val result = JSONObject(call("/auth/v1/token?grant_type=password", "POST",
            JSONObject().put("email", email).put("password", password).toString(), auth = false))
        saveSession(result)
    }
    suspend fun signUp(email: String, password: String, displayName: String) {
        val redirect = java.net.URLEncoder.encode(AUTH_REDIRECT, "UTF-8")
        val result = JSONObject(call("/auth/v1/signup?redirect_to=$redirect", "POST",
            JSONObject().put("email", email).put("password", password)
                .put("data", JSONObject().put("display_name", displayName)).toString(), auth = false))
        if (result.has("access_token")) saveSession(result)
        else throw IOException("Аккаунт создан. Подтвердите email по ссылке из письма, затем вернитесь в Nexura.")
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
    suspend fun resendConfirmation(email: String) {
        val redirect = java.net.URLEncoder.encode(AUTH_REDIRECT, "UTF-8")
        call("/auth/v1/resend?redirect_to=$redirect", "POST",
            JSONObject().put("type", "signup").put("email", email.trim()).toString(),
            auth = false)
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
            val type = m.optString("message_type", "text")
            val content = m.optString("content")
            val rendered = if (type == "image" && content.isNotBlank()) signedAttachmentUrl(content) else content
            MessageItem(m.getString("id"), m.optString("sender_id"), rendered, m.optString("created_at"), type)
        }
    }
    suspend fun sendMessage(chatId: String, text: String) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        call("/rest/v1/messages", "POST",
            JSONObject().put("conversation_id", chatId).put("sender_id", uid).put("message_type", "text").put("content", text).toString(),
            prefer = "return=minimal")
    }
    private suspend fun signedAttachmentUrl(path: String): String {
        val payload = JSONObject().put("expiresIn", 3600)
        val result = JSONObject(call("/storage/v1/object/sign/chat-attachments/$path", "POST", payload.toString()))
        val signed = result.optString("signedURL").ifBlank { result.optString("signedUrl") }
        if (signed.isBlank()) throw IOException("Не удалось получить ссылку на изображение")
        return if (signed.startsWith("http")) signed else "$BASE_URL/storage/v1$signed"
    }

    suspend fun sendImageMessage(chatId: String, bytes: ByteArray, mimeType: String) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        if (mimeType !in listOf("image/jpeg", "image/png", "image/webp", "image/gif")) {
            throw IOException("Поддерживаются JPG, PNG, WEBP и GIF")
        }
        if (bytes.size > 20 * 1024 * 1024) throw IOException("Максимальный размер изображения — 20 МБ")
        val extension = when (mimeType) { "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "jpg" }
        val path = "$chatId/${UUID.randomUUID()}.$extension"
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$BASE_URL/storage/v1/object/chat-attachments/$path")
                .header("apikey", PUBLIC_KEY)
                .header("Authorization", "Bearer ${store.accessToken ?: PUBLIC_KEY}")
                .header("Content-Type", mimeType)
                .post(bytes.toRequestBody(mimeType.toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException(response.body?.string().orEmpty().ifBlank { "Не удалось загрузить изображение" })
            }
        }
        call("/rest/v1/messages", "POST",
            JSONObject().put("conversation_id", chatId).put("sender_id", uid).put("message_type", "image").put("content", path).toString(),
            prefer = "return=minimal")
    }

    suspend fun myProfile(): UserProfile {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val raw = JSONArray(call("/rest/v1/profiles?select=id,display_name,username,avatar_url&id=eq.$uid&limit=1"))
        if (raw.length() == 0) {
            val authUser = JSONObject(call("/auth/v1/user", auth = true))
            val metadata = authUser.optJSONObject("user_metadata") ?: JSONObject()
            val emailName = authUser.optString("email").substringBefore("@").takeIf { it.isNotBlank() } ?: "Пользователь"
            val display = metadata.optString("display_name").ifBlank { emailName }
            call("/rest/v1/profiles", "POST",
                JSONObject().put("id", uid).put("display_name", display).put("avatar_url", "🙂").toString(),
                prefer = "return=minimal")
            return UserProfile(uid, display, "", "🙂")
        }
        val p = raw.getJSONObject(0)
        return UserProfile(p.optString("id"), p.optString("display_name"), p.optString("username"), p.optString("avatar_url"))
    }

    suspend fun updateMyProfile(displayName: String, avatar: String) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val payload = JSONObject().put("display_name", displayName.trim()).put("avatar_url", avatar)
        call("/rest/v1/profiles?id=eq.$uid", "PATCH", payload.toString(), prefer = "return=minimal")
    }

    suspend fun findUsers(query: String): List<Pair<String,String>> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val raw = JSONArray(call("/rest/v1/profiles?select=id,display_name,username&or=(display_name.ilike.%25$encoded%25,username.ilike.%25$encoded%25)&limit=20"))
        return (0 until raw.length()).map { i ->
            val p = raw.getJSONObject(i)
            p.optString("id") to (p.optString("display_name").ifBlank { p.optString("username") })
        }.filter { it.first != store.userId }
    }
    private fun normalizeUuid(value: String): String {
        val raw = value.trim()
        val decoded = try {
            JSONTokener(raw).nextValue() as? String
        } catch (_: Exception) {
            null
        }
        val uuid = (decoded ?: raw.trim('"')).trim()
        if (!uuid.matches(Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
            throw IOException("Сервер вернул некорректный ID чата.")
        }
        return uuid
    }

    suspend fun createGroup(title: String, memberIds: List<String>): String {
        val ids = JSONArray()
        memberIds.distinct().forEach { ids.put(normalizeUuid(it)) }
        val payload = JSONObject().put("p_title", title.trim()).put("p_member_ids", ids)
        return normalizeUuid(call("/rest/v1/rpc/create_group_conversation", "POST", payload.toString()))
    }

    suspend fun createDirect(userId: String): String {
        val result = call("/rest/v1/rpc/create_direct_conversation", "POST",
            JSONObject().put("p_other_user_id", normalizeUuid(userId)).toString())
        return normalizeUuid(result)
    }
}
