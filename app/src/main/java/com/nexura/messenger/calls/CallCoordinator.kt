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
import android.content.Intent
import androidx.core.content.ContextCompat

data class CallUiState(
    val status: String = "Подключение…",
    val incoming: Boolean = false,
    val active: Boolean = false,
    val video: Boolean = false,
    val muted: Boolean = false,
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
    private val onState: (CallUiState) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var signaling: CallSignalingClient? = null
    private var engine: NexuraCallEngine? = null
    private var callId: String? = null
    private var pendingOffer: String? = null
    private var videoMode = false
    private var ready = false
    private var closed = false
    private val queuedIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var offerRetryJob: Job? = null

    fun connect(token: String) {
        signaling?.close()
        signaling = CallSignalingClient(
            "lyhzawblxcvpgpjrkspc",
            com.nexura.messenger.data.NexuraApi.PUBLIC_KEY,
            token,
            ownUserId,
            peerUserId,
            { signal -> scope.launch { receive(signal) } },
            { scope.launch { ready = true; publish(CallUiState(status = "Готово к звонку")) } },
            { message -> scope.launch { publish(CallUiState(status = "Ошибка связи", error = message)) } }
        )
    }

    fun start(video: Boolean) {
        if (!ready || closed) {
            publish(CallUiState(status = "Подключение…", error = "Сервер звонков ещё подключается"))
            return
        }
        videoMode = video
        callId = UUID.randomUUID().toString()
        startKeepAlive(video)
        createEngine(video)
        engine?.createOffer(
            onReady = { sdp ->
                val signal = JSONObject().put("type", "offer").put("call_id", callId)
                    .put("from", ownUserId).put("to", peerUserId).put("video", video).put("sdp", sdp)
                if (!send(signal)) publish(CallUiState(status = "Не удалось вызвать", error = "Сигнал не отправлен"))
                else {
                    publish(CallUiState(status = "Вызов…", active = true, video = video))
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
                }
                flushIce()
            },
            onError = { error -> publish(CallUiState(status = "Ошибка звонка", error = error)) }
        )
    }

    fun accept() {
        val offer = pendingOffer ?: return
        videoMode = pendingVideo
        callId = pendingCallId
        startKeepAlive(videoMode)
        createEngine(videoMode)
        engine?.acceptOffer(
            offer,
            onReady = { sdp ->
                remoteDescriptionSet = true
                val signal = JSONObject().put("type", "answer").put("call_id", callId)
                    .put("from", ownUserId).put("to", peerUserId).put("sdp", sdp)
                if (!send(signal)) publish(CallUiState(status = "Ошибка звонка", error = "Не удалось отправить ответ собеседнику"))
                publish(CallUiState(status = "На связи", active = true, video = videoMode))
            },
            onError = { error -> publish(CallUiState(status = "Ошибка звонка", error = error)) }
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
                offerRetryJob?.cancel()
                engine?.acceptAnswer(
                    signal.optString("sdp"),
                    onReady = {
                        remoteDescriptionSet = true
                        flushIce()
                        publish((lastState ?: CallUiState()).copy(status = "На связи", active = true, video = videoMode))
                    },
                    onError = { error -> publish(CallUiState(status = "Ошибка звонка", error = error)) }
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
                finish(false)
                publish(CallUiState(status = text))
            }
        }
    }

    private val queuedRemoteIce = mutableListOf<Triple<String, String?, Int>>()

    private fun createEngine(video: Boolean) {
        engine?.close()
        engine = NexuraCallEngine(
            context, video,
            onLocalIce = { candidate ->
                if (callId != null && remoteDescriptionSet) {
                    send(JSONObject().put("type", "ice").put("call_id", callId)
                        .put("from", ownUserId).put("to", peerUserId)
                        .put("candidate", JSONObject().put("candidate", candidate.sdp)
                            .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex)))
                } else queuedIce.add(candidate)
            },
            onRemoteVideo = { track -> scope.launch { publish((lastState ?: CallUiState()).copy(remoteVideoTrack = track)) } },
            onLocalVideo = { track -> scope.launch { publish((lastState ?: CallUiState()).copy(localVideoTrack = track)) } },
            onState = { value -> scope.launch { publish(CallUiState(status = value, active = true, video = videoMode)) } }
        )
    }

    private val queuedIce = mutableListOf<IceCandidate>()
    private fun flushIce() {
        queuedIce.forEach { candidate ->
            send(JSONObject().put("type", "ice").put("call_id", callId)
                .put("from", ownUserId).put("to", peerUserId)
                .put("candidate", JSONObject().put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex)))
        }
        queuedIce.clear()
        if (remoteDescriptionSet) {
            queuedRemoteIce.forEach { (sdp, mid, line) -> engine?.addRemoteIce(sdp, mid, line) }
            queuedRemoteIce.clear()
        }
    }

    private fun startKeepAlive(video: Boolean) {
        val intent = Intent(context, CallKeepAliveService::class.java)
            .putExtra(CallKeepAliveService.EXTRA_VIDEO, video)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    private fun send(signal: JSONObject): Boolean = signaling?.sendSignal(signal) == true

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
        runCatching { context.stopService(Intent(context, CallKeepAliveService::class.java)) }
        engine?.close()
        engine = null
        publish((lastState ?: CallUiState()).copy(localVideoTrack = null, remoteVideoTrack = null))
        pendingOffer = null
        pendingCallId = null
        callId = null
        remoteDescriptionSet = false
        videoMode = false
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
