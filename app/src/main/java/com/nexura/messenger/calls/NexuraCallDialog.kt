package com.nexura.messenger.calls

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.nexura.messenger.data.ChatItem
import com.nexura.messenger.data.NexuraApi
import kotlinx.coroutines.delay

@Composable
fun NexuraCallDialog(
    api: NexuraApi,
    chat: ChatItem,
    ownUserId: String,
    accessToken: String,
    visible: Boolean,
    autoAction: String = "show",
    incomingCallId: String? = null,
    incomingCallerId: String? = null,
    incomingVideo: Boolean = false,
    onCallFinished: (Long, Boolean, Boolean, String) -> Unit = { _, _, _, _ -> },
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    var coordinator by remember { mutableStateOf<CallCoordinator?>(null) }
    var ui by remember { mutableStateOf(CallUiState()) }
    var callSeconds by remember { mutableStateOf(0L) }
    var callStartedAt by remember { mutableStateOf<Long?>(null) }
    var pendingAction by remember { mutableStateOf("audio") }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) continueAudioCall()
        else ui = ui.copy(error = "Без доступа к микрофону аудиозвонок невозможен")
    }

    fun requestAudio(action: String) {
        pendingAction = action
        val microphoneMissing = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED
        if (microphoneMissing) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            continueAudioCall()
        }
    }

    fun continueAudioCall() {
        ui = ui.copy(error = null)
        when (pendingAction) {
            "accept" -> coordinator?.accept()
            else -> coordinator?.start(false)
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
    DisposableEffect(lifecycle, coordinator) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) coordinator?.onAppForeground()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(coordinator, incomingCallId, incomingCallerId) {
        if (!incomingCallId.isNullOrBlank() && !incomingCallerId.isNullOrBlank()) {
            // Older push payloads may contain a video flag; it is deliberately ignored.
            coordinator?.receiveIncomingPush(incomingCallId, incomingCallerId, false)
        }
    }

    var autoActionHandled by remember { mutableStateOf(false) }
    LaunchedEffect(ui.incoming, autoAction, ui.status, visible) {
        if (!visible || autoActionHandled) return@LaunchedEffect
        when (autoAction) {
            "accept" -> if (ui.incoming) {
                autoActionHandled = true
                requestAudio("accept")
            }
            "decline" -> if (ui.incoming) {
                autoActionHandled = true
                coordinator?.decline()
                NexuraMessagingService.cancelIncomingCall(context, chat.id)
                onDismiss()
            }
            "start_audio", "start_video" -> if (ui.status == "Готово к звонку") {
                autoActionHandled = true
                requestAudio("audio")
            }
        }
    }

    LaunchedEffect(ui.active, ui.connected) {
        if (!ui.active) {
            callStartedAt = null
            callSeconds = 0L
        } else {
            if (ui.connected && callStartedAt == null) callStartedAt = SystemClock.elapsedRealtime()
            while (ui.active) {
                callStartedAt?.let { startedAt ->
                    callSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1000L
                }
                delay(1000L)
            }
        }
    }

    LaunchedEffect(ui.active) {
        if (ui.active) NexuraMessagingService.cancelIncomingCall(context, chat.id)
    }
    LaunchedEffect(ui.incoming, ui.active, visible) {
        if (!ui.incoming && !ui.active && !visible) autoActionHandled = false
    }

    if (!visible && !ui.incoming && !ui.active) return

    Dialog(
        onDismissRequest = { if (ui.active || ui.incoming) coordinator?.end(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0xFF182538), Color(0xFF0D1118), Color(0xFF090D14))
                    )
                )
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Аудиозвонок", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (ui.incoming) "Входящий вызов"
                        else if (ui.active) "Защищённое аудиосоединение"
                        else "Личный звонок Nexura",
                        color = Color(0xFFA4AFBF), fontSize = 12.sp
                    )
                }
                Box(
                    Modifier.background(Color(0xFF263B50), androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .padding(horizontal = 11.dp, vertical = 7.dp)
                ) {
                    Text("NEXURA", color = Color(0xFFB8D6FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                IconButton(onClick = {
                    if (ui.incoming || ui.active) coordinator?.end()
                    NexuraMessagingService.cancelIncomingCall(context, chat.id)
                    onDismiss()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть экран звонка", tint = Color.White)
                }
            }

            Spacer(Modifier.weight(1f))
            val avatarUrl = chat.avatar.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            Box(
                Modifier.size(124.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(Color(0xFF5279A8), Color(0xFF28364A))
                        ), CircleShape
                    )
                    .padding(5.dp)
                    .background(Color(0xFF182538), CircleShape),
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
            Text(chat.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier.background(
                    if (ui.connected) Color(0xFF173B32) else Color(0xFF202B3A),
                    androidx.compose.foundation.shape.RoundedCornerShape(50)
                ).padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(
                    if (ui.connected) String.format("%02d:%02d", callSeconds / 60, callSeconds % 60) else ui.status,
                    color = if (ui.connected) Color(0xFF8DE0B5) else Color(0xFFD0D9E6),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
            }
            if (!ui.error.isNullOrBlank()) {
                Text(ui.error!!, color = Color(0xFFFF858D), fontSize = 12.sp)
            }

            if (ui.incoming) {
                Row(horizontalArrangement = Arrangement.spacedBy(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = {
                                coordinator?.decline()
                                NexuraMessagingService.cancelIncomingCall(context, chat.id)
                                onDismiss()
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFB83E4A)),
                            modifier = Modifier.size(68.dp)
                        ) { Icon(Icons.Default.CallEnd, "Отклонить", tint = Color.White) }
                        Text("Отклонить", color = Color.White, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = { requestAudio("accept") },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF249B68)),
                            modifier = Modifier.size(68.dp)
                        ) { Icon(Icons.Default.Call, "Ответить", tint = Color.White) }
                        Text("Ответить", color = Color.White, fontSize = 12.sp)
                    }
                }
            } else if (ui.active && ui.connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    CallControlButton(
                        label = if (ui.muted) "Микрофон выкл." else "Микрофон",
                        icon = if (ui.muted) Icons.Default.MicOff else Icons.Default.Mic,
                        selected = ui.muted,
                        onClick = { ui = ui.copy(muted = coordinator?.toggleMute() ?: !ui.muted) }
                    )
                    CallControlButton(
                        label = if (ui.speaker) "Динамик вкл." else "Динамик",
                        icon = if (ui.speaker) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        selected = ui.speaker,
                        onClick = { ui = ui.copy(speaker = coordinator?.toggleSpeaker() ?: !ui.speaker) }
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = {
                                coordinator?.end()
                                NexuraMessagingService.cancelIncomingCall(context, chat.id)
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
                Button(
                    onClick = { requestAudio("audio") },
                    enabled = coordinator != null && ui.status == "Готово к звонку"
                ) {
                    Icon(Icons.Default.Call, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Позвонить")
                }
            }
            Spacer(Modifier.weight(1f))
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
