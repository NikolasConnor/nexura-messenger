package com.nexura.messenger.calls

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Private per-user Supabase Realtime call-control signaling with bounded reconnect/backoff.
 * Audio/video media is transported separately through the LiveKit SFU room.
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
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Supabase Realtime expects the wire topic to include the "realtime:" namespace.
    // realtime.topic() in RLS evaluates the channel name without this transport prefix.
    private val ownTopic = "realtime:user:$ownUserId"
    private val peerTopic = "realtime:user:$peerUserId"
    private val joined = mutableSetOf<String>()
    private val lock = Any()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var readyDelivered = false
    @Volatile private var errorDelivered = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private val pendingBroadcastAcks = ConcurrentHashMap<String, (Boolean, String?) -> Unit>()

    init {
        connectSocket()
        scope.launch {
            delay(18_000)
            if (!closed && !readyDelivered) {
                reportError("Сервер звонков не подтвердил подключение. Проверьте сеть и повторите попытку.")
                scheduleReconnect()
            }
        }
        scope.launch {
            var ref = 10
            while (isActive && !closed) {
                delay(20_000)
                val activeSocket = socket
                if (readyDelivered && activeSocket != null) {
                    val sent = activeSocket.send(
                        JSONObject()
                            .put("topic", "phoenix")
                            .put("event", "heartbeat")
                            .put("payload", JSONObject())
                            .put("ref", (ref++).toString()).toString()
                    )
                    if (!sent) scheduleReconnect()
                }
            }
        }
    }

    private fun connectSocket() {
        if (closed) return
        val request = Request.Builder()
            .url("wss://$projectRef.supabase.co/realtime/v1/websocket?apikey=$apiKey&vsn=1.0.0")
            .build()
        val created = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== socket || closed) return
                synchronized(lock) { joined.clear() }
                readyDelivered = false
                join(ownTopic, "1")
                if (peerTopic != ownTopic) join(peerTopic, "2")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket || closed) return
                try {
                    val message = JSONObject(text)
                    when (message.optString("event")) {
                        "phx_reply" -> {
                            val payload = message.optJSONObject("payload")
                            val ref = message.optString("ref")
                            val status = payload?.optString("status")
                            // Broadcast ACKs use UUID refs. Do not mistake them for join replies.
                            val pendingAck = pendingBroadcastAcks.remove(ref)
                            if (pendingAck != null) {
                                val accepted = status == "ok"
                                val detail = payload?.optJSONObject("response")
                                    ?.optString("error")
                                    ?.takeIf { !it.isNullOrBlank() }
                                pendingAck(accepted, detail)
                                if (!accepted) {
                                    reportError("Realtime отклонил сигнал звонка" + (detail?.let { ": $it" } ?: ""))
                                }
                            } else if (ref == "1" || ref == "2") {
                                if (status == "ok") {
                                    val topic = message.optString("topic")
                                    val allJoined = synchronized(lock) {
                                        joined.add(topic)
                                        joined.contains(ownTopic) && joined.contains(peerTopic)
                                    }
                                    if (allJoined && !readyDelivered) {
                                        readyDelivered = true
                                        reconnectAttempt = 0
                                        reconnectJob?.cancel()
                                        reconnectJob = null
                                        errorDelivered = false
                                        onReady()
                                    }
                                } else if (status == "error") {
                                    val reason = payload?.optJSONObject("response")?.optString("reason")
                                        ?: payload?.optJSONObject("response")?.toString()
                                        ?: "Realtime отклонил подключение"
                                    reportError(reason)
                                    failPendingBroadcasts("Канал сигнализации не подключился")
                                    scheduleReconnect()
                                }
                            }
                        }
                        "broadcast" -> {
                            val payload = message.optJSONObject("payload") ?: return
                            if (payload.optString("event") == "call_signal") {
                                val signal = payload.optJSONObject("payload") ?: return
                                if (signal.optString("to") == ownUserId) onSignal(signal)
                            }
                        }
                        "phx_error", "phx_close" -> {
                            reportError("Соединение сигнализации прервано")
                            scheduleReconnect()
                        }
                    }
                } catch (e: Exception) {
                    reportError(e.message ?: "Ошибка обработки сигнала звонка")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== socket || closed) return
                socket = null
                readyDelivered = false
                synchronized(lock) { joined.clear() }
                failPendingBroadcasts("Соединение сигнализации прервано")
                reportError(
                    if (response != null) "Связь с сервером звонков прервана (HTTP ${response.code}). Переподключаемся…"
                    else "Связь с сервером звонков временно прервана. Переподключаемся…"
                )
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== socket || closed) return
                socket = null
                readyDelivered = false
                synchronized(lock) { joined.clear() }
                failPendingBroadcasts("Связь с сервером звонков закрыта")
                reportError("Связь с сервером звонков прервана. Переподключаемся…")
                scheduleReconnect()
            }
        })
        socket = created
    }

    private fun scheduleReconnect() {
        if (closed || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val waitMs = (1_000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30_000L)
            reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
            delay(waitMs)
            if (!closed && !readyDelivered) {
                val staleSocket = socket
                socket = null
                staleSocket?.cancel()
                connectSocket()
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
        socket?.send(JSONObject()
            .put("topic", topic)
            .put("event", "phx_join")
            .put("payload", payload)
            .put("ref", ref)
            .put("join_ref", ref).toString())
    }

    fun sendSignal(
        signal: JSONObject,
        onAcknowledged: ((Boolean, String?) -> Unit)? = null
    ): Boolean {
        if (closed || !readyDelivered || !synchronized(lock) { joined.contains(peerTopic) }) return false
        val ref = UUID.randomUUID().toString()
        if (onAcknowledged != null) {
            pendingBroadcastAcks[ref] = onAcknowledged
            scope.launch {
                delay(5_000)
                pendingBroadcastAcks.remove(ref)?.invoke(
                    false,
                    "Сервер сигнализации не подтвердил доставку за 5 секунд"
                )
            }
        }
        val payload = JSONObject()
            .put("event", "call_signal")
            .put("type", "broadcast")
            .put("payload", signal)
        val sent = socket?.send(JSONObject()
            .put("topic", peerTopic)
            .put("event", "broadcast")
            .put("payload", payload)
            .put("ref", ref)
            .put("join_ref", "2").toString()) == true
        if (!sent) pendingBroadcastAcks.remove(ref)
        return sent
    }

    private fun failPendingBroadcasts(reason: String) {
        val callbacks = pendingBroadcastAcks.values.toList()
        pendingBroadcastAcks.clear()
        callbacks.forEach { callback -> runCatching { callback(false, reason) } }
    }

    fun close() {
        closed = true
        reconnectJob?.cancel()
        failPendingBroadcasts("Сигнализация звонка закрыта")
        scope.cancel()
        socket?.close(1000, "Call finished")
        socket = null
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
