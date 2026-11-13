package com.nexura.messenger.calls

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.renderer.SurfaceViewRenderer
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import io.livekit.android.events.collect
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

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
    val localVideoTrack: VideoTrack? = null,
    val remoteVideoTrack: VideoTrack? = null
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
    private var room: Room? = null
    private var roomEventsJob: Job? = null
    private val pendingRenderers = linkedSetOf<SurfaceViewRenderer>()
    @Volatile private var callId: String? = null
    private var pendingCallId: String? = null
    private var pendingVideo = false
    private var pendingOfferReceivedAtMs: Long? = null
    private var videoMode = false
    private var ready = false
    private var closed = false
    private var accepted = false
    private var endingRoom = false
    private var ringTimeoutJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var connectedAtMs: Long? = null
    private var callVideoForHistory = false
    private var callDirectionOutgoing = false
    private var callHistoryWritten = false
    // A call timer starts only after this device joined the LiveKit room AND the peer is active.
    private var localRoomConnected = false
    private var peerRoomConnected = false
    private var answerSent = false
    private var lastState: CallUiState? = null

    fun connect(token: String) {
        signaling?.close()
        signaling = CallSignalingClient(
            "lyhzawblxcvpgpjrkspc",
            com.nexura.messenger.data.NexuraApi.PUBLIC_KEY,
            token,
            ownUserId,
            peerUserId,
            { signal -> scope.launch { receive(signal) } },
            {
                // OkHttp invokes WebSocket callbacks off the main thread. All coordinator
                // state and Compose UI updates must be serialized on the main dispatcher.
                scope.launch {
                    if (closed) return@launch
                    ready = true
                    val previous = lastState
                    if (previous?.active == true) {
                        publish(previous.copy(status = if (previous.connected) "На связи" else "Соединяем…", error = null))
                    } else {
                        publish(CallUiState(status = "Готово к звонку"))
                    }
                }
            },
            { message ->
                scope.launch {
                    if (closed) return@launch
                    ready = false
                    val previous = lastState ?: CallUiState()
                    publish(previous.copy(
                        status = if (previous.active) "Восстанавливаем связь…" else "Ошибка связи",
                        error = message
                    ))
                }
            }
        )
    }

    /** Rehydrates an incoming offer delivered by FCM after the app was not subscribed to Realtime. */
    fun receiveIncomingPush(id: String, callerId: String, video: Boolean) {
        if (closed || callerId != peerUserId || id.isBlank() || id.length > 64) return
        runCatching { UUID.fromString(id) }.getOrElse { return }
        receive(JSONObject()
            .put("type", "offer")
            .put("call_id", id)
            .put("from", callerId)
            .put("to", ownUserId)
            .put("video", false))
    }

    fun start(video: Boolean) {
        if (!ready || closed || room != null || callId != null) {
            publish((lastState ?: CallUiState()).copy(error = "Сервер звонков ещё подключается"))
            return
        }
        // Nexura currently supports audio calls only.
        videoMode = false
        callVideoForHistory = false
        callDirectionOutgoing = true
        callHistoryWritten = false
        connectedAtMs = null
        accepted = false
        localRoomConnected = false
        peerRoomConnected = false
        answerSent = false
        callId = UUID.randomUUID().toString()
        val expectedId = callId ?: return
        startKeepAlive(false)
        val invite = JSONObject()
            .put("type", "offer")
            .put("call_id", expectedId)
            .put("from", ownUserId)
            .put("to", peerUserId)
            .put("video", false)
            .put("sent_at", System.currentTimeMillis())
        if (!send(invite) { acknowledged, detail ->
                if (!acknowledged && callId == expectedId && !closed) {
                    failCall(
                        "Сервер не принял сигнал вызова",
                        detail ?: "Проверьте подключение к серверу сигнализации."
                    )
                }
            }) {
            failCall("Не удалось вызвать", "Канал сигнализации ещё не готов. Проверьте интернет и повторите попытку.")
            return
        }
        publish(CallUiState(
            status = "Вызов…",
            active = true,
            outgoing = true,
            video = false,
            speaker = false
        ))
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(35_000)
            if (callId == expectedId && !accepted && !closed) {
                send(endSignal(expectedId))
                finish()
                publish(CallUiState(status = "Готово к звонку", error = "Нет ответа"))
            }
        }
        scope.launch {
            runCatching { api.notifyIncomingCall(chatId, peerUserId, expectedId, false) }
        }
    }

    fun accept() {
        val expectedId = pendingCallId ?: return
        if (closed || room != null) return
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        videoMode = false
        callVideoForHistory = false
        callDirectionOutgoing = false
        callHistoryWritten = false
        connectedAtMs = null
        accepted = true
        localRoomConnected = false
        peerRoomConnected = false
        answerSent = false
        callId = expectedId
        pendingCallId = null
        pendingOfferReceivedAtMs = null
        pendingVideo = false
        startKeepAlive(false)
        publish(CallUiState(
            status = "Соединяем…",
            active = true,
            outgoing = false,
            video = false,
            speaker = false
        ))
        armConnectionTimeout(expectedId)
        scope.launch {
            try {
                // A notification action can accept before the Realtime socket finishes joining.
                // Wait briefly instead of dropping the answer signal on a not-yet-ready socket.
                var attempts = 0
                while (!ready && attempts < 80 && callId == expectedId && !closed) {
                    delay(150)
                    attempts++
                }
                if (!ready) throw IOException("Не удалось подключиться к серверу сигнализации")
                joinLiveKit(expectedId, videoMode)
            } catch (error: Exception) {
                if (callId == expectedId && !closed) {
                    failCall("Не удалось подключиться к звонку", error.message ?: "Проверьте интернет и повторите попытку")
                }
            }
        }
    }

    private suspend fun joinLiveKit(expectedId: String, video: Boolean) {
        val credentials = api.liveKitRoomCredentials(chatId, peerUserId)
        if (callId != expectedId || closed) return

        val newRoom = LiveKit.create(context.applicationContext)
        room = newRoom
        pendingRenderers.toList().forEach { renderer ->
            runCatching { newRoom.initVideoRenderer(renderer) }
        }
        pendingRenderers.clear()

        roomEventsJob?.cancel()
        roomEventsJob = scope.launch {
            newRoom.events.collect { event ->
                if (room === newRoom && !closed) {
                    when (event) {
                                    is RoomEvent.TrackSubscribed -> {
                                        if (event.participant.identity?.value == peerUserId) {
                                            val track = event.track as? VideoTrack
                                            if (track != null) publish((lastState ?: CallUiState()).copy(remoteVideoTrack = track))
                                        }
                                    }
                                    is RoomEvent.TrackUnsubscribed -> {
                                        val track = event.track as? VideoTrack
                                        if (track != null && lastState?.remoteVideoTrack === track) {
                                            publish((lastState ?: CallUiState()).copy(remoteVideoTrack = null))
                                        }
                                    }
                                    is RoomEvent.Connected -> {
                                        localRoomConnected = true
                                        peerRoomConnected = newRoom.remoteParticipants.values.any {
                                            it.identity?.value == peerUserId &&
                                                it.state == io.livekit.android.room.participant.Participant.State.ACTIVE
                                        }
                                        if (peerRoomConnected) markConnected()
                                        else publish((lastState ?: CallUiState()).copy(status = "Ожидаем собеседника…", connected = false))
                                    }
                                    is RoomEvent.ParticipantConnected -> {
                                        if (event.participant.identity?.value == peerUserId &&
                                            event.participant.state == io.livekit.android.room.participant.Participant.State.ACTIVE) {
                                            peerRoomConnected = true
                                            if (localRoomConnected) markConnected()
                                        }
                                    }
                                    is RoomEvent.ParticipantStateChanged -> {
                                        if (event.participant.identity?.value == peerUserId) {
                                            peerRoomConnected = event.newState == io.livekit.android.room.participant.Participant.State.ACTIVE
                                            if (peerRoomConnected && localRoomConnected) markConnected()
                                            else if (!peerRoomConnected) publish((lastState ?: CallUiState()).copy(status = "Восстанавливаем связь…", connected = false))
                                        }
                                    }
                                    is RoomEvent.ParticipantDisconnected -> {
                                        if (event.participant.identity?.value == peerUserId && lastState?.active == true) {
                                            finish()
                                            publish(CallUiState(status = "Звонок завершён", error = "Собеседник отключился"))
                                        }
                                    }
                                    is RoomEvent.Reconnecting -> {
                                        publish((lastState ?: CallUiState()).copy(status = "Восстанавливаем связь…"))
                                    }
                                    is RoomEvent.Reconnected -> {
                                        peerRoomConnected = newRoom.remoteParticipants.values.any {
                                it.identity?.value == peerUserId &&
                                    it.state == io.livekit.android.room.participant.Participant.State.ACTIVE
                            }
                                        if (localRoomConnected && peerRoomConnected) {
                                            markConnected()
                                        } else {
                                            publish((lastState ?: CallUiState()).copy(status = "Ожидаем собеседника…", connected = false))
                                        }
                                    }
                                    is RoomEvent.Disconnected -> {
                                        if (!endingRoom && lastState?.active == true) {
                                            failCall("Связь с LiveKit прервана", event.error?.message ?: "Соединение закрыто")
                                        }
                                    }
                                    is RoomEvent.FailedToConnect -> {
                                        if (lastState?.active == true) {
                                            failCall("Не удалось подключиться к LiveKit", event.error.message ?: "Ошибка соединения")
                                        }
                                    }
                        else -> Unit
                    }
                }
            }
        }

        newRoom.connect(credentials.serverUrl, credentials.token)
        if (callId != expectedId || closed) return
        // connect() returns only after this participant has joined the LiveKit room.
        localRoomConnected = true
        if (!newRoom.localParticipant.setMicrophoneEnabled(true)) {
            throw IOException("LiveKit не смог включить микрофон")
        }
        if (video) {
            if (!newRoom.localParticipant.setCameraEnabled(true)) {
                throw IOException("LiveKit не смог включить камеру")
            }
            val localTrack = newRoom.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
            publish((lastState ?: CallUiState()).copy(localVideoTrack = localTrack))
        }
        if (!callDirectionOutgoing && !answerSent) {
            if (!send(
                    JSONObject()
                        .put("type", "answer")
                        .put("call_id", expectedId)
                        .put("from", ownUserId)
                        .put("to", peerUserId)
                ) { acknowledged, detail ->
                    if (!acknowledged && callId == expectedId && !closed) {
                        failCall(
                            "Ответ не доставлен собеседнику",
                            detail ?: "Сервер сигнализации не подтвердил ответ."
                        )
                    }
                }) {
                throw IOException("Не удалось отправить подтверждение вызова")
            }
            answerSent = true
        }
        if (newRoom.remoteParticipants.values.any { it.identity?.value == peerUserId }) {
            markConnected()
        } else if (accepted) {
            publish((lastState ?: CallUiState()).copy(status = "Ожидаем собеседника…"))
        }
    }

    private fun receive(signal: JSONObject) {
        // Only the other participant in this direct chat may control this call.
        if (signal.optString("to") != ownUserId) return
        if (signal.optString("from") != peerUserId) return
        val id = signal.optString("call_id")
        if (id.isBlank()) return
        when (signal.optString("type")) {
            "offer" -> {
                val now = System.currentTimeMillis()
                val sentAt = signal.optLong("sent_at", now)
                val receivedAt = if (sentAt in 1L..now) sentAt else now
                if (now - receivedAt > CALL_RING_TIMEOUT_MS) {
                    send(endSignal(id))
                    return
                }
                if (id.isBlank() || id == callId || id == pendingCallId) return
                if (room != null || callId != null || pendingCallId != null || lastState?.active == true) {
                    send(JSONObject().put("type", "busy").put("call_id", id).put("from", ownUserId).put("to", peerUserId))
                    return
                }
                pendingCallId = id
                pendingOfferReceivedAtMs = receivedAt
                // Legacy incoming video offers are accepted as audio-only calls.
                pendingVideo = false
                publish(CallUiState(
                    status = "Входящий аудиозвонок",
                    incoming = true,
                    video = false
                ))
                // Do not leave an unanswered incoming call dialog ringing indefinitely.
                ringTimeoutJob?.cancel()
                ringTimeoutJob = scope.launch {
                    delay(35_000)
                    if (pendingCallId == id && !closed) {
                        send(endSignal(id))
                        finish()
                    }
                }
            }
            "answer" -> if (id == callId && !accepted) {
                accepted = true
                ringTimeoutJob?.cancel()
                ringTimeoutJob = null
                armConnectionTimeout(id)
                publish((lastState ?: CallUiState()).copy(status = "Соединяем…", incoming = false, active = true))
                scope.launch {
                    try {
                        joinLiveKit(id, videoMode)
                    } catch (error: Exception) {
                        if (callId == id && !closed) {
                            failCall("Не удалось подключиться к LiveKit", error.message ?: "Проверьте настройки LiveKit")
                        }
                    }
                }
            }
            "end", "decline", "busy" -> if (id == callId || id == pendingCallId) {
                val message = when (signal.optString("type")) {
                    "busy" -> "Абонент занят"
                    "decline" -> "Вызов отклонён"
                    else -> "Звонок завершён"
                }
                val wasOutgoing = callDirectionOutgoing
                finish()
                publish(CallUiState(status = "Готово к звонку", outgoing = wasOutgoing, error = message))
            }
        }
    }

    /** Re-check an unanswered offer after the device wakes from its lock screen. */
    fun onAppForeground() {
        val id = pendingCallId ?: return
        val receivedAt = pendingOfferReceivedAtMs ?: return
        if (System.currentTimeMillis() - receivedAt >= CALL_RING_TIMEOUT_MS) {
            send(endSignal(id))
            finish()
        }
    }

    fun initVideoRenderer(renderer: SurfaceViewRenderer) {
        val activeRoom = room
        if (activeRoom != null) {
            runCatching { activeRoom.initVideoRenderer(renderer) }
                .onFailure { publish((lastState ?: CallUiState()).copy(error = "Не удалось подготовить видеоплеер: ${it.message}")) }
        } else {
            pendingRenderers.add(renderer)
        }
    }

    private fun markConnected() {
        if (callId == null || closed || lastState?.connected == true || !localRoomConnected || !peerRoomConnected) return
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        if (connectedAtMs == null) connectedAtMs = System.currentTimeMillis()
        publish((lastState ?: CallUiState()).copy(
            status = "На связи",
            active = true,
            incoming = false,
            connected = true,
            error = null
        ))
    }

    private fun armConnectionTimeout(expectedId: String) {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(40_000)
            if (callId == expectedId && lastState?.connected != true && !closed) {
                failCall("Не удалось установить соединение", "Проверьте интернет и повторите попытку.")
            }
        }
    }

    private fun startKeepAlive(video: Boolean) {
        val intent = Intent(context, CallKeepAliveService::class.java)
            
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    private fun send(
        signal: JSONObject,
        onAcknowledged: ((Boolean, String?) -> Unit)? = null
    ): Boolean {
        val mainThreadCallback: ((Boolean, String?) -> Unit)? =
            onAcknowledged?.let { callback ->
                { accepted: Boolean, detail: String? ->
                    scope.launch {
                        if (!closed) callback(accepted, detail)
                    }
                    Unit
                }
            }
        return signaling?.sendSignal(signal, mainThreadCallback) == true
    }

    private fun endSignal(id: String) = JSONObject()
        .put("type", "end").put("call_id", id).put("from", ownUserId).put("to", peerUserId)

    fun toggleSpeaker(): Boolean {
        val activeRoom = room ?: return !(lastState?.speaker ?: videoMode)
        val devices = activeRoom.audioSwitchHandler?.availableAudioDevices.orEmpty()
        val enabled = !(lastState?.speaker ?: videoMode)
        val desired = if (enabled) "Speakerphone" else "Earpiece"
        val target = devices.firstOrNull { it.javaClass.simpleName.contains(desired, ignoreCase = true) }
        if (target != null) activeRoom.audioSwitchHandler?.selectDevice(target)
        publish((lastState ?: CallUiState()).copy(speaker = enabled))
        return enabled
    }

    fun toggleMute(): Boolean {
        val muted = !(lastState?.muted ?: false)
        val activeRoom = room
        if (activeRoom != null) scope.launch {
            runCatching { activeRoom.localParticipant.setMicrophoneEnabled(!muted) }
                .onFailure { publish((lastState ?: CallUiState()).copy(error = "Не удалось изменить микрофон: ${it.message}")) }
        }
        publish((lastState ?: CallUiState()).copy(muted = muted))
        return muted
    }

    fun toggleCamera(): Boolean {
        val enabled = !(lastState?.cameraEnabled ?: true)
        val activeRoom = room
        if (activeRoom != null) scope.launch {
            runCatching {
                activeRoom.localParticipant.setCameraEnabled(enabled)
                val track = activeRoom.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
                publish((lastState ?: CallUiState()).copy(cameraEnabled = enabled, localVideoTrack = track))
            }.onFailure {
                publish((lastState ?: CallUiState()).copy(error = "Не удалось изменить камеру: ${it.message}"))
            }
        }
        publish((lastState ?: CallUiState()).copy(cameraEnabled = enabled))
        return enabled
    }

    fun switchCamera() {
        val track = room?.localParticipant?.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
        track?.switchCamera()
    }

    private fun publish(state: CallUiState) {
        lastState = state
        onState(state)
    }

    fun decline() {
        val id = pendingCallId ?: callId
        if (id != null) send(JSONObject().put("type", "decline").put("call_id", id).put("from", ownUserId).put("to", peerUserId))
        finish()
    }

    fun end() {
        val id = callId ?: pendingCallId
        if (id != null) send(endSignal(id))
        finish()
    }

    private fun failCall(status: String, message: String) {
        val wasOutgoing = callDirectionOutgoing
        finish()
        publish(CallUiState(
            status = if (ready) "Готово к звонку" else "Подключение…",
            outgoing = wasOutgoing,
            error = "$status: $message"
        ))
    }

    private fun finish() {
        offerCleanup()
        endingRoom = true
        val activeRoom = room
        room = null
        activeRoom?.disconnect()
        activeRoom?.release()
        endingRoom = false
        pendingRenderers.clear()
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
        localRoomConnected = false
        peerRoomConnected = false
        callId = null
        pendingCallId = null
        pendingOfferReceivedAtMs = null
        pendingVideo = false
        accepted = false
        videoMode = false
        callVideoForHistory = false
        callDirectionOutgoing = false
        publish(CallUiState(status = if (ready) "Готово к звонку" else "Подключение…", outgoing = wasOutgoing))
    }

    private fun offerCleanup() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        roomEventsJob?.cancel()
        roomEventsJob = null
        runCatching { context.stopService(Intent(context, CallKeepAliveService::class.java)) }
    }

    companion object {
        private const val CALL_RING_TIMEOUT_MS = 35_000L
    }

    fun close() {
        if (closed) return
        closed = true
        finish()
        ready = false
        signaling?.close()
        signaling = null
        scope.cancel()
    }
}
