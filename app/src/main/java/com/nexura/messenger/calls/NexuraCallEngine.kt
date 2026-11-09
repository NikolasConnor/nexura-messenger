package com.nexura.messenger.calls

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.nexura.messenger.BuildConfig
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

class NexuraCallEngine(
    context: Context,
    private val videoEnabled: Boolean,
    private val onLocalIce: (IceCandidate) -> Unit,
    private val onRemoteVideo: (VideoTrack?) -> Unit,
    private val onLocalVideo: (VideoTrack?) -> Unit,
    private val onState: (String) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val previousAudioMode = audioManager.mode
    private val previousSpeakerState = audioManager.isSpeakerphoneOn
    private val egl = EglBase.create()
    fun sharedEglContext(): EglBase.Context = egl.eglBaseContext
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private val closed = AtomicBoolean(false)

    init {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = videoEnabled
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()

        val iceServers = buildList {
            add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())
            val turnUrls = BuildConfig.WEBRTC_TURN_URLS.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val turnUser = BuildConfig.WEBRTC_TURN_USERNAME
            val turnCredential = BuildConfig.WEBRTC_TURN_CREDENTIAL
            if (turnUrls.isNotEmpty() && turnUser.isNotBlank() && turnCredential.isNotBlank()) {
                add(
                    PeerConnection.IceServer.builder(turnUrls)
                        .setUsername(turnUser)
                        .setPassword(turnCredential)
                        .createIceServer()
                )
            }
        }
        peer = factory.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            },
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    onState(when (state) {
                        PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> "Подключено"
                        PeerConnection.IceConnectionState.CHECKING -> "Соединяем…"
                        PeerConnection.IceConnectionState.DISCONNECTED -> "Связь прервана"
                        PeerConnection.IceConnectionState.FAILED -> "Не удалось соединиться"
                        PeerConnection.IceConnectionState.CLOSED -> "Звонок завершён"
                        else -> "Ожидание соединения"
                    })
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidate(candidate: IceCandidate) { onLocalIce(candidate) }
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onAddStream(stream: org.webrtc.MediaStream) {}
                override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
                override fun onDataChannel(channel: org.webrtc.DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {}
                override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                    val track = transceiver.receiver.track()
                    if (track is VideoTrack) { track.setEnabled(true); onRemoteVideo(track) }
                }
            }
        ) ?: error("Не удалось создать WebRTC соединение")

        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("nexura_audio", audioSource)
        peer.addTrack(audioTrack, listOf("nexura_stream"))

        if (videoEnabled) {
            val enumerator = Camera2Enumerator(context)
            val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
                ?: error("Камера не найдена")
            capturer = enumerator.createCapturer(cameraName, null)
                ?: error("Не удалось открыть камеру")
            textureHelper = SurfaceTextureHelper.create("NexuraCamera", egl.eglBaseContext)
            videoSource = factory.createVideoSource(false)
            capturer!!.initialize(textureHelper, context, videoSource!!.capturerObserver)
            capturer!!.startCapture(960, 540, 24)
            videoTrack = factory.createVideoTrack("nexura_video", videoSource)
            onLocalVideo(videoTrack)
            peer.addTrack(videoTrack, listOf("nexura_stream"))
        }
    }

    fun createOffer(onReady: (String) -> Unit, onError: (String) -> Unit) {
        peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peer.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() { onReady(desc.description) }
                    override fun onSetFailure(error: String) { onError(error) }
                    override fun onCreateSuccess(desc: SessionDescription) {}
                    override fun onCreateFailure(error: String) { onError(error) }
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) { onError(error) }
            override fun onSetFailure(error: String) { onError(error) }
        }, MediaConstraints())
    }

    fun acceptOffer(sdp: String, onReady: (String) -> Unit, onError: (String) -> Unit) {
        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peer.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                peer.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        peer.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() { onReady(desc.description) }
                            override fun onSetFailure(error: String) { onError(error) }
                            override fun onCreateSuccess(desc: SessionDescription) {}
                            override fun onCreateFailure(error: String) { onError(error) }
                        }, desc)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String) { onError(error) }
                    override fun onSetFailure(error: String) { onError(error) }
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String) { onError(error) }
            override fun onCreateSuccess(desc: SessionDescription) {}
            override fun onCreateFailure(error: String) { onError(error) }
        }, offer)
    }

    fun acceptAnswer(sdp: String, onReady: () -> Unit, onError: (String) -> Unit) {
        peer.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { onReady() }
            override fun onSetFailure(error: String) { onError(error) }
            override fun onCreateSuccess(desc: SessionDescription) {}
            override fun onCreateFailure(error: String) { onError(error) }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun addRemoteIce(sdp: String, mid: String?, line: Int) {
        peer.addIceCandidate(IceCandidate(mid, line, sdp))
    }

    fun setMuted(muted: Boolean) { audioTrack.setEnabled(!muted) }
    fun setSpeakerEnabled(enabled: Boolean) { audioManager.isSpeakerphoneOn = enabled }
    fun setCameraEnabled(enabled: Boolean) { videoTrack?.setEnabled(enabled) }
    fun switchCamera() { capturer?.switchCamera(null) }
    fun localVideoTrack(): VideoTrack? = videoTrack

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { textureHelper?.dispose() }
        runCatching { peer.close(); peer.dispose() }
        runCatching { audioTrack.dispose(); audioSource.dispose() }
        runCatching { videoTrack?.dispose(); videoSource?.dispose() }
        runCatching { audioManager.isSpeakerphoneOn = previousSpeakerState; audioManager.mode = previousAudioMode }
        runCatching { factory.dispose(); egl.release() }
    }
}
