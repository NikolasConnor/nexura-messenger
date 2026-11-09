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
    DisposableEffect(Unit) { onDispose { remoteRenderer?.release(); localRenderer?.release(); eglContext.release() } }
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cameraGranted = grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!audioGranted) {
            ui = ui.copy(error = "Нужен доступ к микрофону для звонка")
            return@rememberLauncherForActivityResult
        }
        if (pendingAction == "video" && !cameraGranted) {
            ui = ui.copy(error = "Нужен доступ к камере для видеозвонка")
            return@rememberLauncherForActivityResult
        }
        when (pendingAction) {
            "video" -> coordinator?.start(true)
            "audio" -> coordinator?.start(false)
            "accept" -> coordinator?.accept()
        }
    }

    LaunchedEffect(chat.id) {
        try {
            val peerId = api.directPeerUserId(chat.id)
            coordinator = CallCoordinator(context, chat.id, ownUserId, peerId) { ui = it }
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
        val permissions = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            else arrayOf(Manifest.permission.RECORD_AUDIO)
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            when (action) {
                "video" -> coordinator?.start(true)
                "audio" -> coordinator?.start(false)
                "accept" -> coordinator?.accept()
            }
        } else permissionLauncher.launch(missing.toTypedArray())
    }

    if (!visible && !ui.incoming && !ui.active && ui.error.isNullOrBlank()) return

    Dialog(
        onDismissRequest = { if (ui.active || ui.incoming) coordinator?.end(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp).background(Color(0xFF171D27), MaterialTheme.shapes.extraLarge).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(if (ui.video) "Видеозвонок" else "Звонок", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (ui.video && ui.active) {
                Box(Modifier.fillMaxWidth().height(360.dp).background(Color.Black, MaterialTheme.shapes.large)) {
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
                    if (ui.localVideoTrack != null) {
                        AndroidView(
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).width(104.dp).height(148.dp),
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
                    }
                }
            } else {
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
                        onClick = { coordinator?.decline(); onDismiss() },
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
                    Button(onClick = { request("audio", false) }, enabled = coordinator != null && !starting) {
                        Icon(Icons.Default.Call, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Позвонить")
                    }
                    Button(onClick = { request("video", true) }, enabled = coordinator != null && !starting) {
                        Icon(Icons.Default.Videocam, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Видео")
                    }
                }
            }
            TextButton(onClick = { coordinator?.end(); onDismiss() }) { Text("Закрыть", color = Color(0xFFA4AFBF)) }
        }
    }
}
