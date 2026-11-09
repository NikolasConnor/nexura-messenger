package com.nexura.messenger.calls

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import com.nexura.messenger.data.ChatItem
import com.nexura.messenger.data.NexuraApi
import kotlinx.coroutines.launch

@Composable
fun NexuraCallDialog(
    api: NexuraApi,
    chat: ChatItem,
    ownUserId: String,
    accessToken: String,
    visible: Boolean,
    autoAction: String = "show",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var coordinator by remember { mutableStateOf<CallCoordinator?>(null) }
    var ui by remember { mutableStateOf(CallUiState()) }
    var pendingAction by remember { mutableStateOf("audio") }
    var starting by remember { mutableStateOf(false) }
    val eglContext = remember { EglBase.create() }
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val latestRemoteRenderer by rememberUpdatedState(remoteRenderer)
    val latestLocalRenderer by rememberUpdatedState(localRenderer)
    DisposableEffect(Unit) {
        onDispose {
            latestRemoteRenderer?.release()
            latestLocalRenderer?.release()
            eglContext.release()
        }
    }
    DisposableEffect(ui.remoteVideoTrack, remoteRenderer) {
        val track = ui.remoteVideoTrack
        val renderer = remoteRenderer
        if (track != null && renderer != null) track.addSink(renderer)
        onDispose { if (track != null && renderer != null) track.removeSink(renderer) }
    }
    DisposableEffect(ui.localVideoTrack, localRenderer) {
        val track = ui.localVideoTrack
        val renderer = localRenderer
        if (track != null && renderer != null) track.addSink(renderer)
        onDispose { if (track != null && renderer != null) track.removeSink(renderer) }
    }

    var permissionStep by remember { mutableStateOf("") }
    fun continueCallAfterPermissions() {
        ui = ui.copy(error = null)
        when (pendingAction) {
            "video" -> coordinator?.start(true)
            "audio" -> coordinator?.start(false)
            "accept" -> coordinator?.accept()
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionStep = ""
        if (granted) continueCallAfterPermissions()
        else ui = ui.copy(error = "Без доступа к камере видеозвонок не запустится")
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            permissionStep = ""
            ui = ui.copy(error = "Без доступа к микрофону звонок невозможен")
        } else if (pendingAction == "video" &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionStep = Manifest.permission.CAMERA
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            permissionStep = ""
            continueCallAfterPermissions()
        }
    }

    LaunchedEffect(chat.id) {
        try {
            val peerId = api.directPeerUserId(chat.id)
            coordinator = CallCoordinator(context, chat.id, ownUserId, peerId, api) { ui = it }
            coordinator?.connect(accessToken)
        } catch (e: Exception) {
            ui = ui.copy(status = "Не удалось подготовить звонок", error = e.message)
        }
    }
    DisposableEffect(chat.id) {
        onDispose { coordinator?.close() }
    }

    fun request(action: String, video: Boolean) {
        pendingAction = action
        val microphoneMissing = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED
        val cameraMissing = video && ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) != PackageManager.PERMISSION_GRANTED
        when {
            microphoneMissing -> {
                permissionStep = Manifest.permission.RECORD_AUDIO
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            cameraMissing -> {
                permissionStep = Manifest.permission.CAMERA
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> continueCallAfterPermissions()
        }
    }

    var autoActionHandled by remember { mutableStateOf(false) }
    LaunchedEffect(ui.incoming, autoAction) {
        if (ui.incoming && !autoActionHandled && autoAction != "show") {
            autoActionHandled = true
            when (autoAction) {
                "accept" -> request("accept", ui.video)
                "decline" -> {
                    coordinator?.decline()
                    com.nexura.messenger.push.NexuraMessagingService.cancelIncomingCall(context, chat.id)
                    onDismiss()
                }
            }
        }
    }
    LaunchedEffect(ui.active) {
        if (ui.active) {
            com.nexura.messenger.push.NexuraMessagingService.cancelIncomingCall(context, chat.id)
        }
    }
    LaunchedEffect(ui.incoming, ui.active) {
        if (!ui.incoming && !ui.active) autoActionHandled = false
    }

    if (!visible && !ui.incoming && !ui.active && ui.error.isNullOrBlank()) return

    Dialog(
        onDismissRequest = { if (ui.active || ui.incoming) coordinator?.end(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF0D1118)).padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(if (ui.video) "Видеозвонок" else "Аудиозвонок", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (ui.video && ui.active) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).background(Color.Black, MaterialTheme.shapes.extraLarge)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            SurfaceViewRenderer(viewContext).also { renderer ->
                                renderer.init(eglContext.eglBaseContext, null)
                                renderer.setEnableHardwareScaler(true)
                                renderer.setMirror(false)
                                remoteRenderer = renderer
                            }
                        },
                        update = { }
                    )
                    if (ui.remoteVideoTrack == null) {
                        Text(
                            "Ожидаем видео собеседника…",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(12.dp)
                            .width(104.dp).height(148.dp)
                            .background(Color(0xFF242D3A), MaterialTheme.shapes.medium)
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { viewContext ->
                                SurfaceViewRenderer(viewContext).also { renderer ->
                                    renderer.init(eglContext.eglBaseContext, null)
                                    renderer.setEnableHardwareScaler(true)
                                    renderer.setMirror(true)
                                    localRenderer = renderer
                                }
                            },
                            update = { }
                        )
                        if (ui.localVideoTrack == null) {
                            Text("Камера", color = Color.White, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(112.dp).background(Color(0xFF28364A), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(chat.avatar.ifBlank { "🙂" }, fontSize = 48.sp)
                }
            }
            Text(chat.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(ui.status, color = Color(0xFFA4AFBF), fontSize = 14.sp)
            if (!ui.error.isNullOrBlank()) Text(ui.error!!, color = Color(0xFFFF858D), fontSize = 12.sp)
            if (ui.incoming) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(
                        onClick = {
                            coordinator?.decline()
                            com.nexura.messenger.push.NexuraMessagingService.cancelIncomingCall(context, chat.id)
                            onDismiss()
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFB83E4A)),
                        modifier = Modifier.size(60.dp)
                    ) { Icon(Icons.Default.CallEnd, "Отклонить", tint = Color.White) }
                    FilledIconButton(
                        onClick = { request("accept", ui.video) },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF2F9D68)),
                        modifier = Modifier.size(60.dp)
                    ) { Icon(if (ui.video) Icons.Default.Videocam else Icons.Default.Call, "Принять", tint = Color.White) }
                }
            } else if (ui.active) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = { coordinator?.toggleSpeaker(); ui = ui.copy(speaker = !ui.speaker) }) {
                        Icon(if (ui.speaker) Icons.Default.VolumeUp else Icons.Default.VolumeOff, "Громкая связь")
                    }
                    FilledIconButton(onClick = { coordinator?.toggleMute(); ui = ui.copy(muted = !ui.muted) }) {
                        Icon(if (ui.muted) Icons.Default.MicOff else Icons.Default.Mic, "Микрофон")
                    }
                    if (ui.video) {
                        FilledIconButton(onClick = { coordinator?.toggleCamera(); ui = ui.copy(cameraEnabled = !ui.cameraEnabled) }) {
                            Icon(Icons.Default.CameraAlt, "Камера")
                        }
                        FilledIconButton(onClick = { coordinator?.switchCamera() }) {
                            Icon(Icons.Default.Cameraswitch, "Сменить камеру")
                        }
                    }
                    FilledIconButton(
                        onClick = { coordinator?.end(); onDismiss() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFB83E4A)),
                        modifier = Modifier.size(58.dp)
                    ) { Icon(Icons.Default.CallEnd, "Завершить", tint = Color.White) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { request("audio", false) }, enabled = coordinator != null && ui.status == "Готово к звонку" && !starting) {
                        Icon(Icons.Default.Call, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Позвонить")
                    }
                    Button(onClick = { request("video", true) }, enabled = coordinator != null && ui.status == "Готово к звонку" && !starting) {
                        Icon(Icons.Default.Videocam, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Видео")
                    }
                }
            }
            Spacer(Modifier.weight(1f, fill = false))
            TextButton(onClick = { coordinator?.end(); onDismiss() }) { Text("Закрыть", color = Color(0xFFA4AFBF)) }
        }
    }
}
