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
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import com.nexura.messenger.data.ChatItem
import com.nexura.messenger.data.NexuraApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.os.SystemClock

@Composable
fun NexuraCallDialog(
    api: NexuraApi,
    chat: ChatItem,
    ownUserId: String,
    accessToken: String,
    visible: Boolean,
    autoAction: String = "show",
    onCallFinished: (Long, Boolean, Boolean, String) -> Unit = { _, _, _, _ -> },
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var coordinator by remember { mutableStateOf<CallCoordinator?>(null) }
    var ui by remember { mutableStateOf(CallUiState()) }
    var callSeconds by remember { mutableStateOf(0L) }
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
            coordinator = CallCoordinator(context, chat.id, ownUserId, peerId, api, { ui = it }, onCallFinished)
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
    LaunchedEffect(ui.incoming, autoAction, ui.status, visible) {
        if (!visible || autoActionHandled) return@LaunchedEffect
        when (autoAction) {
            "accept" -> if (ui.incoming) {
                autoActionHandled = true
                request("accept", ui.video)
            }
            "decline" -> if (ui.incoming) {
                autoActionHandled = true
                coordinator?.decline()
                com.nexura.messenger.push.NexuraMessagingService.cancelIncomingCall(context, chat.id)
                onDismiss()
            }
            "start_audio" -> if (ui.status == "Готово к звонку") {
                autoActionHandled = true
                request("audio", false)
            }
            "start_video" -> if (ui.status == "Готово к звонку") {
                autoActionHandled = true
                request("video", true)
            }
        }
    }

    LaunchedEffect(ui.connected) {
        if (!ui.connected) {
            callSeconds = 0L
        } else {
            val startedAt = SystemClock.elapsedRealtime()
            while (ui.connected) {
                callSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000L
                delay(1000L)
            }
        }
    }

    LaunchedEffect(ui.active) {
        if (ui.active) {
            com.nexura.messenger.push.NexuraMessagingService.cancelIncomingCall(context, chat.id)
        }
    }
    LaunchedEffect(ui.incoming, ui.active, visible) {
        if (!ui.incoming && !ui.active && !visible) autoActionHandled = false
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
                                renderer.init(coordinator?.sharedEglContext() ?: eglContext.eglBaseContext, null)
                                renderer.setEnableHardwareScaler(true)
                                renderer.setMirror(false)
                                renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
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
                                    renderer.init(coordinator?.sharedEglContext() ?: eglContext.eglBaseContext, null)
                                    renderer.setEnableHardwareScaler(true)
                                    renderer.setMirror(true)
                                renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
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
                val avatarUrl = chat.avatar.takeIf {
                    it.startsWith("https://") || it.startsWith("http://")
                }
                Box(
                    Modifier.size(112.dp).background(Color(0xFF28364A), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarUrl != null) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = "Аватар ${chat.title}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(chat.avatar.takeIf { it.isNotBlank() && it != "null" } ?: "🙂", fontSize = 48.sp)
                    }
                }
            }
            Text(chat.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (ui.connected) String.format("%02d:%02d", callSeconds / 60, callSeconds % 60) else ui.status,
                color = if (ui.connected) Color(0xFF72D6A2) else Color(0xFFA4AFBF),
                fontSize = if (ui.connected) 18.sp else 14.sp,
                fontWeight = if (ui.connected) FontWeight.SemiBold else FontWeight.Normal
            )
            if (!ui.error.isNullOrBlank()) Text(ui.error!!, color = Color(0xFFFF858D), fontSize = 12.sp)
            if (ui.incoming) {
                Row(horizontalArrangement = Arrangement.spacedBy(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = {
                                coordinator?.decline()
                                com.nexura.messenger.push.NexuraMessagingService.cancelIncomingCall(context, chat.id)
                                onDismiss()
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFB83E4A)),
                            modifier = Modifier.size(68.dp)
                        ) { Icon(Icons.Default.CallEnd, "Отклонить", tint = Color.White) }
                        Text("Отклонить", color = Color.White, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = { request("accept", ui.video) },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF249B68)),
                            modifier = Modifier.size(68.dp)
                        ) { Icon(if (ui.video) Icons.Default.Videocam else Icons.Default.Call, "Ответить", tint = Color.White) }
                        Text("Ответить", color = Color.White, fontSize = 12.sp)
                    }
                }
            } else if (ui.active && ui.connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CallControlButton(label = if (ui.muted) "Звук выкл." else "Микрофон", icon = if (ui.muted) Icons.Default.MicOff else Icons.Default.Mic, selected = ui.muted, onClick = { ui = ui.copy(muted = coordinator?.toggleMute() ?: !ui.muted) })
                    CallControlButton(label = if (ui.speaker) "Динамик вкл." else "Динамик", icon = if (ui.speaker) Icons.Default.VolumeUp else Icons.Default.VolumeOff, selected = ui.speaker, onClick = { ui = ui.copy(speaker = coordinator?.toggleSpeaker() ?: !ui.speaker) })
                    if (ui.video) {
                        CallControlButton(label = if (ui.cameraEnabled) "Камера" else "Камера выкл.", icon = Icons.Default.CameraAlt, selected = !ui.cameraEnabled, onClick = { ui = ui.copy(cameraEnabled = coordinator?.toggleCamera() ?: !ui.cameraEnabled) })
                        CallControlButton(label = "Сменить", icon = Icons.Default.Cameraswitch, onClick = { coordinator?.switchCamera() })
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = {
                                coordinator?.end()
                                com.nexura.messenger.push.NexuraMessagingService.cancelIncomingCall(context, chat.id)
                                onDismiss()
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFB83E4A)),
                            modifier = Modifier.size(58.dp)
                        ) { Icon(Icons.Default.CallEnd, "Завершить", tint = Color.White) }
                        Text("Завершить", color = Color.White, fontSize = 11.sp)
                    }
                }
            } else if (ui.active) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Соединяем звонок…", color = Color.White, fontSize = 14.sp)
                    FilledIconButton(
                        onClick = { coordinator?.end(); onDismiss() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFB83E4A)),
                        modifier = Modifier.size(58.dp)
                    ) { Icon(Icons.Default.CallEnd, "Отменить вызов", tint = Color.White) }
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
        }
    }
}


@Composable
private fun CallControlButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(54.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (selected) Color(0xFF365B83) else Color(0xFF263242),
                contentColor = Color.White
            )
        ) { Icon(icon, contentDescription = label) }
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1)
    }
}
