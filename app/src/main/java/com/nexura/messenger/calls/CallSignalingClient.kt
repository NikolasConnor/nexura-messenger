package com.nexura.messenger.calls

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Supabase Realtime v1 JSON protocol adapter for private per-user call topics.
 * A user listens only to user:<their UUID>; a caller joins the callee topic
 * with INSERT authorization granted only to users sharing a conversation.
 */
class CallSignalingClient(
    private val projectRef: String,
    private val apiKey: String,
    private val accessToken: String,
    private val ownUserId: String,
    private val peerUserId: String,
    private val onSignal: (JSONObject) -> Unit,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socket: WebSocket
    private val ownTopic = "realtime:user:$ownUserId"
    private val peerTopic = "realtime:user:$peerUserId"
    private val joined = mutableSetOf<String>()
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var readyDelivered = false
    @Volatile private var errorDelivered = false

    init {
        val request = Request.Builder()
            .url("wss://$projectRef.supabase.co/realtime/v1/websocket?apikey=$apiKey&vsn=1.0.0")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                join(ownTopic, "1")
                if (peerTopic != ownTopic) join(peerTopic, "2")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("event")) {
                        "phx_reply" -> {
                            val payload = message.optJSONObject("payload")
                            if (payload?.optString("status") == "ok") {
                                val topic = message.optString("topic")
                                val allJoined = synchronized(lock) {
                                    joined.add(topic)
                                    joined.contains(ownTopic) && joined.contains(peerTopic)
                                }
                                if (allJoined && !readyDelivered) {
                                    readyDelivered = true
                                    onReady()
                                }
                            } else if (payload?.optString("status") == "error") {
                                reportError(payload.optJSONObject("response")?.toString() ?: "Realtime отклонил подключение")
                            }
                        }
                        "broadcast" -> {
                            val payload = message.optJSONObject("payload") ?: return
                            if (payload.optString("event") == "call_signal") {
                                val signal = payload.optJSONObject("payload") ?: return
                                if (signal.optString("to") == ownUserId) onSignal(signal)
                            }
                        }
                        "phx_error", "phx_close" -> reportError("Соединение сигнализации прервано")
                    }
                } catch (e: Exception) {
                    reportError(e.message ?: "Ошибка обработки сигнала звонка")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed) reportError(buildString {
                    append(t.message ?: "Не удалось подключиться к серверу звонков")
                    if (response != null) append(" (HTTP ").append(response.code).append(")")
                })
            }
        })
        scope.launch {
            delay(18_000)
            if (!closed && !readyDelivered) reportError("Сервер звонков не подтвердил подключение за 18 секунд. Проверьте сеть и повторите попытку.")
        }
        scope.launch {
            var ref = 10
            while (isActive && !closed) {
                delay(25_000)
                socket.send(JSONObject()
                    .put("topic", "phoenix")
                    .put("event", "heartbeat")
                    .put("payload", JSONObject())
                    .put("ref", (ref++).toString()).toString())
            }
        }
    }

    private fun reportError(message: String) {
        if (closed || errorDelivered) return
        errorDelivered = true
        onError(message)
    }

    private fun join(topic: String, ref: String) {
        val payload = JSONObject()
            .put("config", JSONObject()
                .put("broadcast", JSONObject().put("ack", true).put("self", false))
                .put("presence", JSONObject().put("enabled", false))
                .put("private", true))
            .put("access_token", accessToken)
        socket.send(JSONObject()
            .put("topic", topic)
            .put("event", "phx_join")
            .put("payload", payload)
            .put("ref", ref)
            .put("join_ref", ref).toString())
    }

    fun sendSignal(signal: JSONObject): Boolean {
        if (closed || !readyDelivered || !synchronized(lock) { joined.contains(peerTopic) }) return false
        val payload = JSONObject()
            .put("event", "call_signal")
            .put("type", "broadcast")
            .put("payload", signal)
        return socket.send(JSONObject()
            .put("topic", peerTopic)
            .put("event", "broadcast")
            .put("payload", payload)
            .put("ref", UUID.randomUUID().toString())
            .put("join_ref", "2").toString())
    }

    fun close() {
        closed = true
        scope.cancel()
        socket.close(1000, "Call finished")
        http.dispatcher.executorService.shutdown()
    }
}
