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
import java.util.concurrent.ConcurrentHashMap

data class ChatItem(val id: String, val title: String, val kind: String, val updated: String?, val unreadCount: Int = 0, val avatar: String = "🙂")
data class UserProfile(val id: String, val displayName: String, val username: String, val avatar: String)
data class UserItem(val id: String, val displayName: String, val avatar: String)
data class ContactMatch(val phone: String, val userId: String, val displayName: String, val avatar: String)
data class MessageItem(val id: String, val senderId: String, val content: String, val createdAt: String, val messageType: String = "text", val replyToId: String? = null, val editedAt: String? = null, val deliveryStatus: String = "sent")

class NexuraApi(private val store: SessionStore) {
    companion object {
        const val BASE_URL = "https://lyhzawblxcvpgpjrkspc.supabase.co"
        const val PUBLIC_KEY = "sb_publishable_G-EJKck-KO2cgoxaMlg9ag_CQZvDUEY"
        const val AUTH_REDIRECT = "nexura://auth/callback"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val signedUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()

    private suspend fun call(path: String, method: String = "GET", body: String? = null, auth: Boolean = true, prefer: String? = null): String = withContext(Dispatchers.IO) {
        val requestBody = body?.toRequestBody(JSON)
        fun buildRequest(token: String?): Request {
            val builder = Request.Builder().url("$BASE_URL$path")
                .header("apikey", PUBLIC_KEY)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer ${token ?: PUBLIC_KEY}")
            if (prefer != null) builder.header("Prefer", prefer)
            when (method) {
                "POST" -> builder.post(requestBody ?: "".toRequestBody(JSON))
                "PATCH" -> builder.patch(requestBody ?: "".toRequestBody(JSON))
                "DELETE" -> builder.delete(requestBody)
                else -> builder.get()
            }
            return builder.build()
        }

        var response = client.newCall(buildRequest(if (auth) store.accessToken else null)).execute()
        // Refresh an expired access token once, then retry the original request.
        if (response.code == 401 && auth && !store.refreshToken.isNullOrBlank()) {
            response.close()
            refreshSession()
            val freshToken = store.accessToken
                ?: throw IOException("Сессия истекла. Войдите в Nexura ещё раз.")
            response = client.newCall(buildRequest(freshToken)).execute()
        }
        response.use {
            val responseText = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val message = try {
                    JSONObject(responseText).optString("message").ifBlank { responseText }
                } catch (_: Exception) {
                    responseText
                }
                throw IOException(message.ifBlank { "HTTP ${it.code}" })
            }
            responseText
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
        val raw = JSONArray(call("/rest/v1/conversation_members?select=conversation_id,last_read_at,conversations(id,kind,title,last_message_at)&user_id=eq.$uid&order=joined_at.desc"))
        val base = (0 until raw.length()).mapNotNull { i ->
            val membership = raw.optJSONObject(i) ?: return@mapNotNull null
            val c = membership.optJSONObject("conversations") ?: return@mapNotNull null
            val id = c.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val kind = c.optString("kind").takeIf { it != "null" } ?: "direct"
            val lastRead = membership.optString("last_read_at").takeIf { it.isNotBlank() && it != "null" } ?: "1970-01-01T00:00:00Z"
            val unread = try {
                val encoded = java.net.URLEncoder.encode(lastRead, "UTF-8")
                JSONArray(call("/rest/v1/messages?select=id&conversation_id=eq.$id&sender_id=neq.$uid&created_at=gt.$encoded&deleted_at=is.null&limit=100")).length()
            } catch (_: Exception) { 0 }
            val title = c.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: if (kind == "direct") "Пользователь" else "Группа"
            ChatItem(id, title, kind, c.optString("last_message_at").takeIf { it.isNotBlank() && it != "null" }, unread)
        }
        val directIds = base.filter { it.kind == "direct" }.map { it.id }
        if (directIds.isEmpty()) return base
        val membershipRows = JSONArray(call("/rest/v1/conversation_members?select=conversation_id,user_id&conversation_id=in.(${directIds.joinToString(",")})&user_id=neq.$uid"))
        val peerByChat = mutableMapOf<String, String>()
        val peerIds = mutableSetOf<String>()
        for (i in 0 until membershipRows.length()) {
            val row = membershipRows.optJSONObject(i) ?: continue
            val chatId = row.optString("conversation_id")
            val peerId = row.optString("user_id")
            if (chatId.isNotBlank() && peerId.isNotBlank() && peerId != "null") { peerByChat[chatId] = peerId; peerIds.add(peerId) }
        }
        if (peerIds.isEmpty()) return base
        val profileRows = JSONArray(call("/rest/v1/profiles?select=id,display_name,username,avatar_url&id=in.(${peerIds.joinToString(",")})"))
        val profiles = mutableMapOf<String, Pair<String, String>>()
        for (i in 0 until profileRows.length()) {
            val p = profileRows.optJSONObject(i) ?: continue
            val id = p.optString("id")
            val name = p.optString("display_name").takeIf { it.isNotBlank() && it != "null" } ?: p.optString("username").takeIf { it.isNotBlank() && it != "null" } ?: "Пользователь"
            val avatar = p.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" } ?: "🙂"
            profiles[id] = name to avatar
        }
        return base.map { chat ->
            if (chat.kind != "direct") chat else {
                val profile = profiles[peerByChat[chat.id]]
                chat.copy(title = profile?.first ?: "Пользователь", avatar = profile?.second ?: "🙂")
            }
        }
    }
    suspend fun messages(chatId: String): List<MessageItem> {
        val raw = JSONArray(call("/rest/v1/messages?select=id,sender_id,content,created_at,message_type,reply_to_id,edited_at&conversation_id=eq.$chatId&deleted_at=is.null&order=created_at.asc&limit=150"))
        return (0 until raw.length()).map { i ->
            val m = raw.getJSONObject(i)
            val type = m.optString("message_type", "text")
            val content = m.optString("content")
            val rendered = if (type == "image" && content.isNotBlank()) signedAttachmentUrl(content) else content
            MessageItem(m.getString("id"), m.optString("sender_id"), rendered, m.optString("created_at"), type,
                m.optString("reply_to_id").takeIf { it.isNotBlank() && it != "null" },
                m.optString("edited_at").takeIf { it.isNotBlank() && it != "null" })
        }
    }
    suspend fun registerPushToken(token: String) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val payload = JSONObject().put("user_id", uid).put("token", token).put("platform", "android").put("updated_at", java.time.Instant.now().toString())
        call("/rest/v1/device_tokens?on_conflict=token", "POST", payload.toString(), prefer = "resolution=merge-duplicates,return=minimal")
    }

