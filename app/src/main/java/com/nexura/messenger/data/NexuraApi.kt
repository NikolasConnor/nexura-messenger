package com.nexura.messenger.data

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ChatItem(val id: String, val title: String, val kind: String, val updated: String?, val unreadCount: Int = 0, val avatar: String = "🙂", val peerUserId: String? = null, val chatMode: String = "secret")
data class MessageReaction(val emoji: String, val count: Int, val selectedByMe: Boolean)
data class UserProfile(val id: String, val displayName: String, val username: String, val avatar: String, val bio: String = "", val age: Int? = null)
data class UserItem(val id: String, val displayName: String, val avatar: String)
data class PrivacySettings(val showOnlineStatus: Boolean = true, val readReceiptsEnabled: Boolean = true)
data class UserPresence(val isVisible: Boolean, val isOnline: Boolean, val lastSeenAt: String?)
data class ContactMatch(val phone: String, val userId: String, val displayName: String, val avatar: String)
data class MessageItem(
    val id: String,
    val senderId: String,
    val content: String,
    val createdAt: String,
    val messageType: String = "text",
    val replyToId: String? = null,
    val editedAt: String? = null,
    val encrypted: Boolean = false,
    val securityWarning: Boolean = false,
    val mediaKind: String? = null,
    val mediaCaption: String? = null,
    val mediaFileName: String? = null,
    val mediaMimeType: String? = null
)
class E2eePreKeyUnavailableException(message: String) : IOException(message)
class NexuraHttpException(val statusCode: Int, message: String) : IOException(message)

data class E2eeRemoteDevice(
    val userId: String,
    val deviceId: String,
    val curve25519Key: String,
    val ed25519Key: String,
    val protocol: String,
    val bundleVersion: Int
)

data class TurnIceServerConfig(
    val urls: List<String>,
    val username: String?,
    val credential: String?
)

