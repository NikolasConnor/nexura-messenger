package com.nexura.messenger.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class RealtimeMessageEvent(
    val connected: Boolean,
    val messageChanged: Boolean = false,
    val changedConversationId: String? = null
)

/**
 * One authenticated Supabase Realtime socket for the currently open conversation.
 * Events are only sync hints; message content is always fetched and E2EE-decrypted by NexuraApi.
 */
class SupabaseMessagesRealtime(private val store: SessionStore) {
    private val socketClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun observeConversation(conversationId: String): Flow<RealtimeMessageEvent> =
        observeConversations(listOf(conversationId))

    /** One socket can watch all conversations the signed-in user belongs to. */
    fun observeConversations(conversationIds: List<String>): Flow<RealtimeMessageEvent> = flow {
        val ids = conversationIds.map(String::trim).filter(String::isNotBlank).distinct().take(50)
        if (ids.isEmpty()) {
            emit(RealtimeMessageEvent(connected = false))
            return@flow
        }
        var backoff = 1_000L
        while (currentCoroutineContext().isActive) {
            try {
                emitAll(callbackFlow {
                    val token = store.accessToken
                    if (token.isNullOrBlank()) {
                        trySend(RealtimeMessageEvent(connected = false))
                        close()
                        return@callbackFlow
                    }

                    val encodedKey = URLEncoder.encode(NexuraApi.PUBLIC_KEY, "UTF-8")
                    val url = "${NexuraApi.BASE_URL}/realtime/v1/websocket?apikey=$encodedKey&vsn=1.0.0"
                    val request = Request.Builder().url(url).build()
                    var socket: WebSocket? = null
                    var heartbeatJob: kotlinx.coroutines.Job? = null
                    var ref = 1L
                    val joinRef = "1"

                    socket = socketClient.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            val postgresChanges = JSONArray()
                            ids.forEach { conversationId ->
                                postgresChanges.put(
                                    JSONObject()
                                        .put("event", "INSERT")
                                        .put("schema", "public")
                                        .put("table", "messages")
                                        .put("filter", "conversation_id=eq.$conversationId")
                                )
                            }
                            val payload = JSONObject()
                                .put("access_token", token)
                                .put("config", JSONObject()
                                    .put("broadcast", JSONObject().put("ack", false).put("self", false))
                                    .put("presence", JSONObject().put("key", ""))
                                    .put("postgres_changes", postgresChanges)
                                    .put("private", false))
                            val join = JSONObject()
                                .put("topic", "realtime:public:messages")
                                .put("event", "phx_join")
                                .put("payload", payload)
                                .put("ref", joinRef)
                                .put("join_ref", joinRef)
                            if (!webSocket.send(join.toString())) {
                                close(IllegalStateException("Не удалось отправить запрос подписки Realtime"))
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            runCatching {
                                val message = JSONObject(text)
                                when (message.optString("event")) {
                                    "phx_reply" -> {
                                        val payload = message.optJSONObject("payload")
                                        if (payload?.optString("status") == "ok") {
                                            trySend(RealtimeMessageEvent(connected = true))
                                        } else if (payload?.optString("status") == "error") {
                                            close(IllegalStateException("Supabase Realtime отклонил подписку"))
                                        }
                                    }
                                    "postgres_changes" -> {
                                        val data = message.optJSONObject("payload")?.optJSONObject("data")
                                        val record = data?.optJSONObject("record")
                                        val conversationId = record?.optString("conversation_id")
                                            ?.takeIf { !it.isNullOrBlank() && it != "null" }
                                        trySend(RealtimeMessageEvent(
                                            connected = true,
                                            messageChanged = true,
                                            changedConversationId = conversationId
                                        ))
                                    }
                                    "system" -> {
                                        val payload = message.optJSONObject("payload")
                                        if (payload?.optString("status") == "error") {
                                            close(IllegalStateException(payload.optString("message", "Ошибка Realtime")))
                                        }
                                    }
                                }
                            }.onFailure { close(it) }
                        }

                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            onMessage(webSocket, bytes.utf8())
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            trySend(RealtimeMessageEvent(connected = false))
                            close()
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                            trySend(RealtimeMessageEvent(connected = false))
                            close(t)
                        }
                    })

                    heartbeatJob = launch {
                        while (isActive) {
                            delay(25_000L)
                            val heartbeat = JSONObject()
                                .put("topic", "phoenix")
                                .put("event", "heartbeat")
                                .put("payload", JSONObject())
                                .put("ref", (ref++).toString())
                            if (socket?.send(heartbeat.toString()) != true) {
                                close(IllegalStateException("Соединение Realtime потеряно"))
                                break
                            }
                        }
                    }

                    awaitClose {
                        heartbeatJob?.cancel()
                        socket?.close(1000, "Conversation changed")
                    }
                })
                backoff = 1_000L
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emit(RealtimeMessageEvent(connected = false))
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }
}
