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
import java.util.concurrent.ConcurrentHashMap

data class ChatItem(val id: String, val title: String, val kind: String, val updated: String?, val unreadCount: Int = 0, val avatar: String = "🙂", val peerUserId: String? = null)
data class UserProfile(val id: String, val displayName: String, val username: String, val avatar: String, val bio: String = "", val age: Int? = null)
data class UserItem(val id: String, val displayName: String, val avatar: String)
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
    val mediaCaption: String? = null
)
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val signedUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val remoteDeviceCache = ConcurrentHashMap<String, Pair<E2eeRemoteDevice, Long>>()

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
    /** Revokes every refresh-token session except the one currently used by this device. */
    suspend fun signOutOtherSessions() {
        call("/auth/v1/logout?scope=others", "POST", "{}", auth = true)
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
    suspend fun chats(): List<ChatItem> {
        val uid = store.userId ?: return emptyList()
        // Fetch memberships and the aggregate unread counters concurrently.
        val (membershipJson, unreadJson) = coroutineScope {
            val memberships = async {
                call("/rest/v1/conversation_members?select=conversation_id,conversations(id,kind,title,last_message_at)&user_id=eq.$uid&order=joined_at.desc")
            }
            val unread = async { call("/rest/v1/rpc/get_my_unread_counts", "POST", "{}") }
            memberships.await() to unread.await()
        }
        val raw = JSONArray(membershipJson)
        // Aggregate unread counts in Postgres; do not download thousands of message rows.
        val unreadRows = JSONArray(unreadJson)
        val unreadByChat = mutableMapOf<String, Int>()
        for (i in 0 until unreadRows.length()) {
            val row = unreadRows.optJSONObject(i) ?: continue
            val id = row.optString("conversation_id")
            if (id.isNotBlank() && id != "null") unreadByChat[id] = row.optInt("unread_count")
        }

        val base = (0 until raw.length()).mapNotNull { i ->
            val membership = raw.optJSONObject(i) ?: return@mapNotNull null
            val c = membership.optJSONObject("conversations") ?: return@mapNotNull null
            val id = c.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val kind = c.optString("kind").takeIf { it != "null" } ?: "direct"
            val unread = unreadByChat[id] ?: 0
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
                chat.copy(title = profile?.first ?: "Пользователь", avatar = profile?.second ?: "🙂", peerUserId = peerByChat[chat.id])
            }
        }
    }
    suspend fun messages(chatId: String, e2ee: E2eeClient? = null, afterCreatedAt: String? = null, mediaOnly: Boolean = false): List<MessageItem> {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        val afterFilter = afterCreatedAt?.let {
            "&created_at=gte.${java.net.URLEncoder.encode(it, "UTF-8")}"
        }.orEmpty()
        val limit = if (afterCreatedAt == null) 50 else 100
        val mediaFilter = if (mediaOnly) "&message_type=in.(media,image)" else ""
        val raw = JSONArray(call(
            "/rest/v1/messages?select=id,sender_id,content,created_at,message_type,reply_to_id,edited_at" +
                "&conversation_id=eq.$chatId&deleted_at=is.null&order=created_at.desc&limit=$limit$afterFilter$mediaFilter"
        ))
        return (0 until raw.length()).map { i ->
            val m = raw.getJSONObject(i)
            val id = m.getString("id")
            val senderId = m.optString("sender_id")
            val type = m.optString("message_type", "text")
            val content = m.optString("content")
            val replyTo = m.optString("reply_to_id").takeIf { it.isNotBlank() && it != "null" }
            val edited = m.optString("edited_at").takeIf { it.isNotBlank() && it != "null" }

            if (type in setOf("text", "media", "call") &&
                (E2eeMessageEnvelope.isEnvelope(content) || E2eeGroupMessageEnvelope.isEnvelope(content))) {
                val decrypted = if (E2eeGroupMessageEnvelope.isEnvelope(content)) {
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
                val mediaJson = if (type == "media" && decrypted != null) E2eeMediaCrypto.parsePayload(decrypted) else null
                val mediaUri = if (mediaJson != null) {
                    runCatching { downloadAndDecryptMedia(mediaJson, id, chatId, senderId) }.getOrNull()
                } else null
                MessageItem(
                    id, senderId,
                    when {
                        decrypted == null -> "🔒 Сообщение зашифровано. Проверьте устройство или восстановите защищённую сессию."
                        type == "media" && mediaUri == null -> "⚠ Не удалось проверить или открыть медиафайл"
                        type == "media" -> mediaUri!!
                        else -> decrypted
                    },
                    m.optString("created_at"), type, replyTo, edited,
                    encrypted = decrypted != null && (type != "media" || mediaUri != null),
                    securityWarning = decrypted == null || (type == "media" && mediaUri == null),
                    mediaKind = mediaJson?.optString("media_type"),
                    mediaCaption = mediaJson?.optString("caption")?.takeIf { it.isNotBlank() }
                )
            } else {
                val rendered = if (type == "image" && content.isNotBlank()) signedAttachmentUrl(content) else content
                MessageItem(
                    id, senderId, rendered, m.optString("created_at"), type, replyTo, edited,
                    encrypted = false,
                    securityWarning = type == "text" || type == "image" || type == "file"
                )
            }
        }.sortedBy { it.createdAt }
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
        if (available < 10) {
            e2ee.replenishOneTimeKeys(20)
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
        val rows = JSONArray(call(
            "/rest/v1/conversation_members?select=user_id&conversation_id=eq.${normalizeUuid(conversationId)}&order=joined_at.asc"
        ))
        val ids = (0 until rows.length()).map { rows.getJSONObject(it).getString("user_id") }.distinct()
        if (ids.size !in 2..E2eeGroupMessageEnvelope.MAX_RECIPIENTS) {
            throw IOException("В группе должно быть от 2 до ${E2eeGroupMessageEnvelope.MAX_RECIPIENTS} участников")
        }
        return ids
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
        val payload = JSONObject()
            .put("id", normalizeUuid(messageId))
            .put("conversation_id", chatId)
            .put("sender_id", uid)
            .put("message_type", messageType)
            .put("content", envelope)
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
        notifyChatPush(chatId)
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
        val members = groupMemberIds(chatId)
        if (senderId !in members || uid !in members) throw IOException("Отправитель или получатель не состоит в группе")
        val senderRows = JSONArray(call(
            "/rest/v1/e2ee_devices?select=device_id,curve25519_key,ed25519_key,protocol,bundle_version" +
                "&user_id=eq.$senderId&device_id=eq.${envelope.senderDeviceId}&is_active=eq.true&limit=1"
        ))
        if (senderRows.length() != 1) throw IOException("Устройство отправителя не зарегистрировано")
        val sender = senderRows.getJSONObject(0)
        if (sender.optString("curve25519_key") != envelope.senderCurve25519 ||
            sender.optString("protocol") != "olm-v1" || sender.optInt("bundle_version") != 1) {
            throw IOException("Идентичность отправителя группы не подтверждена")
        }
        val myDeviceId = e2ee.deviceId()
        if (senderId == uid && envelope.senderDeviceId == myDeviceId) {
            val own = JSONObject(e2ee.ensureDevice())
            if (sender.optString("curve25519_key") != own.optString("curve25519") ||
                sender.optString("ed25519_key") != own.optString("ed25519")) {
                throw IOException("Идентичность отправляющего устройства не совпадает")
            }
            return e2ee.cachedMessage(chatId, messageId)?.toString(Charsets.UTF_8)
                ?: throw IOException("Локальная копия группового сообщения недоступна")
        }
        e2ee.trustRemoteDeviceOnFirstUse(senderId, envelope.senderDeviceId, sender.optString("curve25519_key"), sender.optString("ed25519_key"))
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
        val ownOtherDevices = ownDevices.filterNot { it.deviceId == ownDeviceId }
        val allTargets = (peerDevices + ownOtherDevices).distinctBy { "${it.userId}:${it.deviceId}" }
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
                    consumeOwnE2eePreKey(chatId, device.deviceId)
                } else {
                    consumeE2eePreKey(chatId, device.userId, device.deviceId)
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

        val (latestPeerDevices, latestOwnDevicesRaw) = coroutineScope {
            val peer = async { activeE2eeDevices(peerId) }
            val own = async { activeE2eeDevices(uid) }
            peer.await() to own.await()
        }
        val latestOwnDevices = latestOwnDevicesRaw.filterNot { it.deviceId == ownDeviceId }
        val latestTargets = (latestPeerDevices + latestOwnDevices).distinctBy { "${it.userId}:${it.deviceId}" }
        if (latestTargets.map { "${it.userId}:${it.deviceId}" }.toSet() != allTargets.map { "${it.userId}:${it.deviceId}" }.toSet()) {
            throw IOException("Список устройств одного из участников изменился во время отправки. Повторите отправку.")
        }
        val envelope = E2eeGroupMessageEnvelope(
            chatId, uid, ownDeviceId, ownBundle.getString("curve25519"), recipients
        ).encode()
        val payload = JSONObject()
            .put("id", normalizeUuid(messageId))
            .put("conversation_id", chatId)
            .put("sender_id", uid)
            .put("message_type", messageType)
            .put("content", envelope)
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
        notifyChatPush(chatId)
    }


    suspend fun notifyIncomingCall(conversationId: String, recipientUserId: String, callId: String, video: Boolean) {
        call(
            "/functions/v1/send-call-push",
            "POST",
            JSONObject()
                .put("conversation_id", normalizeUuid(conversationId))
                .put("recipient_user_id", normalizeUuid(recipientUserId))
                .put("call_id", callId)
                .put("video", video)
                .toString()
        )
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

    suspend fun markChatRead(chatId: String) {
        val uid = store.userId ?: throw IOException("Сессия истекла")
        call("/rest/v1/conversation_members?conversation_id=eq.$chatId&user_id=eq.$uid", "PATCH",
            JSONObject().put("last_read_at", java.time.Instant.now().toString()).toString(),
            prefer = "return=minimal")
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
        val mediaType = if (encryptedMedia.mimeType.startsWith("video/")) "video" else "image"
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
            .put("media_type", if (encryptedMedia.mimeType.startsWith("video/")) "video" else "image")
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
        val cached = File(storeMediaCacheDir(), "$messageId.dec")
        if (cached.exists() && cached.length() > 0) return android.net.Uri.fromFile(cached).toString()
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
            }
            return android.net.Uri.fromFile(cached).toString()
        } catch (error: Exception) {
            cached.delete()
            throw error
        } finally {
            encrypted.delete()
        }
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