    private suspend fun notifyChatPush(chatId: String, preview: String) {
        try {
            call("/functions/v1/send-chat-push", "POST", JSONObject().put("conversation_id", normalizeUuid(chatId)).put("preview", preview.take(180)).toString())
        } catch (_: Exception) { }
    }

    suspend fun sendMessage(chatId: String, text: String, replyToId: String? = null) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val safeChatId = normalizeUuid(chatId)
        val content = text.trim()
        if (content.isEmpty()) throw IOException("Введите текст сообщения")
        if (content.length > 20000) throw IOException("Сообщение слишком длинное. Максимум — 20 000 символов.")
        val payload = JSONObject()
            .put("conversation_id", safeChatId)
            .put("sender_id", normalizeUuid(uid))
            .put("message_type", "text")
            .put("content", content)
        if (!replyToId.isNullOrBlank()) payload.put("reply_to_id", normalizeUuid(replyToId))
        call("/rest/v1/messages", "POST", payload.toString(), prefer = "return=minimal")
    }

    suspend fun sendPushForMessage(chatId: String, preview: String) { notifyChatPush(chatId, preview) }

    suspend fun markChatRead(chatId: String) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val safeChatId = normalizeUuid(chatId)
        val safeUserId = normalizeUuid(uid)
        call("/rest/v1/conversation_members?conversation_id=eq.$safeChatId&user_id=eq.$safeUserId", "PATCH",
            JSONObject().put("last_read_at", java.time.Instant.now().toString()).toString(),
            prefer = "return=minimal")
    }

    suspend fun editMessage(messageId: String, newText: String) {
        if (newText.isBlank()) throw IOException("Сообщение не может быть пустым")
        call("/rest/v1/messages?id=eq.${normalizeUuid(messageId)}&message_type=eq.text", "PATCH",
            JSONObject().put("content", newText.trim()).put("edited_at", java.time.Instant.now().toString()).toString(),
            prefer = "return=minimal")
    }

    suspend fun deleteMessage(messageId: String) {
        call("/rest/v1/messages?id=eq.${normalizeUuid(messageId)}", "PATCH",
            JSONObject().put("deleted_at", java.time.Instant.now().toString()).toString(),
            prefer = "return=minimal")
    }
    private suspend fun signedAttachmentUrl(path: String): String {
        val now = System.currentTimeMillis()
        signedUrlCache[path]?.let { cached ->
            if (cached.second > now) return cached.first
            signedUrlCache.remove(path, cached)
        }
        val payload = JSONObject().put("expiresIn", 3600)
        val result = JSONObject(call("/storage/v1/object/sign/chat-attachments/$path", "POST", payload.toString()))
        val signed = result.optString("signedURL").ifBlank { result.optString("signedUrl") }
        if (signed.isBlank()) throw IOException("Не удалось получить ссылку на изображение")
        val url = if (signed.startsWith("http")) signed else "$BASE_URL/storage/v1$signed"
        if (signedUrlCache.size > 500) signedUrlCache.clear()
        signedUrlCache[path] = url to (now + 55 * 60 * 1000L)
        return url
    }

    suspend fun sendImageMessage(chatId: String, bytes: ByteArray, mimeType: String) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        if (mimeType !in listOf("image/jpeg", "image/png", "image/webp", "image/gif")) {
            throw IOException("Поддерживаются JPG, PNG, WEBP и GIF")
        }
        if (bytes.size > 20 * 1024 * 1024) throw IOException("Максимальный размер изображения — 20 МБ")
        val extension = when (mimeType) { "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "jpg" }
        val safeChatId = normalizeUuid(chatId)
        val path = "$safeChatId/${UUID.randomUUID()}.$extension"
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
            JSONObject().put("conversation_id", safeChatId).put("sender_id", normalizeUuid(uid)).put("message_type", "image").put("content", path).toString(),
            prefer = "return=minimal")
        notifyChatPush(safeChatId, "📷 Фото")
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

    suspend fun matchContacts(phoneNumbers: List<String>): List<ContactMatch> {
        if (phoneNumbers.isEmpty()) return emptyList()
        val numbers = JSONArray()
        phoneNumbers.distinct().take(500).forEach { numbers.put(it) }
        val raw = JSONArray(call("/rest/v1/rpc/match_contact_users", "POST",
            JSONObject().put("p_phone_numbers", numbers).toString()))
        return (0 until raw.length()).mapNotNull { i ->
            val row = raw.optJSONObject(i) ?: return@mapNotNull null
            val phone = row.optString("phone").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val id = row.optString("user_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ContactMatch(phone, id,
                row.optString("display_name").ifBlank { "Пользователь" },
                row.optString("avatar_url").ifBlank { "🙂" })
        }
    }

    suspend fun findUsers(query: String): List<UserItem> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val raw = JSONArray(call("/rest/v1/profiles?select=id,display_name,username,avatar_url&or=(display_name.ilike.%25$encoded%25,username.ilike.%25$encoded%25)&limit=20"))
        return (0 until raw.length()).mapNotNull { i ->
            val p = raw.optJSONObject(i) ?: return@mapNotNull null
            val id = p.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val name = p.optString("display_name").takeIf { it.isNotBlank() && it != "null" } ?: p.optString("username").takeIf { it.isNotBlank() && it != "null" } ?: "Пользователь"
            val avatar = p.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" } ?: "🙂"
            UserItem(id, name, avatar)
        }.filter { it.id != store.userId }
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
