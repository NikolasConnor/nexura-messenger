package com.nexura.messenger.calls

import android.content.Context
import org.json.JSONObject
import org.webrtc.IceCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import android.content.Intent
import androidx.core.content.ContextCompat

data class CallUiState(
    val status: String = "Подключение…",
    val incoming: Boolean = false,
    val active: Boolean = false,
    val connected: Boolean = false,
    val outgoing: Boolean = false,
    val video: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val cameraEnabled: Boolean = true,
    val error: String? = null,
    val localVideoTrack: org.webrtc.VideoTrack? = null,
    val remoteVideoTrack: org.webrtc.VideoTrack? = null
)

class CallCoordinator(
    private val context: Context,
    private val chatId: String,
    private val ownUserId: String,
    private val peerUserId: String,
    private val api: com.nexura.messenger.data.NexuraApi,
    private val onState: (CallUiState) -> Unit,
    private val onCallFinished: (Long, Boolean, Boolean, String) -> Unit = { _, _, _, _ -> }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var signaling: CallSignalingClient? = null
    private var engine: NexuraCallEngine? = null
    @Volatile private var callId: String? = null
    private var pendingOffer: String? = null
    private var videoMode = false
    private var ready = false
    private var closed = false
    @Volatile private var remoteDescriptionSet = false
    @Volatile private var localDescriptionSignaled = false
    private var offerRetryJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var connectedAtMs: Long? = null
    private var callVideoForHistory = false
    private var callDirectionOutgoing = false
    private var callHistoryWritten = false

    fun connect(token: String) {
        signaling?.close()
        signaling = CallSignalingClient(
            "lyhzawblxcvpgpjrkspc",
            com.nexura.messenger.data.NexuraApi.PUBLIC_KEY,
            token,
            ownUserId,
            peerUserId,
            { signal -> scope.launch { receive(signal) } },
            { scope.launch {
                ready = true
                flushIce()
                val previous = lastState
                if (previous?.active == true) {
                    publish(previous.copy(status = if (previous.connected) "На связи" else "Восстанавливаем связь…", error = null))
                } else {
                    publish(CallUiState(status = "Готово к звонку"))
                }
            } },
            { message -> scope.launch {
                ready = false
                val previous = lastState ?: CallUiState()
                publish(previous.copy(status = if (previous.active) "Восстанавливаем связь…" else "Ошибка связи", error = message))
            } }
        )
    }

    fun start(video: Boolean) {
        if (!ready || closed) {
            publish(CallUiState(status = "Подключение…", error = "Сервер звонков ещё подключается"))
            return
        }
        videoMode = video
        callVideoForHistory = video
        callDirectionOutgoing = true
        callHistoryWritten = false
        connectedAtMs = null
        callId = UUID.randomUUID().toString()
        localDescriptionSignaled = false
        startKeepAlive(video)
        try {
            createEngine(video)
        } catch (error: Exception) {
            failCall("Не удалось запустить звонок", error.message ?: "Проверьте разрешения микрофона и камеры")
            return
        }
        engine?.createOffer(
            onReady = { sdp ->
                val signal = JSONObject().put("type", "offer").put("call_id", callId)
                    .put("from", ownUserId).put("to", peerUserId).put("video", video).put("sdp", sdp)
                if (!send(signal)) {
                    failCall("Не удалось вызвать", "Сигнал не отправлен. Проверьте соединение и повторите попытку.")
                } else {
                    // The local SDP is installed; flush ICE candidates collected before this offer.
                    localDescriptionSignaled = true
                    publish((lastState ?: CallUiState()).copy(status = "Вызов…", incoming = false, active = true, outgoing = true, video = video, speaker = video))
                    scope.launch {
                        runCatching { api.notifyIncomingCall(chatId, peerUserId, callId!!, video) }
                    }
                    offerRetryJob?.cancel()
                    offerRetryJob = scope.launch {
                        repeat(10) {
                            delay(3_000)
                            if (callId == null || remoteDescriptionSet || closed) return@launch
                            send(signal)
                        }
                    }
                    ringTimeoutJob?.cancel()
                    val ringingCallId = callId
                    ringTimeoutJob = scope.launch {
                        delay(35_000)
                        if (callId == ringingCallId && !remoteDescriptionSet && !closed) {
                            send(JSONObject().put("type", "end").put("call_id", ringingCallId)
                                .put("from", ownUserId).put("to", peerUserId))
                            finish(false)
                            publish(CallUiState(status = "Готово к звонку", error = "Нет ответа"))
                        }
                    }
                }
                flushIce()
            },
            onError = { error -> failCall("Ошибка звонка", error) }
        )
    }

    fun accept() {
        val offer = pendingOffer ?: return
        videoMode = pendingVideo
        callVideoForHistory = pendingVideo
        callDirectionOutgoing = false
        callHistoryWritten = false
        connectedAtMs = null
        callId = pendingCallId
        startKeepAlive(videoMode)
        try {
            createEngine(videoMode)
        } catch (error: Exception) {
            failCall("Не удалось принять звонок", error.message ?: "Проверьте разрешения микрофона и камеры")
            return
        }
        engine?.acceptOffer(
            offer,
            onReady = { sdp ->
                remoteDescriptionSet = true
                val signal = JSONObject().put("type", "answer").put("call_id", callId)
                    .put("from", ownUserId).put("to", peerUserId).put("sdp", sdp)
                if (!send(signal)) {
                    failCall("Ошибка звонка", "Не удалось отправить ответ собеседнику")
                } else {
                    localDescriptionSignaled = true
                    flushIce()
                    armConnectionTimeout()
                    publish((lastState ?: CallUiState()).copy(status = "Соединяем…", incoming = false, active = true, outgoing = false, connected = false, video = videoMode, speaker = videoMode))
                }
            },
            onError = { error -> failCall("Ошибка звонка", error) }
        )
    }

    private var pendingVideo = false
    private var pendingCallId: String? = null

    private fun receive(signal: JSONObject) {
        if (signal.optString("to") != ownUserId) return
        val id = signal.optString("call_id")
        when (signal.optString("type")) {
            "offer" -> {
                if (id == pendingCallId || id == callId) return
                if (engine != null) {
                    send(JSONObject().put("type", "busy").put("call_id", id).put("from", ownUserId).put("to", peerUserId))
                    return
                }
                pendingOffer = signal.optString("sdp")
                pendingCallId = id
                pendingVideo = signal.optBoolean("video")
                publish(CallUiState(status = "Входящий видеозвонок".takeIf { pendingVideo } ?: "Входящий звонок", incoming = true, video = pendingVideo))
            }
            "answer" -> if (id == callId) {
                ringTimeoutJob?.cancel()
                ringTimeoutJob = null
                offerRetryJob?.cancel()
                engine?.acceptAnswer(
                    signal.optString("sdp"),
                    onReady = {
                        remoteDescriptionSet = true
                        flushIce()
                        armConnectionTimeout()
                        publish((lastState ?: CallUiState()).copy(status = "Соединяем…", incoming = false, active = true, connected = false, video = videoMode, speaker = videoMode))
                    },
                    onError = { error -> failCall("Ошибка звонка", error) }
                )
            }
            "ice" -> if (id == callId || id == pendingCallId) {
                val candidate = signal.optJSONObject("candidate") ?: return
                val sdp = candidate.optString("sdpMid").takeIf { it != "null" }
                val line = candidate.optInt("sdpMLineIndex")
                val value = candidate.optString("candidate")
                if (remoteDescriptionSet) engine?.addRemoteIce(value, sdp, line)
                else queuedRemoteIce.add(Triple(value, sdp, line))
            }
            "end", "decline", "busy" -> if (id == callId || id == pendingCallId) {
                val text = when (signal.optString("type")) {
                    "busy" -> "Абонент занят"
                    "decline" -> "Вызов отклонён"
                    else -> "Звонок завершён"
                }
                val wasOutgoing = lastState?.outgoing == true
                finish(false)
                publish(CallUiState(status = "Готово к звонку", outgoing = wasOutgoing, error = text))
            }
        }
    }

    private val queuedRemoteIce = ConcurrentLinkedQueue<Triple<String, String?, Int>>()

    fun sharedEglContext(): org.webrtc.EglBase.Context? = engine?.sharedEglContext()

    private fun createEngine(video: Boolean) {
        engine?.close()
        engine = NexuraCallEngine(
            context, video,
            onLocalIce = { candidate ->
                val id = callId
                if (id != null && localDescriptionSignaled && send(JSONObject().put("type", "ice").put("call_id", id)
                        .put("from", ownUserId).put("to", peerUserId)
                        .put("candidate", JSONObject().put("candidate", candidate.sdp)
                            .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex)))) {
                    // Trickle ICE can be sent as soon as the local description exists.
                } else queuedIce.add(candidate)
            },
            onRemoteVideo = { track -> scope.launch { publish((lastState ?: CallUiState()).copy(remoteVideoTrack = track)) } },
            onLocalVideo = { track -> scope.launch { publish((lastState ?: CallUiState()).copy(localVideoTrack = track)) } },
            onState = { value -> scope.launch {
                val connected = value == "Подключено"
                if (connected) {
                    connectionTimeoutJob?.cancel()
                    connectionTimeoutJob = null
                    if (connectedAtMs == null) connectedAtMs = System.currentTimeMillis()
                }
                publish((lastState ?: CallUiState()).copy(status = if (connected) "На связи" else value, connected = connected, video = videoMode))
            } }
        )
    }

    private val queuedIce = ConcurrentLinkedQueue<IceCandidate>()
    private fun flushIce() {
        val id = callId
        if (id != null && localDescriptionSignaled) {
            while (true) {
                val candidate = queuedIce.peek() ?: break
                val sent = send(JSONObject().put("type", "ice").put("call_id", id)
                    .put("from", ownUserId).put("to", peerUserId)
                    .put("candidate", JSONObject().put("candidate", candidate.sdp)
                        .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex)))
                if (sent) queuedIce.poll() else break
            }
        }
        if (remoteDescriptionSet) {
            while (true) {
                val candidate = queuedRemoteIce.poll() ?: break
                engine?.addRemoteIce(candidate.first, candidate.second, candidate.third)
            }
        }
    }

    private fun failCall(status: String, message: String) {
        finish(false)
        publish(CallUiState(status = if (ready) "Готово к звонку" else "Подключение…", error = "$status: $message"))
    }
    private fun armConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        val expectedCallId = callId
        connectionTimeoutJob = scope.launch {
            delay(40_000)
            if (callId == expectedCallId && lastState?.connected != true && !closed) {
                finish(false)
                publish(CallUiState(status = "Не удалось установить соединение", error = "Проверьте интернет и настройки сети, затем попробуйте снова."))
            }
        }
    }

    private fun startKeepAlive(video: Boolean) {
        val intent = Intent(context, CallKeepAliveService::class.java)
            .putExtra(CallKeepAliveService.EXTRA_VIDEO, video)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    private fun send(signal: JSONObject): Boolean = signaling?.sendSignal(signal) == true

    fun toggleSpeaker(): Boolean {
        val enabled = !(lastState?.speaker ?: videoMode)
        engine?.setSpeakerEnabled(enabled)
        publish((lastState ?: CallUiState()).copy(speaker = enabled))
        return enabled
    }

    fun toggleMute(): Boolean {
        val muted = !(lastState?.muted ?: false)
        engine?.setMuted(muted)
        publish((lastState ?: CallUiState()).copy(muted = muted))
        return muted
    }
    private var lastState: CallUiState? = null
    private fun publish(state: CallUiState) { lastState = state; onState(state) }

    fun toggleCamera(): Boolean {
        val enabled = !(lastState?.cameraEnabled ?: true)
        engine?.setCameraEnabled(enabled)
        publish((lastState ?: CallUiState()).copy(cameraEnabled = enabled))
        return enabled
    }
    fun switchCamera() { engine?.switchCamera() }

    fun decline() {
        val id = pendingCallId ?: callId
        if (id != null) send(JSONObject().put("type", "decline").put("call_id", id).put("from", ownUserId).put("to", peerUserId))
        finish(false)
    }

    fun end() {
        val id = callId ?: pendingCallId
        if (id != null) send(JSONObject().put("type", "end").put("call_id", id).put("from", ownUserId).put("to", peerUserId))
        finish(false)
    }

    private fun finish(closeSignaling: Boolean = true) {
        offerRetryJob?.cancel()
        offerRetryJob = null
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        runCatching { context.stopService(Intent(context, CallKeepAliveService::class.java)) }
        engine?.close()
        engine = null
        val wasOutgoing = callDirectionOutgoing
        val connectedAt = connectedAtMs
        if (wasOutgoing && callId != null && !callHistoryWritten) {
            callHistoryWritten = true
            val duration = if (connectedAt == null) 0L else
                ((System.currentTimeMillis() - connectedAt) / 1000L).coerceAtLeast(0L)
            val result = if (connectedAt != null) "completed" else "missed"
            runCatching { onCallFinished(duration, wasOutgoing, callVideoForHistory, result) }
        }
        connectedAtMs = null
        val readyForNextCall = !closeSignaling && ready
        publish(CallUiState(
            status = if (readyForNextCall) "Готово к звонку" else "Подключение…",
            outgoing = wasOutgoing
        ))
        pendingOffer = null
        pendingCallId = null
        callId = null
        remoteDescriptionSet = false
        localDescriptionSignaled = false
        videoMode = false
        pendingVideo = false
        callVideoForHistory = false
        callDirectionOutgoing = false
        if (closeSignaling) ready = false
        queuedIce.clear()
        queuedRemoteIce.clear()
        if (closeSignaling) signaling?.close()
        if (closeSignaling) signaling = null
    }

    fun close() {
        if (closed) return
        closed = true
        finish()
        scope.cancel()
    }
}