class NexuraApi(private val store: SessionStore) {
    companion object {
        const val BASE_URL = "https://lyhzawblxcvpgpjrkspc.supabase.co"
        const val PUBLIC_KEY = "sb_publishable_G-EJKck-KO2cgoxaMlg9ag_CQZvDUEY"
        const val AUTH_REDIRECT = "nexura://auth/callback"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val signedUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val remoteDeviceCache = ConcurrentHashMap<String, Pair<E2eeRemoteDevice, Long>>()
    private val groupMemberCache = ConcurrentHashMap<String, Pair<List<String>, Long>>()
    // Supabase rotates refresh tokens. Serialize refreshes so simultaneous 401 responses
    // cannot race and invalidate the token another request has just stored.
    private val refreshMutex = Mutex()

    private suspend fun executeWithTransientRetry(
        request: Request,
        allowRetry: Boolean
    ): okhttp3.Response {
        var retries = 0
        while (true) {
            try {
                return client.newCall(request).execute()
            } catch (e: IOException) {
                val transientSocketFailure =
                    e is java.net.SocketException ||
                    e is java.net.SocketTimeoutException ||
                    e is java.net.UnknownHostException
                if (!allowRetry || !transientSocketFailure || retries >= 1) throw e
                retries++
                kotlinx.coroutines.delay(900L)
            }
        }
    }

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

        val refreshTokenAtRequestStart = if (auth) store.refreshToken else null
        val initialRequest = buildRequest(if (auth) store.accessToken else null)
        // Retry only safe reads and password sign-in. Never blindly replay message,
        // upload, refresh-token, or other state-changing requests after a broken socket.
        val safeToRetry = method == "GET" || path == "/auth/v1/token?grant_type=password"
        var response = executeWithTransientRetry(initialRequest, safeToRetry)
        // Refresh an expired access token once, then retry the original request.
        // If another request already rotated the token, reuse its fresh access token.
        if (response.code == 401 && auth) {
            response.close()
            refreshAfterUnauthorized(refreshTokenAtRequestStart)
            val freshToken = store.accessToken
                ?: throw IOException("Сессия истекла. Войдите в Nexura ещё раз.")
            response = client.newCall(buildRequest(freshToken)).execute()
        }
        response.use {
            val responseText = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val message = try {
                    val payload = JSONObject(responseText)
                    payload.optString("message")
                        .ifBlank { payload.optString("error") }
                        .ifBlank { responseText }
                } catch (_: Exception) {
                    responseText
                }
                throw NexuraHttpException(it.code, message.ifBlank { "HTTP ${it.code}" })
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
        val complete = withContext(Dispatchers.IO) {
            !store.accessToken.isNullOrBlank() && !store.userId.isNullOrBlank()
        }
        if (!complete) {
            throw IOException("Код принят, но Supabase не вернул полноценную сессию. Попробуйте войти ещё раз.")
        }
    }

    suspend fun signIn(email: String, password: String) {
        val result = JSONObject(call("/auth/v1/token?grant_type=password", "POST",
            JSONObject().put("email", email).put("password", password).toString(), auth = false))
        saveSession(result)
        val complete = withContext(Dispatchers.IO) {
            !store.accessToken.isNullOrBlank() &&
                !store.refreshToken.isNullOrBlank() &&
                !store.userId.isNullOrBlank()
        }
        if (!complete) {
            withContext(Dispatchers.IO) { store.clear() }
            throw IOException("Supabase подтвердил вход, но не вернул полную сессию. Повторите вход.")
        }
    }
    suspend fun signUp(email: String, password: String, displayName: String) {
        val redirect = java.net.URLEncoder.encode(AUTH_REDIRECT, "UTF-8")
        val result = JSONObject(call("/auth/v1/signup?redirect_to=$redirect", "POST",
            JSONObject().put("email", email).put("password", password)
                .put("data", JSONObject().put("display_name", displayName)).toString(), auth = false))
        if (result.has("access_token")) saveSession(result)
        else throw IOException("Аккаунт создан. Подтвердите email по ссылке из письма, затем вернитесь в Nexura.")
    }
    // EncryptedSharedPreferences performs encryption/decryption synchronously.
    // Never write session tokens on the Android main thread after a network response.
    private suspend fun saveSession(o: JSONObject) = withContext(Dispatchers.IO) {
        store.accessToken = o.optString("access_token").takeIf { it.isNotBlank() }
        store.refreshToken = o.optString("refresh_token").takeIf { it.isNotBlank() }
        store.userId = o.optJSONObject("user")?.optString("id")
            ?: o.optJSONObject("session")?.optJSONObject("user")?.optString("id")
    }
    suspend fun refreshSession() {
        refreshMutex.withLock {
            val refresh = store.refreshToken ?: return@withLock
            refreshSessionLocked(refresh)
        }
    }

    private suspend fun refreshAfterUnauthorized(refreshTokenAtRequestStart: String?) {
        refreshMutex.withLock {
            val currentRefreshToken = store.refreshToken
                ?: throw IOException("Сессия истекла. Войдите в Nexura ещё раз.")
            // A parallel request may have refreshed the session while this request was in flight.
            if (!refreshTokenAtRequestStart.isNullOrBlank() &&
                currentRefreshToken != refreshTokenAtRequestStart &&
                !store.accessToken.isNullOrBlank()) return@withLock
            refreshSessionLocked(currentRefreshToken)
        }
    }

    private suspend fun refreshSessionLocked(refresh: String) {
        try {
            val o = JSONObject(call("/auth/v1/token?grant_type=refresh_token", "POST",
                JSONObject().put("refresh_token", refresh).toString(), auth = false))
            saveSession(o)
            if (store.accessToken.isNullOrBlank() || store.refreshToken.isNullOrBlank()) {
                throw IOException("Сервер не вернул новую сессию. Войдите в Nexura ещё раз.")
            }
        } catch (e: Exception) {
            val message = (e.message ?: "").lowercase()
            if (message.contains("refresh token") &&
                (message.contains("invalid") || message.contains("not found") ||
                 message.contains("already used") || message.contains("expired"))) {
                store.clear()
            }
            throw e
        }
    }

    /** Restores and validates the persisted Supabase session before loading app data. */
    suspend fun restoreSession() {
        if (store.accessToken.isNullOrBlank()) refreshSession()
        if (store.accessToken.isNullOrBlank()) {
            throw IOException("Сессия не найдена. Войдите в Nexura.")
        }
        val user = JSONObject(call("/auth/v1/user", auth = true))
        val userId = user.optString("id").takeIf { it.isNotBlank() && it != "null" }
            ?: throw IOException("Не удалось восстановить профиль аккаунта. Войдите в Nexura ещё раз.")
        store.userId = userId
    }
    suspend fun resendConfirmation(email: String) {
        val redirect = java.net.URLEncoder.encode(AUTH_REDIRECT, "UTF-8")
        call("/auth/v1/resend?redirect_to=$redirect", "POST",
            JSONObject().put("type", "signup").put("email", email.trim()).toString(),
            auth = false)
    }
    /** Revokes every refresh-token session except the one currently used by this device. */
    suspend fun signOutOtherSessions() {
        call("/auth/v1/logout?scope=others", "POST", "{}", auth = true)
    }

    data class LiveKitRoomCredentials(
        val token: String,
        val serverUrl: String,
        val roomName: String,
        val identity: String,
        val expiresInSeconds: Int
    )

    /** Requests a short-lived, conversation-scoped LiveKit participant token. */
    suspend fun liveKitRoomCredentials(conversationId: String, peerUserId: String): LiveKitRoomCredentials {
        val payload = JSONObject()
            .put("conversation_id", conversationId)
            .put("peer_user_id", peerUserId)
        val response = JSONObject(call("/functions/v1/livekit-token", "POST", payload.toString(), auth = true))
        val token = response.optString("token")
        val serverUrl = response.optString("server_url")
        val roomName = response.optString("room_name")
        val identity = response.optString("identity")
        if (token.isBlank() || !serverUrl.startsWith("wss://") || roomName.isBlank() || identity.isBlank()) {
            throw IOException(response.optString("error").ifBlank { "LiveKit не вернул данные для подключения" })
        }
        return LiveKitRoomCredentials(token, serverUrl, roomName, identity, response.optInt("expires_in", 600))
    }

    /** Fetches TURN relay credentials from our authenticated Supabase Edge Function. */
    suspend fun turnIceServers(callId: String, initiatorId: String, receiverId: String): List<TurnIceServerConfig> {
        val payload = JSONObject()
            .put("room", callId)
            .put("initiator_client", initiatorId)
            .put("receiver_client", receiverId)
        val response = JSONObject(call("/functions/v1/turn-credentials", "POST", payload.toString(), auth = true))
        val servers = response.optJSONArray("iceServers")
            ?: throw IOException(response.optString("error").ifBlank { "TURN-сервер не вернул ICE-конфигурацию" })
        val result = mutableListOf<TurnIceServerConfig>()
        for (i in 0 until servers.length()) {
            val item = servers.optJSONObject(i) ?: continue
            val urls = when (val raw = item.opt("urls")) {
                is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).takeIf(String::isNotBlank) }
                is String -> listOf(raw).filter(String::isNotBlank)
                else -> emptyList()
            }
            if (urls.isNotEmpty()) {
                result += TurnIceServerConfig(
                    urls = urls,
                    username = item.optString("username").takeIf { it.isNotBlank() && it != "null" },
                    credential = item.optString("credential").takeIf { it.isNotBlank() && it != "null" }
                )
            }
        }
        if (result.none { server -> server.urls.any { it.startsWith("turn:") || it.startsWith("turns:") } }) {
            throw IOException("TURN-сервер не выдал relay-адреса. Проверьте настройки Turnix.")
        }
        return result
    }

    suspend fun signOut() {
        try { call("/auth/v1/logout", "POST", "{}", auth = true) } finally {
            store.mediaCacheDir.listFiles()?.forEach { it.delete() }
            store.clear()
        }
    }
    suspend fun updateConversationChatMode(conversationId: String, mode: String) {
        if (mode != "cloud" && mode != "secret") throw IOException("Неизвестный режим чата")
        call(
            "/rest/v1/conversations?id=eq." + normalizeUuid(conversationId),
            "PATCH",
            JSONObject().put("chat_mode", mode).toString(),
            prefer = "return=minimal"
        )
    }

    suspend fun chats(): List<ChatItem> {
        val uid = store.userId ?: return emptyList()
        // Fetch the conversation list, peer profiles and unread counters in two
        // requests instead of four serial round trips. PostgREST embeds the peer
        // membership and profile through the declared foreign keys.
        val (membershipJson, unreadJson) = coroutineScope {
            val memberships = async {
                call(
                    "/rest/v1/conversation_members?select=conversation_id," +
                        "conversations(id,kind,title,last_message_at,chat_mode,conversation_members(user_id,profiles(id,display_name,username,avatar_url)))" +
                        "&user_id=eq.$uid&order=joined_at.desc" +
                        "&conversations.conversation_members.user_id=neq.$uid"
                )
            }
            val unread = async { call("/rest/v1/rpc/get_my_unread_counts", "POST", "{}") }
            memberships.await() to unread.await()
        }

        val unreadRows = JSONArray(unreadJson)
        val unreadByChat = mutableMapOf<String, Int>()
        for (i in 0 until unreadRows.length()) {
            val row = unreadRows.optJSONObject(i) ?: continue
            val id = row.optString("conversation_id")
            if (id.isNotBlank() && id != "null") unreadByChat[id] = row.optInt("unread_count")
        }

        val raw = JSONArray(membershipJson)
        return (0 until raw.length()).mapNotNull { i ->
            val membership = raw.optJSONObject(i) ?: return@mapNotNull null
            val conversation = membership.optJSONObject("conversations") ?: return@mapNotNull null
            val id = conversation.optString("id").takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            val kind = conversation.optString("kind").takeIf { it != "null" } ?: "direct"
            val title = conversation.optString("title").takeIf { it.isNotBlank() && it != "null" }
                ?: if (kind == "direct") "Пользователь" else "Группа"

            var peerId: String? = null
            var avatar = "🙂"
            val displayTitle = if (kind == "direct") {
                val peers = conversation.optJSONArray("conversation_members")
                val peer = peers?.optJSONObject(0)
                peerId = peer?.optString("user_id")?.takeIf { it.isNotBlank() && it != "null" }
                val profile = peer?.optJSONObject("profiles")
                avatar = profile?.optString("avatar_url")
                    ?.takeIf { it.isNotBlank() && it != "null" } ?: "🙂"
                profile?.optString("display_name")
                    ?.takeIf { it.isNotBlank() && it != "null" }
                    ?: profile?.optString("username")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: title
            } else title

            ChatItem(
                id = id,
                title = displayTitle,
                kind = kind,
                updated = conversation.optString("last_message_at").takeIf { it.isNotBlank() && it != "null" },
                unreadCount = unreadByChat[id] ?: 0,
                avatar = avatar,
                peerUserId = peerId,
                chatMode = conversation.optString("chat_mode").takeIf { it == "cloud" || it == "secret" } ?: "secret"
            )
        }
    }
    private suspend fun encryptCloudHistory(conversationId: String, messageId: String, plaintext: String): String {
        val response = JSONObject(call(
            "/functions/v1/cloud-history",
            "POST",
            JSONObject()
                .put("action", "encrypt")
                .put("conversation_id", normalizeUuid(conversationId))
                .put("message_id", normalizeUuid(messageId))
                .put("plaintext", plaintext)
                .toString()
        ))
        return response.optString("cloud_content").takeIf { it.isNotBlank() }
            ?: throw IOException("Сервер не вернул зашифрованную облачную копию сообщения")
    }

    private suspend fun decryptCloudHistory(conversationId: String, rows: JSONArray): Map<String, String> {
        val items = JSONArray()
        val missingSharedIds = mutableListOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val cloudContent = row.optString("cloud_content")
            if (cloudContent.isNotBlank() && cloudContent != "null") {
                items.put(JSONObject().put("id", row.getString("id"))
                    .put("cloud_content", cloudContent).put("scope", "shared"))
            } else {
                missingSharedIds += row.getString("id")
            }
        }
        // Older Olm messages are archived per account by the device that can
        // still decrypt them. This lets a clean install restore that user's copy.
        val uid = store.userId ?: throw IOException("Сессия истекла")
        if (missingSharedIds.isNotEmpty()) {
            val ids = missingSharedIds.joinToString(",") { normalizeUuid(it) }
            val archives = JSONArray(call(
                "/rest/v1/message_cloud_archives?select=message_id,cloud_content" +
                    "&user_id=eq.$uid&conversation_id=eq.${normalizeUuid(conversationId)}&message_id=in.($ids)"
            ))
            for (index in 0 until archives.length()) {
                val archive = archives.getJSONObject(index)
                items.put(JSONObject()
                    .put("id", archive.getString("message_id"))
                    .put("cloud_content", archive.getString("cloud_content"))
                    .put("scope", "user"))
            }
        }
        if (items.length() == 0) return emptyMap()
        val response = JSONObject(call(
            "/functions/v1/cloud-history",
            "POST",
            JSONObject()
                .put("action", "decrypt")
                .put("conversation_id", normalizeUuid(conversationId))
                .put("items", items)
                .toString()
        ))
        val decrypted = response.optJSONArray("items") ?: return emptyMap()
        return buildMap {
            for (index in 0 until decrypted.length()) {
                val item = decrypted.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val plaintext = item.optString("plaintext")
                if (id.isNotBlank() && plaintext.isNotBlank() && plaintext != "null") put(id, plaintext)
            }
        }
    }

    suspend fun messages(
        chatId: String,
        e2ee: E2eeClient? = null,
        afterCreatedAt: String? = null,
        mediaOnly: Boolean = false,
        beforeCreatedAt: String? = null,
        beforeMessageId: String? = null,
        afterMessageId: String? = null,
        pageSize: Int = 30
    ): List<MessageItem> {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val afterTimestamp = afterCreatedAt?.let { java.net.URLEncoder.encode(it, "UTF-8") }
        val beforeTimestamp = beforeCreatedAt?.let { java.net.URLEncoder.encode(it, "UTF-8") }
        val afterFilter = when {
            afterTimestamp != null && !afterMessageId.isNullOrBlank() ->
                "&or=(created_at.gt.$afterTimestamp,and(created_at.eq.$afterTimestamp,id.gt.${normalizeUuid(afterMessageId)}))"
            afterTimestamp != null -> "&created_at=gte.$afterTimestamp"
            else -> ""
        }
        val beforeFilter = when {
            beforeTimestamp != null && !beforeMessageId.isNullOrBlank() ->
                "&or=(created_at.lt.$beforeTimestamp,and(created_at.eq.$beforeTimestamp,id.lt.${normalizeUuid(beforeMessageId)}))"
            beforeTimestamp != null -> "&created_at=lte.$beforeTimestamp"
            else -> ""
        }
        val limit = if (mediaOnly) 1000 else if (afterCreatedAt != null) 100 else pageSize.coerceIn(1, 100)
        val mediaFilter = if (mediaOnly) "&message_type=in.(media,image,file)" else ""
        val raw = JSONArray(call(
            "/rest/v1/messages?select=id,sender_id,content,cloud_content,created_at,message_type,reply_to_id,edited_at" +
                "&conversation_id=eq.$chatId&deleted_at=is.null&order=created_at.desc,id.desc&limit=$limit$afterFilter$beforeFilter$mediaFilter"
        ))
        // Decrypt the server-managed archive in one bounded request. Rows from
        // before cloud-history rollout keep their original Olm-only fallback.
        val cloudPlaintextById = runCatching { decryptCloudHistory(chatId, raw) }
            .getOrDefault(emptyMap())
        // Fetch newest-first for cursor pages, then decrypt chronologically within each page.
        val chronologicalIndexes = (0 until raw.length()).sortedWith(
            compareBy<Int> { raw.getJSONObject(it).optString("created_at") }
                .thenBy { raw.getJSONObject(it).optString("id") }
        )
        val archiveCandidates = JSONArray()
        val rendered = withContext(Dispatchers.Default) {
            chronologicalIndexes.map { i ->
            val m = raw.getJSONObject(i)
            val id = m.getString("id")
            val senderId = m.optString("sender_id")
            val type = m.optString("message_type", "text")
            val content = m.optString("content")
            val replyTo = m.optString("reply_to_id").takeIf { it.isNotBlank() && it != "null" }
            val edited = m.optString("edited_at").takeIf { it.isNotBlank() && it != "null" }

            if (type in setOf("text", "media", "call") &&
                (E2eeMessageEnvelope.isEnvelope(content) || E2eeGroupMessageEnvelope.isEnvelope(content) ||
                    content == "NEXURA_CLOUD_V1" || cloudPlaintextById.containsKey(id))) {
                val cloudDecryptFailed = content == "NEXURA_CLOUD_V1" && !cloudPlaintextById.containsKey(id)
                val decrypted = if (cloudDecryptFailed) null else cloudPlaintextById[id] ?: if (E2eeGroupMessageEnvelope.isEnvelope(content)) {
                    runCatching {
                        val client = e2ee ?: throw IOException("E2EE недоступно на этом устройстве")
                        decryptGroupEnvelope(content, chatId, id, senderId, client)
                    }.getOrNull()
                } else try {

                    val client = e2ee ?: throw IOException("E2EE недоступно на этом устройстве")
                    val envelope = E2eeMessageEnvelope.decode(content)
                    if (envelope.conversationId != normalizeUuid(chatId)) throw IOException("Конверт относится к другому чату")
                    if (senderId == uid) {
                        if (envelope.senderDeviceId != client.deviceId()) throw IOException("Сообщение отправлено с другого устройства")
                        client.cachedMessage(chatId, id)?.toString(Charsets.UTF_8)
                            ?: throw IOException("Локальная копия сообщения недоступна")
                    } else {
                        if (envelope.recipientDeviceId != client.deviceId()) throw IOException("Сообщение адресовано другому устройству")
                        val deviceCacheKey = "$senderId:${envelope.senderDeviceId}"
                        val now = System.currentTimeMillis()
                        val cachedDevice = remoteDeviceCache[deviceCacheKey]
                        val device = if (cachedDevice != null && cachedDevice.second > now) {
                            cachedDevice.first
                        } else {
                            val deviceRows = JSONArray(call(
                                "/rest/v1/e2ee_devices?select=device_id,curve25519_key,ed25519_key,protocol,bundle_version" +
                                    "&user_id=eq.$senderId&device_id=eq.${envelope.senderDeviceId}&is_active=eq.true&limit=1"
                            ))
                            if (deviceRows.length() != 1) throw IOException("Устройство отправителя не зарегистрировано")
                            val row = deviceRows.getJSONObject(0)
                            E2eeRemoteDevice(
                                senderId, envelope.senderDeviceId, row.optString("curve25519_key"),
                                row.optString("ed25519_key"), row.optString("protocol"), row.optInt("bundle_version")
                            ).also { remoteDeviceCache[deviceCacheKey] = it to (now + 60_000L) }
                        }
                        val curve = device.curve25519Key
                        val ed = device.ed25519Key
                        if (device.protocol != "olm-v1" || device.bundleVersion != 1) {
                            throw IOException("Версия протокола устройства не поддерживается")
                        }
                        if (curve != envelope.senderCurve25519) throw IOException("Идентичность отправителя не совпадает")
                        client.trustRemoteDeviceOnFirstUse(senderId, envelope.senderDeviceId, curve, ed)
                        val cached = client.cachedMessage(chatId, id)
                        val plaintext = cached ?: when (envelope.kind) {
                            E2eeMessageEnvelope.Kind.PRE_KEY -> client.acceptDirectSession(
                                chatId, envelope.senderCurve25519, envelope.ciphertext, id
                            )
                            E2eeMessageEnvelope.Kind.NORMAL -> client.decryptDirectMessage(
                                chatId, envelope.ciphertext, id
                            )
                        }
                        plaintext.toString(Charsets.UTF_8)
                    }

                } catch (error: Exception) {
                    null
                }
                val sharedArchive = m.optString("cloud_content")
                if (decrypted != null && (sharedArchive.isBlank() || sharedArchive == "null") &&
                    !cloudPlaintextById.containsKey(id)) {
                    archiveCandidates.put(JSONObject().put("id", id).put("plaintext", decrypted))
                }
                val mediaJson = if (type == "media" && decrypted != null) E2eeMediaCrypto.parsePayload(decrypted) else null
                val mediaUri = if (mediaJson != null) {
                    runCatching { downloadAndDecryptMedia(mediaJson, id, chatId, senderId) }.getOrNull()
                } else null
                MessageItem(
                    id, senderId,
                    when {
                        cloudDecryptFailed -> "⚠ Не удалось загрузить облачное сообщение. Проверьте соединение и обновите чат."
                        decrypted == null -> "🔒 Сообщение зашифровано. Проверьте устройство или восстановите защищённую сессию."
                        type == "media" && mediaUri == null -> "⚠ Не удалось проверить или открыть медиафайл"
                        type == "media" -> mediaUri!!
                        else -> decrypted
                    },
                    m.optString("created_at"), type, replyTo, edited,
                    encrypted = decrypted != null && (type != "media" || mediaUri != null),
                    securityWarning = decrypted == null || (type == "media" && mediaUri == null),
                    mediaKind = mediaJson?.optString("media_type"),
                    mediaCaption = mediaJson?.optString("caption")?.takeIf { it.isNotBlank() },
                    mediaFileName = mediaJson?.optString("name")?.takeIf { it.isNotBlank() },
                    mediaMimeType = mediaJson?.optString("mime")?.takeIf { it.isNotBlank() }
                )
            } else {
                val rendered = if (type in setOf("image", "file") && content.isNotBlank()) signedAttachmentUrl(content) else content
                MessageItem(
                    id, senderId, rendered, m.optString("created_at"), type, replyTo, edited,
                    encrypted = false,
                    securityWarning = type == "text" || type == "image" || type == "file"
                )
            }
            }.sortedWith(compareBy<MessageItem> { it.createdAt }.thenBy { it.id })
        }
        // Migrate only legacy messages this signed-in device successfully decrypted.
        // Archives are scoped to this account and encrypted before they reach Postgres.
        if (archiveCandidates.length() > 0) {
            runCatching {
                call(
                    "/functions/v1/cloud-history",
                    "POST",
                    JSONObject()
                        .put("action", "archive")
                        .put("conversation_id", normalizeUuid(chatId))
                        .put("items", archiveCandidates)
                        .toString()
                )
            }
        }
        // Backup synchronization is intentionally not part of message paging/polling.
        // It runs during device setup and explicit backup maintenance, not on every refresh.
        return rendered
    }
    /**
     * Publishes only public E2EE material. Idempotent inserts let the client
     * retry after network failures without replacing an established identity.
     */
    suspend fun publishE2eeDevice(e2ee: E2eeClient): Int {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val bundle = JSONObject(e2ee.ensureDevice())
        val deviceId = bundle.getString("device_id")
        val curveKey = bundle.getString("curve25519")
        val edKey = bundle.getString("ed25519")

        val devicePayload = JSONObject()
            .put("user_id", uid)
            .put("device_id", deviceId)
            .put("curve25519_key", curveKey)
            .put("ed25519_key", edKey)
            .put("protocol", bundle.optString("protocol", "olm-v1"))
            .put("bundle_version", bundle.optInt("bundle_version", 1))
            .put("is_active", true)
        call(
            "/rest/v1/e2ee_devices?on_conflict=user_id,device_id",
            "POST",
            devicePayload.toString(),
            prefer = "resolution=ignore-duplicates,return=minimal"
        )

        val existingDevice = JSONArray(call(
            "/rest/v1/e2ee_devices?select=curve25519_key,ed25519_key,protocol,bundle_version,is_active" +
                "&user_id=eq.$uid&device_id=eq.$deviceId&limit=1"
        ))
        if (existingDevice.length() != 1) throw IOException("Не удалось подтвердить регистрацию E2EE-устройства")
        val storedDevice = existingDevice.getJSONObject(0)
        if (storedDevice.optString("curve25519_key") != curveKey ||
            storedDevice.optString("ed25519_key") != edKey ||
            storedDevice.optString("protocol") != bundle.optString("protocol", "olm-v1") ||
            storedDevice.optInt("bundle_version") != bundle.optInt("bundle_version", 1) ||
            !storedDevice.optBoolean("is_active")) {
            throw IOException("Ключи E2EE-устройства не совпадают с сохранённой регистрацией")
        }

        suspend fun uploadPendingKeys() {
            val pending = JSONArray(e2ee.pendingOneTimeKeysJson())
            if (pending.length() == 0) return
            val keyIds = mutableListOf<String>()
            val rows = JSONArray()
            for (index in 0 until pending.length()) {
                val key = pending.getJSONObject(index)
                val keyId = key.getString("key_id")
                keyIds += keyId
                rows.put(
                    JSONObject()
                        .put("user_id", uid)
                        .put("device_id", deviceId)
                        .put("key_id", keyId)
                        .put("curve25519_key", key.getString("key"))
                        .put("signature", key.getString("signature"))
                )
            }

            call(
                "/rest/v1/e2ee_one_time_keys?on_conflict=user_id,device_id,key_id",
                "POST",
                rows.toString(),
                prefer = "resolution=ignore-duplicates,return=minimal"
            )

            val filter = keyIds.joinToString(",")
            val storedKeys = JSONArray(call(
                "/rest/v1/e2ee_one_time_keys?select=key_id,curve25519_key,signature" +
                    "&user_id=eq.$uid&device_id=eq.$deviceId&key_id=in.($filter)"
            ))
            val storedById = (0 until storedKeys.length()).associate { index ->
                val row = storedKeys.getJSONObject(index)
                row.getString("key_id") to row
            }
            for (index in 0 until pending.length()) {
                val expected = pending.getJSONObject(index)
                val actual = storedById[expected.getString("key_id")]
                    ?: throw IOException("Supabase не подтвердил сохранение одноразового E2EE-ключа")
                if (actual.optString("curve25519_key") != expected.optString("key") ||
                    actual.optString("signature") != expected.optString("signature")) {
                    throw IOException("Одноразовый E2EE-ключ на сервере не совпадает с устройством")
                }
            }
            e2ee.markPublicKeysPublished(keyIds)
        }

        uploadPendingKeys()

        var available = countAvailableE2eeKeys(uid, deviceId)
        // Keep a larger offline-delivery reserve. A recipient does not need
        // an active Auth session to receive: senders consume these published
        // public OTKs while the device is offline, then the device decrypts
        // queued ciphertext after its next login.
        if (available < 25) {
            e2ee.replenishOneTimeKeys(50)
            uploadPendingKeys()
            available = countAvailableE2eeKeys(uid, deviceId)
        }
        return available
    }

    private suspend fun countAvailableE2eeKeys(userId: String, deviceId: String): Int {
        val rows = JSONArray(call(
            "/rest/v1/e2ee_one_time_keys?select=key_id" +
                "&user_id=eq.$userId&device_id=eq.$deviceId&consumed_at=is.null&limit=100"
        ))
        return rows.length()
    }

    /** Keep own-device fan-out limited to devices that still have a server-side OTK. */
    private suspend fun ownDevicesWithAvailableKeys(
        userId: String,
        devices: List<E2eeRemoteDevice>
    ): List<E2eeRemoteDevice> = devices.filter { device ->
        countAvailableE2eeKeys(userId, device.deviceId) > 0
    }

    /**
     * Creates an Olm pre-key envelope only when the fetched device identity
     * matches a fingerprint the caller has already verified out of band.
     * This does not send or persist the envelope.
     */
    suspend fun createVerifiedE2eePreKeyEnvelope(
        e2ee: E2eeClient,
        conversationId: String,
        recipientUserId: String,
        recipientDeviceId: String,
        verifiedRecipientEd25519Key: String,
        firstPlaintext: ByteArray,
        messageId: String = UUID.randomUUID().toString()
    ): String {
        if (verifiedRecipientEd25519Key.isBlank()) {
            throw IOException("Сначала подтвердите цифровой отпечаток устройства собеседника")
        }
        val preKey = consumeE2eePreKey(conversationId, recipientUserId, recipientDeviceId)
        if (preKey.optString("device_id") != recipientDeviceId) {
            throw IOException("Сервер вернул ключ другого устройства")
        }
        val identityKey = preKey.optString("ed25519_key")
        if (identityKey != verifiedRecipientEd25519Key) {
            throw IOException("Цифровой отпечаток устройства изменился. Отправка остановлена")
        }
        val oneTimeKey = preKey.optString("one_time_key")
        val signature = preKey.optString("one_time_key_signature")
        if (oneTimeKey.isBlank() || signature.isBlank() ||
            !e2ee.verifyPublishedOneTimeKey(identityKey, preKey.optString("curve25519_key"), oneTimeKey, signature)) {
            throw IOException("Подпись одноразового E2EE-ключа недействительна")
        }
        return e2ee.startDirectSession(
            conversationId = normalizeUuid(conversationId),
            recipientCurve25519 = preKey.getString("curve25519_key"),
            recipientEd25519 = identityKey,
            recipientOneTimeKey = oneTimeKey,
            recipientOneTimeKeySignature = signature,
            firstPlaintext = firstPlaintext,
            messageId = messageId
        )
    }

    /** Atomically consumes a group member device OTK after verifying both users belong to the group. */
    suspend fun consumeGroupE2eePreKey(
        conversationId: String,
        recipientUserId: String,
        recipientDeviceId: String
    ): JSONObject {
        val rows = JSONArray(call(
            "/rest/v1/rpc/consume_group_e2ee_one_time_key",
            "POST",
            JSONObject()
                .put("p_conversation_id", normalizeUuid(conversationId))
                .put("p_recipient_user_id", normalizeUuid(recipientUserId))
                .put("p_device_id", normalizeUuid(recipientDeviceId))
                .toString()
        ))
        if (rows.length() != 1) throw IOException("Не удалось получить одноразовый групповой E2EE-ключ")
        return rows.getJSONObject(0)
    }

    /** Atomically consumes one recipient OTK only when both users share this chat. */
    suspend fun consumeE2eePreKey(
        conversationId: String,
        recipientUserId: String,
        recipientDeviceId: String
    ): JSONObject {
        val rows = JSONArray(call(
            "/rest/v1/rpc/consume_e2ee_one_time_key",
            "POST",
            JSONObject()
                .put("p_conversation_id", normalizeUuid(conversationId))
                .put("p_recipient_user_id", normalizeUuid(recipientUserId))
                .put("p_device_id", normalizeUuid(recipientDeviceId))
                .toString()
        ))
        if (rows.length() != 1) throw IOException("Не удалось получить одноразовый E2EE-ключ собеседника")
        return rows.getJSONObject(0)
    }

    /** Atomically consumes a pre-key only from another active device owned by the authenticated account. */
    suspend fun consumeOwnE2eePreKey(
        conversationId: String,
        deviceId: String
    ): JSONObject {
        val rows = JSONArray(call(
            "/rest/v1/rpc/consume_own_e2ee_one_time_key",
            "POST",
            JSONObject()
                .put("p_conversation_id", normalizeUuid(conversationId))
                .put("p_device_id", normalizeUuid(deviceId))
                .toString()
        ))
        if (rows.length() != 1) throw IOException("Не удалось получить одноразовый ключ собственного устройства")
        return rows.getJSONObject(0)
    }
    suspend fun groupMemberIds(conversationId: String): List<String> {
        // Security-sensitive send paths always fetch fresh membership before and after encryption.
        val chatId = normalizeUuid(conversationId)
        val rows = JSONArray(call(
            "/rest/v1/conversation_members?select=user_id&conversation_id=eq.$chatId&order=joined_at.asc"
        ))
        val ids = (0 until rows.length()).map { rows.getJSONObject(it).getString("user_id") }.distinct()
        if (ids.size !in 2..E2eeGroupMessageEnvelope.MAX_RECIPIENTS) {
            throw IOException("В группе должно быть от 2 до ${E2eeGroupMessageEnvelope.MAX_RECIPIENTS} участников")
        }
        return ids
    }

    private suspend fun cachedGroupMemberIds(conversationId: String): List<String> {
        val chatId = normalizeUuid(conversationId)
        val now = System.currentTimeMillis()
        groupMemberCache[chatId]?.let { cached ->
            if (cached.second > now) return cached.first
            groupMemberCache.remove(chatId, cached)
        }
        return groupMemberIds(chatId).also { ids ->
            groupMemberCache[chatId] = ids to (now + 60_000L)
        }
    }

    suspend fun sendEncryptedGroupMessage(
        conversationId: String,
        e2ee: E2eeClient,
        messageId: String,
        plaintext: String,
        messageType: String = "text",
        replyToId: String? = null
    ) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val chatId = normalizeUuid(conversationId)
        if (plaintext.isBlank() || plaintext.toByteArray(Charsets.UTF_8).size > 12_000) {
            throw IOException("Сообщение пустое или слишком большое для защищённого конверта")
        }
        val members = groupMemberIds(chatId)
        if (uid !in members) throw IOException("Вы больше не состоите в этой группе")
        val own = JSONObject(e2ee.ensureDevice())
        val ownDeviceId = own.getString("device_id")
        val devicesByUser = activeE2eeDevicesForUsers(members).groupBy { it.userId }
        val publishedOwnDevice = (devicesByUser[uid] ?: emptyList()).singleOrNull { it.deviceId == ownDeviceId }
            ?: throw IOException("Ваше E2EE-устройство не зарегистрировано на сервере. Повторите инициализацию защиты.")
        if (publishedOwnDevice.curve25519Key != own.getString("curve25519") ||
            publishedOwnDevice.ed25519Key != own.getString("ed25519")) {
            throw IOException("Ключи вашего E2EE-устройства не совпадают с регистрацией сервера")
        }
        val recipients = mutableListOf<E2eeGroupRecipientEnvelope>()
        for (memberId in members) {
            val devices = (devicesByUser[memberId] ?: emptyList()).filterNot { memberId == uid && it.deviceId == ownDeviceId }
            if (devices.isEmpty()) {
                if (memberId == uid) continue
                throw IOException("У участника $memberId нет активного E2EE-устройства. Отправка остановлена.")
            }
            for (device in devices) {
                if (device.protocol != "olm-v1" || device.bundleVersion != 1) {
                    throw IOException("Устройство участника использует неподдерживаемый протокол")
                }
                e2ee.trustRemoteDeviceOnFirstUse(memberId, device.deviceId, device.curve25519Key, device.ed25519Key)
                val pending = e2ee.pendingGroupEnvelope(chatId, messageId, memberId, device.deviceId)
                if (pending != null) {
                    val cached = e2ee.cachedMessage(chatId, messageId)
                        ?: throw IOException("Не найдена локальная копия ожидающего группового сообщения")
                    if (!cached.contentEquals(plaintext.toByteArray(Charsets.UTF_8))) {
                        throw IOException("Текст не совпадает с ожидающим групповым E2EE-сообщением")
                    }
                    recipients += pending
                } else if (e2ee.hasGroupSession(chatId, memberId, device.deviceId)) {
                    val ciphertext = e2ee.encryptGroupMessage(
                        chatId, memberId, device.deviceId, plaintext.toByteArray(Charsets.UTF_8), messageId
                    )
                    recipients += E2eeGroupRecipientEnvelope(
                        memberId, device.deviceId, E2eeMessageEnvelope.Kind.NORMAL, ciphertext
                    )
                } else {
                    val preKey = consumeGroupE2eePreKey(chatId, memberId, device.deviceId)
                    if (preKey.optString("ed25519_key") != device.ed25519Key ||
                        preKey.optString("curve25519_key") != device.curve25519Key) {
                        throw IOException("Ключ устройства участника изменился. Отправка отменена.")
                    }
                    val otk = preKey.optString("one_time_key")
                    val signature = preKey.optString("one_time_key_signature")
                    if (!e2ee.verifyPublishedOneTimeKey(device.ed25519Key, device.curve25519Key, otk, signature)) {
                        throw IOException("Подпись одноразового ключа участника недействительна")
                    }
                    val ciphertext = e2ee.startGroupSession(
                        chatId, memberId, device.deviceId, device.curve25519Key, device.ed25519Key,
                        otk, signature, plaintext.toByteArray(Charsets.UTF_8), messageId
                    )
                    recipients += E2eeGroupRecipientEnvelope(
                        memberId, device.deviceId, E2eeMessageEnvelope.Kind.PRE_KEY, ciphertext
                    )
                }
            }
        }
        val currentMembers = groupMemberIds(chatId)
        if (currentMembers.toSet() != members.toSet()) {
            throw IOException("Состав группы изменился во время отправки. Повторите отправку.")
        }
        val currentDevicesByUser = activeE2eeDevicesForUsers(members).groupBy { it.userId }
        for (memberId in members) {
            val currentDevices = (currentDevicesByUser[memberId] ?: emptyList())
                .filterNot { memberId == uid && it.deviceId == ownDeviceId }
            val sentDevices = recipients.filter { it.userId == memberId }
            if (currentDevices.map { it.deviceId }.toSet() != sentDevices.map { it.deviceId }.toSet() ||
                currentDevices.any { device ->
                    runCatching { e2ee.trustRemoteDeviceOnFirstUse(memberId, device.deviceId, device.curve25519Key, device.ed25519Key) }.isFailure
                }) {
                throw IOException("Список устройств или ключи участника изменились во время отправки. Повторите отправку.")
            }
        }
        val envelope = E2eeGroupMessageEnvelope(
            chatId, uid, ownDeviceId, own.getString("curve25519"), recipients
        ).encode()
        // Cloud history is an additional recovery layer, never a dependency for E2EE delivery.
        // If the server-side archive key is not configured or temporarily unavailable,
        // keep the original end-to-end encrypted send path working.
        val cloudContent = runCatching { encryptCloudHistory(chatId, messageId, plaintext) }.getOrNull()
        val payload = JSONObject()
            .put("id", normalizeUuid(messageId))
            .put("conversation_id", chatId)
            .put("sender_id", uid)
            .put("message_type", messageType)
            .put("content", envelope)
        if (!cloudContent.isNullOrBlank()) payload.put("cloud_content", cloudContent)
        if (!replyToId.isNullOrBlank()) payload.put("reply_to_id", normalizeUuid(replyToId))
        val inserted = JSONArray(call(
            "/rest/v1/messages?on_conflict=id", "POST", payload.toString(),
            prefer = "resolution=ignore-duplicates,return=representation"
        ))
        // Normal sends are verified by the INSERT response in one round trip.
        // Only an idempotent retry that hit an existing ID needs a follow-up read.
        val saved = if (inserted.length() == 1) inserted else JSONArray(call(
            "/rest/v1/messages?select=id,sender_id,content&id=eq.${normalizeUuid(messageId)}&conversation_id=eq.$chatId&limit=1"
        ))
        if (saved.length() != 1 || saved.getJSONObject(0).optString("sender_id") != uid ||
            saved.getJSONObject(0).optString("content") != envelope) {
            throw IOException("Сервер не подтвердил сохранение группового E2EE-сообщения")
        }
        e2ee.markGroupOutboundDelivered(chatId, messageId, recipients)
        runCatching { syncE2eeBackupIfEnabled(e2ee) }
        if (inserted.length() == 1) notifyChatPush(chatId)
    }

    private suspend fun decryptGroupEnvelope(
        raw: String,
        chatId: String,
        messageId: String,
        senderId: String,
        e2ee: E2eeClient
    ): String {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val envelope = E2eeGroupMessageEnvelope.decode(raw)
        if (envelope.conversationId != normalizeUuid(chatId) || envelope.senderUserId != senderId) {
            throw IOException("Групповой конверт не соответствует чату или отправителю")
        }
        val members = cachedGroupMemberIds(chatId)
        if (senderId !in members || uid !in members) throw IOException("Отправитель или получатель не состоит в группе")
        val deviceCacheKey = "$senderId:${envelope.senderDeviceId}"
        val now = System.currentTimeMillis()
        val cachedDevice = remoteDeviceCache[deviceCacheKey]
        val senderDevice = if (cachedDevice != null && cachedDevice.second > now) {
            cachedDevice.first
        } else {
            val senderRows = JSONArray(call(
                "/rest/v1/e2ee_devices?select=device_id,curve25519_key,ed25519_key,protocol,bundle_version" +
                    "&user_id=eq.$senderId&device_id=eq.${envelope.senderDeviceId}&is_active=eq.true&limit=1"
            ))
            if (senderRows.length() != 1) throw IOException("Устройство отправителя не зарегистрировано")
            val row = senderRows.getJSONObject(0)
            E2eeRemoteDevice(
                senderId, envelope.senderDeviceId, row.optString("curve25519_key"),
                row.optString("ed25519_key"), row.optString("protocol"), row.optInt("bundle_version")
            ).also { remoteDeviceCache[deviceCacheKey] = it to (now + 60_000L) }
        }
        if (senderDevice.curve25519Key != envelope.senderCurve25519 ||
            senderDevice.protocol != "olm-v1" || senderDevice.bundleVersion != 1) {
            throw IOException("Идентичность отправителя группы не подтверждена")
        }
        val myDeviceId = e2ee.deviceId()
        if (senderId == uid && envelope.senderDeviceId == myDeviceId) {
            val own = JSONObject(e2ee.ensureDevice())
            if (senderDevice.curve25519Key != own.optString("curve25519") ||
                senderDevice.ed25519Key != own.optString("ed25519")) {
                throw IOException("Идентичность отправляющего устройства не совпадает")
            }
            return e2ee.cachedMessage(chatId, messageId)?.toString(Charsets.UTF_8)
                ?: throw IOException("Локальная копия группового сообщения недоступна")
        }
        e2ee.trustRemoteDeviceOnFirstUse(senderId, envelope.senderDeviceId, senderDevice.curve25519Key, senderDevice.ed25519Key)
        val recipient = envelope.recipients.singleOrNull {
            it.userId == uid && it.deviceId == myDeviceId
        } ?: throw IOException("Это групповое сообщение не адресовано данному устройству")
        val cached = e2ee.cachedMessage(chatId, messageId)
        val plaintext = cached ?: when (recipient.kind) {
            E2eeMessageEnvelope.Kind.PRE_KEY -> e2ee.acceptGroupSession(
                chatId, senderId, envelope.senderDeviceId, envelope.senderCurve25519,
                recipient.ciphertext, messageId
            )
            E2eeMessageEnvelope.Kind.NORMAL -> e2ee.decryptGroupMessage(
                chatId, senderId, envelope.senderDeviceId, recipient.ciphertext, messageId
            )
        }
        return plaintext.toString(Charsets.UTF_8)
    }

    /** Resolve the sole peer in a direct conversation; group chats are rejected. */
    suspend fun directPeerUserId(conversationId: String): String {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val rows = JSONArray(call(
            "/rest/v1/conversation_members?select=user_id&conversation_id=eq.${normalizeUuid(conversationId)}&user_id=neq.$uid"
        ))
        if (rows.length() != 1) throw IOException("E2EE сейчас поддерживает только личные чаты с одним собеседником")
        return rows.getJSONObject(0).getString("user_id")
    }

    suspend fun activeE2eeDevices(userId: String): List<E2eeRemoteDevice> =
        activeE2eeDevicesForUsers(listOf(userId))

    private suspend fun activeE2eeDevicesForUsers(userIds: Collection<String>): List<E2eeRemoteDevice> {
        val ids = userIds.map { normalizeUuid(it) }.distinct()
        if (ids.isEmpty()) return emptyList()
        val rows = JSONArray(call(
            "/rest/v1/e2ee_devices?select=user_id,device_id,curve25519_key,ed25519_key,protocol,bundle_version" +
                "&user_id=in.(${ids.joinToString(",")})&is_active=eq.true&order=created_at.asc"
        ))
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            E2eeRemoteDevice(
                row.getString("user_id"), row.getString("device_id"),
                row.getString("curve25519_key"), row.getString("ed25519_key"),
                row.getString("protocol"), row.getInt("bundle_version")
            )
        }
    }

    /**
     * Direct-message E2EE send path. The encrypted envelope is persisted locally
     * before network I/O; retries with the same message ID reuse the same ratchet
     * ciphertext. No plaintext fallback is permitted.
     */
    /**
     * Direct-message fan-out: create a distinct Olm ciphertext for every active
     * device belonging to the peer account. The persisted envelope is the same
     * per-device format used by group chats, so every logged-in device can decrypt
     * its own copy while the server only stores ciphertext.
     */
    /** Telegram-style cloud message: server stores only an authenticated AES-GCM envelope. */
    suspend fun sendCloudMessage(
        conversationId: String,
        messageId: String,
        plaintext: String,
        replyToId: String? = null
    ) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val chatId = normalizeUuid(conversationId)
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        if (plaintext.isBlank() || bytes.size > 12_000) {
            throw IOException("Сообщение пустое или слишком большое")
        }
        val cloudContent = encryptCloudHistory(chatId, messageId, plaintext)
        val payload = JSONObject()
            .put("id", normalizeUuid(messageId))
            .put("conversation_id", chatId)
            .put("sender_id", uid)
            .put("message_type", "text")
            .put("content", "NEXURA_CLOUD_V1")
            .put("cloud_content", cloudContent)
        if (!replyToId.isNullOrBlank()) payload.put("reply_to_id", normalizeUuid(replyToId))
        val inserted = JSONArray(call(
            "/rest/v1/messages?on_conflict=id", "POST", payload.toString(),
            prefer = "resolution=ignore-duplicates,return=representation"
        ))
        val saved = if (inserted.length() == 1) inserted else JSONArray(call(
            "/rest/v1/messages?select=id,sender_id,content,cloud_content&id=eq.${normalizeUuid(messageId)}&conversation_id=eq.$chatId&limit=1"
        ))
        if (saved.length() != 1 || saved.getJSONObject(0).optString("sender_id") != uid ||
            saved.getJSONObject(0).optString("content") != "NEXURA_CLOUD_V1" ||
            saved.getJSONObject(0).optString("cloud_content") != cloudContent) {
            throw IOException("Сервер не подтвердил сохранение облачного сообщения")
        }
        if (inserted.length() == 1) notifyChatPush(chatId)
    }

    suspend fun sendEncryptedDirectMessage(
        conversationId: String,
        e2ee: E2eeClient,
        messageId: String,
        plaintext: String,
        replyToId: String? = null,
        messageType: String = "text"
    ) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val chatId = normalizeUuid(conversationId)
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        if (plaintext.isBlank() || bytes.size > 12_000) {
            throw IOException("Сообщение пустое или слишком большое для защищённого конверта")
        }
        val peerId = directPeerUserId(chatId)
        if (peerId == uid) throw IOException("Нельзя отправить сообщение самому себе")

        val ownBundle = JSONObject(e2ee.ensureDevice())
        val ownDeviceId = ownBundle.getString("device_id")
        val (ownDevices, peerDevices) = coroutineScope {
            val own = async { activeE2eeDevices(uid) }
            val peer = async { activeE2eeDevices(peerId) }
            own.await() to peer.await()
        }
        val ownPublished = ownDevices.singleOrNull { it.deviceId == ownDeviceId }
            ?: throw IOException("Ваше E2EE-устройство не зарегистрировано на сервере. Повторите инициализацию защиты.")
        if (ownPublished.curve25519Key != ownBundle.getString("curve25519") ||
            ownPublished.ed25519Key != ownBundle.getString("ed25519")) {
            throw IOException("Ключи вашего E2EE-устройства не совпадают с регистрацией сервера")
        }
        if (peerDevices.isEmpty()) throw IOException("У собеседника пока нет активных устройств Nexura")
        // Supabase intentionally exposes OTK rows only to their owner. A sender
        // must never infer a peer's key inventory through a direct table SELECT:
        // RLS returns zero rows even when the peer has a healthy key reserve.
        // Attempt the protected atomic consume RPC for each peer device instead;
        // it reveals only one usable public pre-key and safely handles races.
        val peerDevicesWithKeys = peerDevices
        if (peerDevicesWithKeys.isEmpty()) {
            throw IOException("У собеседника пока нет активных устройств Nexura")
        }
        // Offline own devices may have exhausted their published OTK pool; skip
        // those replicas instead of failing the entire direct-message send.
        val ownOtherDevices = ownDevicesWithAvailableKeys(
            uid, ownDevices.filterNot { it.deviceId == ownDeviceId }
        )
        val allTargets = (peerDevicesWithKeys + ownOtherDevices).distinctBy { "${it.userId}:${it.deviceId}" }
        if (allTargets.size > E2eeGroupMessageEnvelope.MAX_RECIPIENTS) {
            throw IOException("Слишком много активных устройств для одной защищённой отправки")
        }

        val pending = e2ee.cachedMessage(chatId, messageId)
        if (pending != null && !pending.contentEquals(bytes)) {
            throw IOException("Текст не совпадает с ожидающим зашифрованным сообщением")
        }

        val recipients = mutableListOf<E2eeGroupRecipientEnvelope>()
        for (device in allTargets) {
            if (device.protocol != "olm-v1" || device.bundleVersion != 1) {
                throw IOException("Одно из устройств собеседника использует неподдерживаемый протокол")
            }
            e2ee.trustRemoteDeviceOnFirstUse(device.userId, device.deviceId, device.curve25519Key, device.ed25519Key)
            val saved = e2ee.pendingGroupEnvelope(chatId, messageId, device.userId, device.deviceId)
            if (saved != null) {
                val cached = e2ee.cachedMessage(chatId, messageId)
                    ?: throw IOException("Не найдена локальная копия ожидающего сообщения")
                if (!cached.contentEquals(bytes)) throw IOException("Текст не совпадает с ожидающим сообщением")
                recipients += saved
            } else if (e2ee.hasGroupSession(chatId, device.userId, device.deviceId)) {
                val cipher = e2ee.encryptGroupMessage(chatId, device.userId, device.deviceId, bytes, messageId)
                recipients += E2eeGroupRecipientEnvelope(device.userId, device.deviceId, E2eeMessageEnvelope.Kind.NORMAL, cipher)
            } else {
                val preKey = if (device.userId == uid) {
                    try {
                        consumeOwnE2eePreKey(chatId, device.deviceId)
                    } catch (error: Exception) {
                        // The target may have consumed its last key after our
                        // availability check. Keep delivery to the peer and
                        // remaining devices; never invent/reuse an OTK.
                        if (error.message.orEmpty().contains("no available one-time key", ignoreCase = true)) {
                            continue
                        }
                        throw error
                    }
                } else {
                    try {
                        consumeE2eePreKey(chatId, device.userId, device.deviceId)
                    } catch (error: Exception) {
                        // Another sender may consume the last key after our count.
                        // Continue only because a different usable replica may remain.
                        if (error.message.orEmpty().contains("no available one-time key", ignoreCase = true)) {
                            continue
                        }
                        throw error
                    }
                }
                if (preKey.optString("ed25519_key") != device.ed25519Key ||
                    preKey.optString("curve25519_key") != device.curve25519Key) {
                    throw IOException("Ключ одного из устройств собеседника изменился. Отправка отменена.")
                }
                val otk = preKey.optString("one_time_key")
                val signature = preKey.optString("one_time_key_signature")
                if (!e2ee.verifyPublishedOneTimeKey(device.ed25519Key, device.curve25519Key, otk, signature)) {
                    throw IOException("Подпись одноразового ключа одного из устройств недействительна")
                }
                val cipher = e2ee.startGroupSession(
                    chatId, device.userId, device.deviceId, device.curve25519Key, device.ed25519Key,
                    otk, signature, bytes, messageId
                )
                recipients += E2eeGroupRecipientEnvelope(device.userId, device.deviceId, E2eeMessageEnvelope.Kind.PRE_KEY, cipher)
            }
        }

        if (recipients.none { it.userId == peerId }) {
            // Keep the encrypted-at-rest outbox item queued. The app retries it
            // automatically; never ask users to manage cryptographic keys.
            throw E2eePreKeyUnavailableException(
                "Ожидаем доступный ключ собеседника. Сообщение отправится автоматически."
            )
        }

        val (latestPeerDevices, latestOwnDevicesRaw) = coroutineScope {
            val peer = async { activeE2eeDevices(peerId) }
            val own = async { activeE2eeDevices(uid) }
            peer.await() to own.await()
        }
        // Compare against the own-device set selected before consuming OTKs.
        // Its available-key count is expected to decrease during this send.
        val selectedOwnDeviceIds = ownOtherDevices.map { it.deviceId }.toSet()
        val latestOwnDevices = latestOwnDevicesRaw.filter {
            it.deviceId != ownDeviceId && it.deviceId in selectedOwnDeviceIds
        }
        val latestTargets = (latestPeerDevices + latestOwnDevices).distinctBy { "${it.userId}:${it.deviceId}" }
        if (latestTargets.map { "${it.userId}:${it.deviceId}" }.toSet() !=
            (peerDevices + ownOtherDevices).map { "${it.userId}:${it.deviceId}" }.toSet()) {
            throw IOException("Список устройств одного из участников изменился во время отправки. Повторите отправку.")
        }
        val envelope = E2eeGroupMessageEnvelope(
            chatId, uid, ownDeviceId, ownBundle.getString("curve25519"), recipients
        ).encode()
        // Cloud history is an additional recovery layer, never a dependency for E2EE delivery.
        // If the server-side archive key is not configured or temporarily unavailable,
        // keep the original end-to-end encrypted send path working.
        val cloudContent = runCatching { encryptCloudHistory(chatId, messageId, plaintext) }.getOrNull()
        val payload = JSONObject()
            .put("id", normalizeUuid(messageId))
            .put("conversation_id", chatId)
            .put("sender_id", uid)
            .put("message_type", messageType)
            .put("content", envelope)
        if (!cloudContent.isNullOrBlank()) payload.put("cloud_content", cloudContent)
        if (!replyToId.isNullOrBlank()) payload.put("reply_to_id", normalizeUuid(replyToId))
        val inserted = JSONArray(call(
            "/rest/v1/messages?on_conflict=id", "POST", payload.toString(),
            prefer = "resolution=ignore-duplicates,return=representation"
        ))
        // Normal sends are verified by the INSERT response in one round trip.
        // Only an idempotent retry that hit an existing ID needs a follow-up read.
        val saved = if (inserted.length() == 1) inserted else JSONArray(call(
            "/rest/v1/messages?select=id,sender_id,content&id=eq.${normalizeUuid(messageId)}&conversation_id=eq.$chatId&limit=1"
        ))
        if (saved.length() != 1 || saved.getJSONObject(0).optString("sender_id") != uid ||
            saved.getJSONObject(0).optString("content") != envelope) {
            throw IOException("Сервер не подтвердил сохранение именно этого зашифрованного сообщения")
        }
        e2ee.markGroupOutboundDelivered(chatId, messageId, recipients)
        runCatching { syncE2eeBackupIfEnabled(e2ee) }
        if (inserted.length() == 1) notifyChatPush(chatId)
    }


    data class CallPushResult(
        val targeted: Int,
        val acceptedByFcm: Int,
        val failed: Int,
        val noDeviceTokens: Boolean
    )

    suspend fun notifyIncomingCall(conversationId: String, recipientUserId: String, callId: String, video: Boolean, sentAt: Long): CallPushResult {
        val response = JSONObject(call(
            "/functions/v1/send-call-push",
            "POST",
            JSONObject()
                .put("conversation_id", normalizeUuid(conversationId))
                .put("recipient_user_id", normalizeUuid(recipientUserId))
                .put("call_id", callId)
                .put("video", false)
                .put("sent_at", sentAt)
                .toString()
        ))
        val targeted = response.optInt("targeted", response.optInt("sent", 0))
        val accepted = response.optInt("accepted_by_fcm", response.optInt("sent", 0))
        val failed = response.optInt("failed", (targeted - accepted).coerceAtLeast(0))
        return CallPushResult(targeted, accepted, failed, response.optBoolean("no_device_tokens", targeted == 0))
    }

    suspend fun registerPushToken(token: String) {
        if (store.userId.isNullOrBlank()) throw IOException("Сессия истекла")
        if (token.length !in 20..4096) throw IOException("Некорректный токен уведомлений")
        call(
            "/rest/v1/rpc/register_own_device_token",
            "POST",
            JSONObject().put("p_token", token).toString()
        )
    }

    // Push notifications must never contain message text, sender names, or media details.
    private fun notifyChatPush(chatId: String) {
        // Push is secondary to message persistence. Do not hold the sender UI
        // while the Edge Function starts or waits on FCM delivery.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                call("/functions/v1/send-chat-push", "POST",
                    JSONObject().put("conversation_id", normalizeUuid(chatId)).toString())
            } catch (_: Exception) { }
        }
    }

    @Deprecated("Use sendEncryptedDirectMessage; plaintext transport is disabled in this branch.")
    suspend fun sendMessage(chatId: String, text: String, replyToId: String? = null): Nothing {
        throw IOException("Отправка открытого текста отключена. Используйте подтверждённое E2EE-устройство.")
    }


    /** Cloud backup contains only an AES-256-GCM envelope; the recovery code never leaves this device. */
    suspend fun hasE2eeBackup(): Boolean {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val rows = JSONArray(call(
            "/rest/v1/e2ee_state_backups?select=user_id&user_id=eq.$uid&limit=1"
        ))
        return rows.length() == 1
    }



    /**
     * Permanently revokes every other registered device while preserving the
     * newly confirmed current device. The server keeps tombstones, disables
     * the old device rows, and deletes their unused one-time public keys.
     */
    suspend fun removeOtherOwnE2eeDevices(keepDeviceId: String) {
        val keep = normalizeUuid(keepDeviceId)
        val result = call(
            "/rest/v1/rpc/revoke_other_own_e2ee_devices",
            "POST",
            JSONObject().put("p_keep_device_id", keep).toString()
        )
        val revokedCount = result.trim().toIntOrNull()
            ?: throw IOException("Сервер не подтвердил отзыв старых E2EE-устройств")
        if (revokedCount < 0) throw IOException("Некорректный ответ сервера при отзыве устройств")
    }

    /** Lists only the signed-in user's currently active E2EE devices. */
    suspend fun ownE2eeDevices(): List<E2eeRemoteDevice> {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        return activeE2eeDevices(uid)
    }

    /** Permanently blocks one selected device from E2EE key discovery and OTK replenishment. */
    suspend fun revokeOwnE2eeDevice(deviceId: String) {
        val id = normalizeUuid(deviceId)
        val response = call(
            "/rest/v1/rpc/revoke_own_e2ee_device",
            "POST",
            JSONObject().put("p_device_id", id).toString()
        ).trim()
        if (response != "true") throw IOException("Сервер не подтвердил отзыв E2EE-устройства")
        remoteDeviceCache.entries.removeIf { it.key.endsWith(":$id") }
    }

    suspend fun hasRegisteredE2eeDevice(): Boolean {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val rows = JSONArray(call(
            "/rest/v1/e2ee_devices?select=device_id&user_id=eq.$uid&is_active=eq.true&limit=1"
        ))
        return rows.length() > 0
    }

    suspend fun uploadE2eeBackup(e2ee: E2eeClient): Boolean {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        if (!e2ee.needsBackupSync()) return false
        val revision = e2ee.backupRevision()
        val envelope = e2ee.createEncryptedBackup()
        val payload = JSONObject()
            .put("user_id", uid)
            .put("format_version", 1)
            .put("encrypted_payload", envelope)
            .put("updated_at", java.time.Instant.now().toString())
        call(
            "/rest/v1/e2ee_state_backups?on_conflict=user_id",
            "POST",
            payload.toString(),
            prefer = "resolution=merge-duplicates,return=minimal"
        )
        e2ee.markBackupSynced(revision)
        return true
    }

    suspend fun restoreE2eeBackup(e2ee: E2eeClient, recoveryCode: String) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        if (e2ee.hasLocalDevice()) throw IOException("На этом устройстве уже есть ключи E2EE; восстановление отменено")
        val rows = JSONArray(call(
            "/rest/v1/e2ee_state_backups?select=format_version,encrypted_payload&user_id=eq.$uid&limit=1"
        ))
        if (rows.length() != 1) throw IOException("Защищённая резервная копия не найдена")
        val row = rows.getJSONObject(0)
        if (row.optInt("format_version") != 1) throw IOException("Версия резервной копии не поддерживается")
        try {
            e2ee.restoreEncryptedBackup(row.getString("encrypted_payload"), recoveryCode)
        } catch (error: Exception) {
            throw IOException("Не удалось восстановить ключи. Проверьте ключ восстановления.", error)
        }
    }

    suspend fun syncE2eeBackupIfEnabled(e2ee: E2eeClient) {
        if (!e2ee.needsBackupSync()) return
        uploadE2eeBackup(e2ee)
    }

    suspend fun privacySettings(): PrivacySettings {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val profile = JSONArray(call("/rest/v1/profiles?select=read_receipts_enabled&id=eq.$uid&limit=1"))
        val presence = JSONArray(call("/rest/v1/user_presence?select=show_online_status&user_id=eq.$uid&limit=1"))
        return PrivacySettings(
            showOnlineStatus = if (presence.length() > 0) presence.getJSONObject(0).optBoolean("show_online_status", true) else true,
            readReceiptsEnabled = if (profile.length() > 0) profile.getJSONObject(0).optBoolean("read_receipts_enabled", true) else true
        )
    }

    suspend fun updatePrivacySettings(showOnlineStatus: Boolean, readReceiptsEnabled: Boolean) {
        call("/rest/v1/rpc/update_my_privacy_settings", "POST",
            JSONObject().put("p_show_online_status", showOnlineStatus)
                .put("p_read_receipts_enabled", readReceiptsEnabled).toString())
    }

    suspend fun updatePresenceHeartbeat() {
        val uid = store.userId ?: return
        val now = java.time.Instant.now().toString()
        val existing = JSONArray(call("/rest/v1/user_presence?select=user_id&user_id=eq.$uid&limit=1"))
        if (existing.length() > 0) {
            call("/rest/v1/user_presence?user_id=eq.$uid", "PATCH",
                JSONObject().put("last_seen_at", now).toString(), prefer = "return=minimal")
        } else {
            call("/rest/v1/user_presence", "POST",
                JSONObject().put("user_id", uid).put("last_seen_at", now).toString(),
                prefer = "return=minimal")
        }
    }

    suspend fun userPresence(userId: String): UserPresence {
        val id = normalizeUuid(userId)
        val rows = JSONArray(call("/rest/v1/rpc/get_user_presence", "POST",
            JSONObject().put("p_user_id", id).toString()))
        if (rows.length() == 0) return UserPresence(false, false, null)
        val row = rows.getJSONObject(0)
        return UserPresence(row.optBoolean("is_visible"), row.optBoolean("is_online"),
            row.optString("last_seen_at").takeIf { it.isNotBlank() && it != "null" })
    }

    suspend fun blockedUsers(): List<UserItem> {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val blocks = JSONArray(call("/rest/v1/user_blocks?select=blocked_id&blocker_id=eq.$uid&order=created_at.desc"))
        val ids = (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it)?.optString("blocked_id")?.takeIf { id -> id.isNotBlank() } }
        if (ids.isEmpty()) return emptyList()
        val rows = JSONArray(call("/rest/v1/profiles?select=id,display_name,username,avatar_url&id=in.(" + ids.joinToString(",") + ")"))
        val users = (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            UserItem(row.optString("id"), row.optString("display_name").ifBlank { row.optString("username").ifBlank { "Пользователь" } },
                row.optString("avatar_url").ifBlank { "🙂" })
        }
        return ids.mapNotNull { id -> users.firstOrNull { it.id == id } }
    }

    suspend fun blockUser(userId: String) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val blocked = normalizeUuid(userId)
        if (blocked == uid) throw IOException("Нельзя заблокировать собственный аккаунт")
        call("/rest/v1/user_blocks?on_conflict=blocker_id,blocked_id", "POST",
            JSONObject().put("blocker_id", uid).put("blocked_id", blocked).toString(),
            prefer = "resolution=ignore-duplicates,return=minimal")
    }

    suspend fun unblockUser(userId: String) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        call("/rest/v1/user_blocks?blocker_id=eq.$uid&blocked_id=eq." + normalizeUuid(userId), "DELETE",
            prefer = "return=minimal")
    }

    suspend fun peerReadReceiptAt(chatId: String, peerUserId: String?): String? {
        if (peerUserId.isNullOrBlank()) return null
        val rows = JSONArray(call("/rest/v1/conversation_members?select=last_receipt_at&conversation_id=eq.${normalizeUuid(chatId)}&user_id=eq.${normalizeUuid(peerUserId)}&limit=1"))
        if (rows.length() == 0) return null
        return rows.getJSONObject(0).optString("last_receipt_at").takeIf { it.isNotBlank() && it != "null" }
    }

    suspend fun markChatRead(chatId: String, shareReadReceipt: Boolean = true) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val now = java.time.Instant.now().toString()
        val payload = JSONObject().put("last_read_at", now)
        if (shareReadReceipt) payload.put("last_receipt_at", now)
        call("/rest/v1/conversation_members?conversation_id=eq.$chatId&user_id=eq.$uid", "PATCH",
            payload.toString(), prefer = "return=minimal")
    }

    suspend fun editMessage(messageId: String, newText: String) {
        if (newText.isBlank()) throw IOException("Сообщение не может быть пустым")
        val rows = JSONArray(call(
            "/rest/v1/messages?select=content&id=eq.${normalizeUuid(messageId)}&limit=1"
        ))
        if (rows.length() == 1 && E2eeMessageEnvelope.isEnvelope(rows.getJSONObject(0).optString("content"))) {
            throw IOException("Зашифрованное сообщение нельзя редактировать открытым PATCH. Отправьте исправление новым E2EE-сообщением.")
        }
        call("/rest/v1/messages?id=eq.${normalizeUuid(messageId)}&message_type=eq.text", "PATCH",
            JSONObject().put("content", newText.trim()).put("edited_at", java.time.Instant.now().toString()).toString(),
            prefer = "return=minimal")
    }

    suspend fun deleteMessage(messageId: String) {
        call("/rest/v1/messages?id=eq.${normalizeUuid(messageId)}", "PATCH",
            JSONObject().put("deleted_at", java.time.Instant.now().toString()).toString(),
            prefer = "return=minimal")
    }

    /**
     * Replaces an E2EE message by sending a freshly encrypted message, then
     * soft-deleting the old row. Plaintext is never PATCHed to Postgres.
     */
    suspend fun editEncryptedMessage(
        chatId: String,
        e2ee: E2eeClient,
        oldMessageId: String,
        newText: String,
        isGroup: Boolean,
        replyToId: String? = null
    ) {
        if (newText.isBlank()) throw IOException("Сообщение не может быть пустым")
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val normalizedOldId = normalizeUuid(oldMessageId)
        val oldRows = JSONArray(call(
            "/rest/v1/messages?select=created_at&id=eq.$normalizedOldId&conversation_id=eq.${normalizeUuid(chatId)}&sender_id=eq.$uid&limit=1"
        ))
        if (oldRows.length() != 1) throw IOException("Сообщение не найдено или больше недоступно для изменения")
        val originalCreatedAt = oldRows.getJSONObject(0).optString("created_at")
        val wasPinned = pinnedMessage(chatId) == normalizedOldId
        if (wasPinned && !canManageConversation(chatId)) {
            throw IOException("Закреплённое сообщение может изменить только администратор чата")
        }
        val replacementId = UUID.randomUUID().toString()
        if (isGroup) {
            sendEncryptedGroupMessage(chatId, e2ee, replacementId, newText.trim(), "text", replyToId)
        } else {
            sendEncryptedDirectMessage(chatId, e2ee, replacementId, newText.trim(), replyToId, "text")
        }
        call(
            "/rest/v1/messages?id=eq.${normalizeUuid(replacementId)}",
            "PATCH",
            JSONObject()
                .put("edited_at", java.time.Instant.now().toString())
                .put("created_at", originalCreatedAt)
                .toString(),
            prefer = "return=minimal"
        )
        if (wasPinned) setPinnedMessage(chatId, replacementId)
        deleteMessage(oldMessageId)
    }

    suspend fun messageReactions(chatId: String): Map<String, List<MessageReaction>> {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val rows = JSONArray(call(
            "/rest/v1/message_reactions?select=message_id,user_id,emoji&conversation_id=eq.${normalizeUuid(chatId)}"
        ))
        val grouped = linkedMapOf<String, MutableMap<String, Pair<Int, Boolean>>>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val messageId = row.optString("message_id")
            val emoji = row.optString("emoji")
            val mine = row.optString("user_id") == uid
            val counts = grouped.getOrPut(messageId) { linkedMapOf() }
            val previous = counts[emoji]
            counts[emoji] = ((previous?.first ?: 0) + 1) to (mine || previous?.second == true)
        }
        return grouped.mapValues { (_, counts) ->
            counts.map { (emoji, summary) -> MessageReaction(emoji, summary.first, summary.second) }
                .sortedWith(compareByDescending<MessageReaction> { it.selectedByMe }.thenByDescending { it.count })
        }
    }

    /**
     * One active reaction per user and message, matching the interaction model
     * people expect: tapping the selected emoji removes it; tapping another
     * replaces the previous one.
     */
    suspend fun toggleMessageReaction(chatId: String, messageId: String, emoji: String) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val message = normalizeUuid(messageId)
        val conversation = normalizeUuid(chatId)
        val filter = "/rest/v1/message_reactions?message_id=eq.$message&user_id=eq.$uid"
        val existing = JSONArray(call(filter + "&select=emoji&limit=10"))
        val alreadySelected = (0 until existing.length()).any { existing.getJSONObject(it).optString("emoji") == emoji }
        // Remove the user's previous choice first. If the same emoji was active,
        // this is a toggle-off; otherwise the new choice is inserted below.
        if (existing.length() > 0) call(filter, "DELETE", prefer = "return=minimal")
        if (!alreadySelected) {
            call("/rest/v1/message_reactions", "POST",
                JSONObject().put("message_id", message)
                    .put("conversation_id", conversation).put("user_id", uid).put("emoji", emoji).toString(),
                prefer = "return=minimal")
        }
    }

    suspend fun canManageConversation(chatId: String): Boolean {
        val uid = store.userId ?: return false
        val rows = JSONArray(call(
            "/rest/v1/conversation_members?select=member_role&conversation_id=eq.${normalizeUuid(chatId)}&user_id=eq.$uid&limit=1"
        ))
        if (rows.length() != 1) return false
        return rows.getJSONObject(0).optString("member_role") in setOf("owner", "admin")
    }

    suspend fun pinnedMessage(chatId: String): String? {
        val rows = JSONArray(call(
            "/rest/v1/conversation_pins?select=message_id&conversation_id=eq.${normalizeUuid(chatId)}&limit=1"
        ))
        return if (rows.length() == 0) null else rows.getJSONObject(0).optString("message_id").takeIf { it.isNotBlank() }
    }

    suspend fun setPinnedMessage(chatId: String, messageId: String?) {
        val conversation = normalizeUuid(chatId)
        if (messageId == null) {
            call("/rest/v1/conversation_pins?conversation_id=eq.$conversation", "DELETE", prefer = "return=minimal")
        } else {
            val uid = store.userId ?: throw IOException("Сессия истекла")
            call("/rest/v1/conversation_pins?on_conflict=conversation_id", "POST",
                JSONObject().put("conversation_id", conversation).put("message_id", normalizeUuid(messageId))
                    .put("pinned_by", uid).toString(),
                prefer = "resolution=merge-duplicates,return=minimal")
        }
    }
    private suspend fun signedAttachmentUrl(path: String, bucket: String = "chat-attachments"): String {
        val now = System.currentTimeMillis()
        val cacheKey = "$bucket/$path"
        signedUrlCache[cacheKey]?.let { cached ->
            if (cached.second > now) return cached.first
            signedUrlCache.remove(cacheKey, cached)
        }
        val payload = JSONObject().put("expiresIn", 3600)
        val result = JSONObject(call("/storage/v1/object/sign/$bucket/$path", "POST", payload.toString()))
        val signed = result.optString("signedURL").ifBlank { result.optString("signedUrl") }
        if (signed.isBlank()) throw IOException("Не удалось получить ссылку на медиафайл")
        val url = if (signed.startsWith("http")) signed else "$BASE_URL/storage/v1$signed"
        if (signedUrlCache.size > 500) signedUrlCache.clear()
        signedUrlCache[cacheKey] = url to (now + 55 * 60 * 1000L)
        return url
    }

    suspend fun sendEncryptedMediaMessage(
        chatId: String,
        e2ee: E2eeClient,
        encryptedMedia: EncryptedMediaFile,
        caption: String = "",
        replyToId: String? = null
    ) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        if (encryptedMedia.cipherSize !in 1..(25L * 1024L * 1024L)) throw IOException("Зашифрованный файл превышает лимит Storage")
        val chat = normalizeUuid(chatId)
        val fileId = UUID.randomUUID().toString()
        val path = "$chat/$uid/$fileId.enc"
        val mediaType = when { encryptedMedia.mimeType.startsWith("video/") -> "video"; encryptedMedia.mimeType.startsWith("audio/") -> "audio"; encryptedMedia.mimeType.startsWith("image/") -> "image"; else -> "file" }
        val uploadRequest = Request.Builder()
            .url("$BASE_URL/storage/v1/object/nexura-chat-media/$path")
            .header("apikey", PUBLIC_KEY)
            .header("Authorization", "Bearer ${store.accessToken ?: throw IOException("Сессия истекла")}")
            .header("x-upsert", "false")
            .post(encryptedMedia.file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        val mediaClient = client.newBuilder()
            .writeTimeout(2, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .callTimeout(3, TimeUnit.MINUTES)
            .build()
        val upload = withContext(Dispatchers.IO) { mediaClient.newCall(uploadRequest).execute() }
        upload.use {
            if (!it.isSuccessful) throw IOException("Не удалось загрузить зашифрованное медиа (HTTP ${it.code})")
        }

        val payload = JSONObject()
            .put("schema", "nexura-media-v1")
            .put("path", path)
            .put("mime", encryptedMedia.mimeType)
            .put("media_type", mediaType)
            .put("name", encryptedMedia.fileName)
            .put("size", encryptedMedia.plainSize)
            .put("key", encryptedMedia.keyBase64)
            .put("iv", encryptedMedia.ivBase64)
            .put("cipher_sha256", encryptedMedia.cipherSha256)
            .put("caption", caption.trim())
            .toString()
        val messageId = UUID.randomUUID().toString()
        try {
            // If the POST committed but its read-back confirmation was interrupted,
            // keep the ciphertext object: deleting it could break a valid message.
            sendEncryptedDirectMessage(chat, e2ee, messageId, payload, replyToId, "media")
        } finally {
            encryptedMedia.file.delete()
        }
    }

    suspend fun sendEncryptedGroupMediaMessage(
        chatId: String,
        e2ee: E2eeClient,
        encryptedMedia: EncryptedMediaFile,
        caption: String = "",
        replyToId: String? = null
    ) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        if (encryptedMedia.cipherSize !in 1..(25L * 1024L * 1024L)) {
            throw IOException("Зашифрованный файл превышает лимит Storage")
        }
        val chat = normalizeUuid(chatId)
        val members = groupMemberIds(chat)
        if (uid !in members) throw IOException("Вы больше не состоите в этой группе")
        val fileId = UUID.randomUUID().toString()
        val path = "$chat/$uid/$fileId.enc"
        val uploadRequest = Request.Builder()
            .url("$BASE_URL/storage/v1/object/nexura-chat-media/$path")
            .header("apikey", PUBLIC_KEY)
            .header("Authorization", "Bearer ${store.accessToken ?: throw IOException("Сессия истекла")}")
            .header("x-upsert", "false")
            .post(encryptedMedia.file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        val mediaClient = client.newBuilder()
            .writeTimeout(2, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .callTimeout(3, TimeUnit.MINUTES)
            .build()
        val upload = withContext(Dispatchers.IO) { mediaClient.newCall(uploadRequest).execute() }
        upload.use {
            if (!it.isSuccessful) throw IOException("Не удалось загрузить зашифрованное медиа (HTTP ${it.code})")
        }
        val payload = JSONObject()
            .put("schema", "nexura-media-v1")
            .put("path", path)
            .put("mime", encryptedMedia.mimeType)
            .put("media_type", when { encryptedMedia.mimeType.startsWith("video/") -> "video"; encryptedMedia.mimeType.startsWith("audio/") -> "audio"; encryptedMedia.mimeType.startsWith("image/") -> "image"; else -> "file" })
            .put("name", encryptedMedia.fileName)
            .put("size", encryptedMedia.plainSize)
            .put("key", encryptedMedia.keyBase64)
            .put("iv", encryptedMedia.ivBase64)
            .put("cipher_sha256", encryptedMedia.cipherSha256)
            .put("caption", caption.trim())
            .toString()
        try {
            sendEncryptedGroupMessage(chat, e2ee, UUID.randomUUID().toString(), payload, "media", replyToId)
        } finally {
            encryptedMedia.file.delete()
        }
    }

    private suspend fun downloadAndDecryptMedia(
        payload: JSONObject,
        messageId: String,
        conversationId: String,
        senderId: String
    ): String {
        val path = payload.getString("path")
        val pathParts = path.split("/")
        if (pathParts.size != 3 || pathParts[0] != normalizeUuid(conversationId) ||
            pathParts[1] != normalizeUuid(senderId)) {
            throw IOException("Медиафайл не привязан к отправителю и этому чату")
        }
        if (!messageId.matches(Regex("^[0-9a-fA-F-]{36}$"))) {
            throw IOException("Некорректный ID сообщения с вложением")
        }
        val cacheDir = storeMediaCacheDir()
        if (!cacheDir.exists() && !cacheDir.mkdirs()) throw IOException("Не удалось подготовить кэш медиа")
        val cached = File(cacheDir, "$messageId.dec")
        val cacheDigest = File(cacheDir, "$messageId.dec.sha256")
        // Never trust a plaintext cache entry merely because the file exists.
        // A sidecar digest detects truncation or accidental modification between reads.
        val cachedDigest = runCatching { cacheDigest.readText().trim() }.getOrNull()
        if (cached.exists() && cached.length() == payload.optLong("size", -1L) &&
            cachedDigest != null && cachedDigest.matches(Regex("^[0-9a-f]{64}$")) &&
            cachedDigest == sha256MediaFile(cached)) {
            return android.net.Uri.fromFile(cached).toString()
        }
        cached.delete()
        cacheDigest.delete()
        val url = signedAttachmentUrl(path, "nexura-chat-media")
        val request = Request.Builder().url(url).get().build()
        val mediaClient = client.newBuilder()
            .readTimeout(2, TimeUnit.MINUTES)
            .callTimeout(3, TimeUnit.MINUTES)
            .build()
        val response = withContext(Dispatchers.IO) { mediaClient.newCall(request).execute() }
        val encrypted = File.createTempFile("nexura-download-", ".enc", storeMediaCacheDir())
        try {
            response.use {
                if (!it.isSuccessful) throw IOException("Не удалось скачать медиа (HTTP ${it.code})")
                val body = it.body ?: throw IOException("Пустой ответ Storage")
                withContext(Dispatchers.IO) {
                    body.byteStream().use { input ->
                        FileOutputStream(encrypted).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > 25L * 1024L * 1024L) throw IOException("Зашифрованный файл слишком большой")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            withContext(Dispatchers.IO) {
                E2eeMediaCrypto.decryptFile(
                    encrypted, cached, payload.getString("key"), payload.getString("iv"),
                    payload.getString("cipher_sha256")
                )
                if (cached.length() != payload.optLong("size", -1L)) {
                    cached.delete()
                    throw IOException("Размер расшифрованного медиа не совпадает с метаданными")
                }
                val digest = sha256MediaFile(cached)
                val tempDigest = File.createTempFile("nexura-cache-", ".sha256", cacheDir)
                try {
                    tempDigest.writeText(digest)
                    if (!tempDigest.renameTo(cacheDigest)) {
                        throw IOException("Не удалось сохранить контрольную сумму медиа")
                    }
                } finally {
                    tempDigest.delete()
                }
            }
            return android.net.Uri.fromFile(cached).toString()
        } catch (error: Exception) {
            cached.delete()
            cacheDigest.delete()
            throw error
        } finally {
            encrypted.delete()
        }
    }

    private fun sha256MediaFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun storeMediaCacheDir(): File = store.mediaCacheDir

    @Deprecated("Use sendEncryptedMediaMessage; plaintext media uploads are disabled.")
    suspend fun sendImageMessage(chatId: String, bytes: ByteArray, mimeType: String): Nothing {
        throw IOException("Открытая загрузка медиа отключена. Используйте E2EE.")
    }

    suspend fun myProfile(): UserProfile {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val raw = JSONArray(call("/rest/v1/profiles?select=id,display_name,username,avatar_url,bio,age&id=eq.$uid&limit=1"))
        if (raw.length() == 0) {
            val authUser = JSONObject(call("/auth/v1/user", auth = true))
            val metadata = authUser.optJSONObject("user_metadata") ?: JSONObject()
            val emailName = authUser.optString("email").substringBefore("@").takeIf { it.isNotBlank() } ?: "Пользователь"
            val display = metadata.optString("display_name").ifBlank { emailName }
            call("/rest/v1/profiles", "POST",
                JSONObject().put("id", uid).put("display_name", display).put("avatar_url", "🙂").toString(),
                prefer = "return=minimal")
            return UserProfile(uid, display, "", "🙂", "", null)
        }
        val p = raw.getJSONObject(0)
        return UserProfile(p.optString("id"), p.optString("display_name"), p.optString("username"), p.optString("avatar_url"), p.optString("bio"), if (p.isNull("age")) null else p.optInt("age"))
    }

    suspend fun updateMyProfile(displayName: String, avatar: String, bio: String = "", age: Int? = null) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        if (age != null && age !in 13..120) throw IOException("Возраст должен быть от 13 до 120 лет")
        val payload = JSONObject()
            .put("display_name", displayName.trim())
            .put("avatar_url", avatar)
            .put("bio", bio.trim().take(160))
            .put("age", age ?: JSONObject.NULL)
        call("/rest/v1/profiles?id=eq.$uid", "PATCH", payload.toString(), prefer = "return=minimal")
    }

    suspend fun userProfile(userId: String): UserProfile {
        val id = normalizeUuid(userId)
        val raw = JSONArray(call("/rest/v1/profiles?select=id,display_name,username,avatar_url,bio,age&id=eq.$id&limit=1"))
        if (raw.length() == 0) throw IOException("Профиль пользователя не найден")
        val p = raw.getJSONObject(0)
        return UserProfile(
            p.optString("id"),
            p.optString("display_name").ifBlank { p.optString("username").ifBlank { "Пользователь" } },
            p.optString("username"),
            p.optString("avatar_url").ifBlank { "🙂" },
            p.optString("bio"),
            if (p.isNull("age")) null else p.optInt("age")
        )
    }

    suspend fun uploadAvatar(bytes: ByteArray, mimeType: String): String {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        if (bytes.isEmpty() || bytes.size > 5 * 1024 * 1024) throw IOException("Фото должно быть не больше 5 МБ")
        val ext = when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val path = "$uid/avatar-${UUID.randomUUID()}.$ext"
        val request = Request.Builder()
            .url("$BASE_URL/storage/v1/object/nexura-avatars/$path")
            .header("apikey", PUBLIC_KEY)
            .header("Authorization", "Bearer ${store.accessToken ?: PUBLIC_KEY}")
            .header("Content-Type", mimeType)
            .header("x-upsert", "true")
            .post(bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("Не удалось загрузить фото профиля (HTTP ${it.code})")
        }
        return "$BASE_URL/storage/v1/object/public/nexura-avatars/$path"
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
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) return emptyList()
        // Encode user input as a single PostgREST filter value. A space must be %20,
        // not '+', because '+' is not consistently decoded as a space in raw filter expressions.
        val encoded = java.net.URLEncoder.encode(cleanQuery, "UTF-8").replace("+", "%20")
        val raw = JSONArray(call("/rest/v1/profiles?select=id,display_name,username,avatar_url&or=(display_name.ilike.%25$encoded%25,username.ilike.%25$encoded%25)&limit=20"))
        val currentUserId = store.userId
        return (0 until raw.length()).mapNotNull { i ->
            val p = raw.optJSONObject(i) ?: return@mapNotNull null
            val id = p.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val name = p.optString("display_name").takeIf { it.isNotBlank() && it != "null" }
                ?: p.optString("username").takeIf { it.isNotBlank() && it != "null" }
                ?: "Пользователь"
            val avatar = p.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" } ?: "🙂"
            UserItem(id, name, avatar)
        }.filter { it.id != currentUserId && it.id.isNotBlank() }
            .distinctBy { it.id }
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
        val cleanTitle = title.trim()
        if (cleanTitle.length !in 2..60) {
            throw IOException("Название группы должно содержать от 2 до 60 символов")
        }
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val normalizedMembers = memberIds
            .map { normalizeUuid(it) }
            .filterNot { it == uid }
            .distinct()
        if (normalizedMembers.size !in 1..49) {
            throw IOException("Выберите от 1 до 49 других участников")
        }
        val ids = JSONArray()
        normalizedMembers.forEach { ids.put(it) }
        val payload = JSONObject().put("p_title", cleanTitle).put("p_member_ids", ids)
        return normalizeUuid(call("/rest/v1/rpc/create_group_conversation", "POST", payload.toString()))
    }

    suspend fun createDirect(userId: String): String {
        val result = call("/rest/v1/rpc/create_direct_conversation", "POST",
            JSONObject().put("p_other_user_id", normalizeUuid(userId)).toString())
        return normalizeUuid(result)
    }
}
