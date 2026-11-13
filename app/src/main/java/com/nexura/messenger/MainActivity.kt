package com.nexura.messenger

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import java.util.Locale
import java.util.UUID
import java.io.IOException
import org.json.JSONObject
import android.os.Build
import android.net.Uri
import android.widget.VideoView
import android.media.MediaRecorder
import android.media.MediaPlayer
import java.io.File
import android.widget.MediaController
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexura.messenger.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import com.google.firebase.messaging.FirebaseMessaging

private val Bg = Color(0xFF0B0914)
private val Surface1 = Color(0xFF151126)
private val Surface2 = Color(0xFF211A35)
private val Accent = Color(0xFF9B6BFF)
private val Accent2 = Color(0xFF6842D8)
private val MainText = Color(0xFFF5F7FB)
private val SecondaryText = Color(0xFFA4AFBF)
private val Danger = Color(0xFFFF858D)
private val Success = Color(0xFF8DDEB0)

private data class DeviceContact(val name: String, val phone: String, val match: ContactMatch? = null)

class MainActivity : ComponentActivity() {
    private var incomingDeepLink by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingDeepLink = intent?.data
        val store = SessionStore(this)
        val api = NexuraApi(store)
        setContent { NexuraApp(api, store, incomingDeepLink) }
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences("nexura_runtime", MODE_PRIVATE).edit().putBoolean("activity_visible", true).apply()
    }

    override fun onStop() {
        getSharedPreferences("nexura_runtime", MODE_PRIVATE).edit().putBoolean("activity_visible", false).apply()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingDeepLink = intent.data
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NexuraApp(api: NexuraApi, store: SessionStore, authCallback: Uri?) {
    val context = LocalContext.current
    // Honor 10i-class devices have limited memory/CPU headroom; use lighter refresh and scrolling.
    val lowRamDevice = remember {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val scope = rememberCoroutineScope()
    val realtimeClient = remember(store) { SupabaseMessagesRealtime(store) }
    val localCache = remember(context) { LocalCacheStore(context.applicationContext) }
    var signedIn by remember { mutableStateOf(!store.accessToken.isNullOrBlank() || !store.refreshToken.isNullOrBlank()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    var phoneCode by remember { mutableStateOf("") }
    var smsSent by remember { mutableStateOf(false) }
    var phoneMode by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var chats by remember { mutableStateOf(emptyList<ChatItem>()) }
    var selected by remember { mutableStateOf<ChatItem?>(null) }

    // Avoid showing a push for the conversation the user is already reading.
    // The Activity visibility flag ensures this never suppresses notifications in the background.
    LaunchedEffect(selected?.id) {
        context.getSharedPreferences("nexura_runtime", Context.MODE_PRIVATE)
            .edit().putString("active_conversation_id", selected?.id).apply()
    }
    var messages by remember { mutableStateOf(emptyList<MessageItem>()) }
    var messageReactions by remember { mutableStateOf(emptyMap<String, List<MessageReaction>>()) }
    var pinnedMessageId by remember { mutableStateOf<String?>(null) }
    var peerReadReceiptAt by remember { mutableStateOf<String?>(null) }
    var canManagePins by remember { mutableStateOf(false) }
    var lastSyncedMessageTimestamp by remember { mutableStateOf<String?>(null) }
    var lastSyncedMessageId by remember { mutableStateOf<String?>(null) }
    var realtimeConnected by remember(selected?.id) { mutableStateOf(false) }
    var hasMoreMessages by remember { mutableStateOf(false) }
    var loadingOlderMessages by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var chatSearchOpen by remember { mutableStateOf(false) }
    var chatSearchQuery by remember { mutableStateOf("") }
    var users by remember { mutableStateOf(emptyList<UserItem>()) }
    var searching by remember { mutableStateOf(false) }
    var openingDirectUserId by remember { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var homeTab by remember { mutableStateOf("chats") }
    var deviceContacts by remember { mutableStateOf(emptyList<DeviceContact>()) }
    var syncingContacts by remember { mutableStateOf(false) }
    var contactsSynced by remember { mutableStateOf(false) }
    var contactsError by remember { mutableStateOf("") }
    var chatFilter by remember { mutableStateOf("") }
    var chatListFilter by remember { mutableStateOf("all") }
    val favoriteChatIds = remember(store.userId) {
        val prefs = context.getSharedPreferences("nexura_chat_prefs_${store.userId.orEmpty()}", 0)
        mutableStateListOf<String>().apply { addAll(prefs.getStringSet("favorite_chat_ids", emptySet()).orEmpty()) }
    }
    val chatDrafts = remember { mutableStateMapOf<String, String>() }
    val chatPreviews = remember { mutableStateMapOf<String, String>() }
    var showChatFilter by remember { mutableStateOf(false) }
    var openMessageMenu by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showTerminateOthersDialog by remember { mutableStateOf(false) }
    val messageDelivery = remember { mutableStateMapOf<String, String>() }
    val failedMessageRetries = remember { mutableStateMapOf<String, Triple<ChatItem, String, String?>>() }
    val messageSendMutex = remember { Mutex() }
    // Serialize Realtime event sync with reconnect catch-up to avoid duplicate unread increments.
    val conversationSyncMutex = remember { Mutex() }
    var sendingImage by remember { mutableStateOf(false) }
    var pendingMediaUri by remember { mutableStateOf<Uri?>(null) }
    var mediaViewerUri by remember { mutableStateOf<Uri?>(null) }
    var mediaViewerType by remember { mutableStateOf("image") }
    var chatGallery by remember { mutableStateOf<List<MessageItem>?>(null) }
    var galleryCategory by remember { mutableStateOf("Все") }
    var scrollToMessageId by remember { mutableStateOf<String?>(null) }
    var chatThemeOpen by remember { mutableStateOf(false) }
    var chatWallpaperColor by remember { mutableStateOf(Color(0xFF100C1D)) }
    var chatBubbleColor by remember { mutableStateOf(Color(0xFF6945C7)) }
    var recordingVoice by remember { mutableStateOf(false) }
    var voiceRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var voiceFile by remember { mutableStateOf<File?>(null) }
    var profileName by remember { mutableStateOf("") }
    var profileAvatar by remember { mutableStateOf("🙂") }
    var profileBio by remember { mutableStateOf("") }
    var profileAge by remember { mutableStateOf("") }
    var profilePhotoUri by remember { mutableStateOf<Uri?>(null) }
    var savingProfile by remember { mutableStateOf(false) }
    var showOnlineStatus by remember { mutableStateOf(true) }
    var readReceiptsEnabled by remember { mutableStateOf(true) }
    var blockedUsers by remember { mutableStateOf(emptyList<UserItem>()) }
    var ownE2eeDevices by remember { mutableStateOf(emptyList<E2eeRemoteDevice>()) }
    var deviceToRevoke by remember { mutableStateOf<E2eeRemoteDevice?>(null) }
    var viewedPresence by remember { mutableStateOf<UserPresence?>(null) }
    var chatPresence by remember { mutableStateOf<UserPresence?>(null) }
    var confirmBlockProfile by remember { mutableStateOf<UserProfile?>(null) }
    var savingPrivacy by remember { mutableStateOf(false) }
    var viewedProfile by remember { mutableStateOf<UserProfile?>(null) }
    var profileMedia by remember { mutableStateOf(emptyList<MessageItem>()) }
    var loadingProfile by remember { mutableStateOf(false) }
    var createGroupOpen by remember { mutableStateOf(false) }
    var groupTitle by remember { mutableStateOf("") }
    var groupSearch by remember { mutableStateOf("") }
    var groupUsers by remember { mutableStateOf(emptyList<UserItem>()) }
    var selectedGroupUsers by remember { mutableStateOf(emptySet<String>()) }
    var creatingGroup by remember { mutableStateOf(false) }
    var replyTarget by remember { mutableStateOf<MessageItem?>(null) }
    var editingTarget by remember { mutableStateOf<MessageItem?>(null) }
    var forwardingTarget by remember { mutableStateOf<MessageItem?>(null) }
    var forwardingBusy by remember { mutableStateOf(false) }
    var forwardingSelectedChatIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var forwardingSearch by remember { mutableStateOf("") }
    var editDraft by remember { mutableStateOf("") }
    var e2eeClient by remember { mutableStateOf<E2eeClient?>(null) }
    var e2eeStatus by remember { mutableStateOf("Шифрование не проверено") }
    var e2eeRecoveryRequired by remember { mutableStateOf(false) }
    var recoveryCodeInput by remember { mutableStateOf("") }
    var backupRecoveryCode by remember { mutableStateOf("") }
    var backupBusy by remember { mutableStateOf(false) }
    var keyRefreshBusy by remember { mutableStateOf(false) }
    var trustCandidate by remember { mutableStateOf<E2eeRemoteDevice?>(null) }
    var trustPeerId by remember { mutableStateOf<String?>(null) }
    var groupTrustQueue by remember { mutableStateOf(emptyList<Pair<String, E2eeRemoteDevice>>()) }
    LaunchedEffect(selected?.id, messages) {
        val chatId = selected?.id ?: return@LaunchedEffect
        messages.lastOrNull()?.let { latest ->
            chatPreviews[chatId] = chatMessagePreview(latest, store.userId.orEmpty())
        }
    }

    LaunchedEffect(selected?.id, store.userId) {
        context.getSharedPreferences("nexura_runtime", android.content.Context.MODE_PRIVATE)
            .edit().putString("active_chat_id", selected?.id).apply()
        selected?.id?.let { chatId ->
            val prefs = context.getSharedPreferences("nexura_chat_prefs_${store.userId.orEmpty()}", Context.MODE_PRIVATE)
            chatWallpaperColor = Color(prefs.getInt("wallpaper_$chatId", 0xFF100C1D.toInt()))
            chatBubbleColor = Color(prefs.getInt("bubble_$chatId", 0xFF6945C7.toInt()))
        } ?: run {
            chatWallpaperColor = Color(0xFF100C1D)
            chatBubbleColor = Color(0xFF6945C7)
        }
    }

    // Refresh the peer's privacy-aware presence while a direct chat is open.
    // The server returns only the fields permitted by that user's privacy settings.
    LaunchedEffect(signedIn, selected?.id, selected?.peerUserId) {
        chatPresence = null
        val peerId = selected?.takeIf { it.kind == "direct" }?.peerUserId
        if (signedIn && !peerId.isNullOrBlank()) {
            while (true) {
                runCatching { api.userPresence(peerId) }
                    .onSuccess { presence ->
                        if (selected?.peerUserId == peerId) chatPresence = presence
                    }
                delay(30_000L)
            }
        }
    }

    fun syncContacts() {
        scope.launch {
            syncingContacts = true
            contactsError = ""
            try {
                val rawContacts = withContext(Dispatchers.IO) {
                    val found = linkedMapOf<String, String>()
                    val country = Locale.getDefault().country.ifBlank { "US" }
                    val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
                    context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        while (cursor.moveToNext() && found.size < 500) {
                            val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                            val rawNumber = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                            val normalized = try { PhoneNumberUtils.formatNumberToE164(rawNumber, country) } catch (_: Exception) { null }
                            val phone = normalized ?: rawNumber.filterIndexed { index, ch -> ch.isDigit() || (ch == '+' && index == 0) }
                            if (phone.matches(Regex("^\\+[1-9][0-9]{7,14}$"))) found.putIfAbsent(phone, name.ifBlank { phone })
                        }
                    }
                    found.map { DeviceContact(it.value, it.key) }
                }
                val matches = api.matchContacts(rawContacts.map { it.phone })
                val byPhone = matches.associateBy { it.phone }
                deviceContacts = rawContacts.map { it.copy(match = byPhone[it.phone]) }
                contactsSynced = true
                if (deviceContacts.isEmpty()) contactsError = "В адресной книге не найдено номеров в международном формате."
            } catch (e: Exception) { contactsError = e.message ?: "Не удалось синхронизировать контакты" }
            finally { syncingContacts = false }
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) syncContacts() else contactsError = "Разрешите доступ к контактам, чтобы найти знакомых в Nexura."
    }

    fun inviteContact(contact: DeviceContact) {
        val shareText = "Привет, ${contact.name}! Давай общаться в Nexura Messenger. Найди Nexura в Google Play: https://play.google.com/store/apps/details?id=com.nexura.messenger"
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText) }
        context.startActivity(Intent.createChooser(send, "Пригласить в Nexura"))
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            try {
                val file = File.createTempFile("nexura-voice-", ".m4a", context.cacheDir)
                val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(96_000)
                recorder.setAudioSamplingRate(44_100)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                recorder.start()
                voiceFile = file
                voiceRecorder = recorder
                recordingVoice = true
                notice = "Идёт запись голосового сообщения"
            } catch (e: Exception) {
                voiceRecorder?.let { runCatching { it.stop(); it.release() } }
                voiceRecorder = null
                voiceFile?.delete()
                voiceFile = null
                error = e.message ?: "Не удалось начать запись"
            }
        } else error = "Разрешите доступ к микрофону для записи голосовых сообщений"
    }

    fun sendPickedMedia(uri: Uri?) {
        if (uri == null) return
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pendingMediaUri = uri
    }

    fun sendPendingMedia(chat: ChatItem, uri: Uri, caption: String, replyToId: String?) {
        if (sendingImage) return
        sendingImage = true
        val submittedCaption = caption
        val submittedReply = replyTarget
        draft = ""
        chatDrafts[chat.id] = ""
        replyTarget = null
        scope.launch {
            error = ""
            var encrypted: EncryptedMediaFile? = null
            try {
                val client = e2eeClient ?: throw IllegalStateException("Ключ E2EE ещё не готов")
                encrypted = withContext(Dispatchers.IO) {
                    E2eeMediaCrypto.encryptUri(context.contentResolver, uri, context.cacheDir)
                }
                if (chat.kind == "direct") {
                    api.sendEncryptedMediaMessage(chat.id, client, encrypted!!, caption, replyToId)
                } else {
                    api.sendEncryptedGroupMediaMessage(chat.id, client, encrypted!!, caption, replyToId)
                }
                pendingMediaUri = null
                draft = ""
                replyTarget = null
                notice = "Медиа отправлено с E2EE-шифрованием"
            } catch (e: Exception) {
                encrypted?.file?.delete()
                if (draft.isBlank()) draft = submittedCaption
                chatDrafts[chat.id] = draft
                if (replyTarget == null) replyTarget = submittedReply
                error = e.message ?: "Не удалось отправить медиа"
            } finally {
                if (uri.scheme == "file") uri.path?.let(::File)?.delete()
                sendingImage = false
            }
        }
    }

    fun stopVoiceRecording(chat: ChatItem) {
        val recorder = voiceRecorder ?: return
        val file = voiceFile
        try {
            recorder.stop()
            recorder.release()
            voiceRecorder = null
            recordingVoice = false
            if (file == null || !file.exists() || file.length() < 100) {
                file?.delete()
                voiceFile = null
                error = "Запись слишком короткая"
            } else {
                sendPendingMedia(chat, Uri.fromFile(file), "", null)
                voiceFile = null
            }
        } catch (e: Exception) {
            runCatching { recorder.reset(); recorder.release() }
            voiceRecorder = null
            recordingVoice = false
            file?.delete()
            voiceFile = null
            error = "Не удалось завершить запись. Попробуйте ещё раз."
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        sendPickedMedia(uri)
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        profilePhotoUri = uri
    }

    val visibleChats = chats
        .filter { it.title.contains(chatFilter, ignoreCase = true) }
        .filter {
            when (chatListFilter) {
                "unread" -> it.unreadCount > 0
                "favorites" -> favoriteChatIds.contains(it.id)
                else -> true
            }
        }
        .sortedWith(compareByDescending<ChatItem> { favoriteChatIds.contains(it.id) }.thenByDescending { it.updated.orEmpty() })

    fun loadChats() {
        scope.launch {
            busy = true
            try {
                val latestChats = api.chats()
                chats = latestChats
                // ChatItem intentionally contains no server-side plaintext preview: message bodies are E2EE.
                // Seed each row from this account's encrypted-at-rest local message cache.
                val cachedPreviews = withContext(Dispatchers.IO) {
                    latestChats.mapNotNull { chat ->
                        localCache.readMessages(store.userId.orEmpty(), chat.id)
                            .maxWithOrNull(compareBy<MessageItem> { it.createdAt }.thenBy { it.id })
                            ?.let { chat.id to chatMessagePreview(it, store.userId.orEmpty()) }
                    }
                }
                chatPreviews.clear()
                cachedPreviews.forEach { (chatId, preview) -> chatPreviews[chatId] = preview }
                withContext(Dispatchers.IO) { localCache.saveChats(store.userId.orEmpty(), latestChats) }
                error = ""
            } catch (e: Exception) {
                error = e.message ?: "Не удалось загрузить чаты"
            } finally { busy = false }
        }
    }

    fun dispatchSecureMessage(
        chat: ChatItem, messageId: String, text: String, replyToId: String?, createdAt: String? = null
    ) {
        if (messageDelivery[messageId] == "sending" || messageDelivery[messageId] == "sent") return
        messageDelivery[messageId] = "sending"
        failedMessageRetries.remove(messageId)
        scope.launch {
            try {
                val client = e2eeClient ?: throw IllegalStateException("Локальный ключ E2EE ещё не готов")
                // Durably queue the plaintext under the Android Keystore key before any ratchet advance or network I/O.
                withContext(Dispatchers.IO) {
                    client.savePendingOutgoing(chat.id, messageId, text, replyToId, createdAt)
                }
                var delivered = false
                var lastFailure: Exception? = null
                for (attempt in 0..2) {
                    try {
                        messageSendMutex.withLock {
                            // Olm/JNI encryption is CPU work; never run it on Compose's main thread.
                            withContext(Dispatchers.Default) {
                                if (chat.kind == "direct") {
                                    api.sendEncryptedDirectMessage(chat.id, client, messageId, text, replyToId)
                                } else {
                                    api.sendEncryptedGroupMessage(chat.id, client, messageId, text, replyToId = replyToId)
                                }
                            }
                        }
                        delivered = true
                        break
                    } catch (e: Exception) {
                        lastFailure = e
                        // Do not waste retries on permanent HTTP/client errors. A transient
                        // network failure keeps the same durable outbox item and message ID.
                        if (e !is E2eePreKeyUnavailableException && !isRetryableOutboxFailure(e)) throw e
                        if (attempt < 2) delay(if (attempt == 0) 1_500L else 4_000L)
                    }
                }
                if (delivered) {
                    messageDelivery[messageId] = "sent"
                    failedMessageRetries.remove(messageId)
                } else {
                    throw lastFailure ?: IOException("Не удалось отправить защищённое сообщение")
                }
            } catch (e: Exception) {
                if (e is E2eePreKeyUnavailableException || isRetryableOutboxFailure(e)) {
                    // Offline/temporary server failures are queued, not shown as a permanent
                    // send error. The encrypted-at-rest outbox retries with the same UUID.
                    messageDelivery[messageId] = "pending"
                    failedMessageRetries.remove(messageId)
                } else {
                    messageDelivery[messageId] = "failed"
                    failedMessageRetries[messageId] = Triple(chat, text, replyToId)
                    error = e.message ?: "Не удалось отправить защищённое сообщение"
                }
            }
        }
    }


    fun openChat(chat: ChatItem) {
        selected?.let { previous -> chatDrafts[previous.id] = draft }
        selected = chat
        peerReadReceiptAt = null
        draft = chatDrafts[chat.id].orEmpty()
        messages = emptyList()
        lastSyncedMessageTimestamp = null
        lastSyncedMessageId = null
        hasMoreMessages = false
        loadingOlderMessages = false
        messageReactions = emptyMap()
        pinnedMessageId = null
        canManagePins = false
        replyTarget = null
        chatSearchOpen = false
        chatSearchQuery = ""
        scope.launch {
            try {
                // Render the encrypted local snapshot immediately; network sync follows.
                val cachedMessages = withContext(Dispatchers.IO) {
                    localCache.readMessages(store.userId.orEmpty(), chat.id)
                }
                if (selected?.id != chat.id) return@launch
                if (cachedMessages.isNotEmpty()) {
                    messages = cachedMessages
                    hasMoreMessages = true
                    val cachedNewest = cachedMessages.maxWithOrNull(compareBy<MessageItem> { it.createdAt }.thenBy { it.id })
                    lastSyncedMessageTimestamp = cachedNewest?.createdAt
                    lastSyncedMessageId = cachedNewest?.id
                }
                // Start with a smaller first network page on low-memory phones.
                val loadedMessages = withContext(Dispatchers.IO) {
                    api.messages(chat.id, e2eeClient, pageSize = 20)
                }
                val pendingForChat = withContext(Dispatchers.IO) {
                    e2eeClient?.pendingOutgoingMessages().orEmpty().filter { it.conversationId == chat.id }
                }
                if (selected?.id != chat.id) return@launch

                val pendingItems = pendingForChat.map { pending ->
                    MessageItem(
                        pending.messageId, store.userId.orEmpty(), pending.plaintext, pending.createdAt,
                        "text", pending.replyToId, encrypted = true, securityWarning = false
                    )
                }
                // Prefer freshly decrypted server rows, while retaining older cached pages.
                messages = (loadedMessages + cachedMessages + pendingItems).distinctBy { it.id }
                    .sortedWith(compareBy<MessageItem> { it.createdAt }.thenBy { it.id })
                withContext(Dispatchers.IO) {
                    localCache.saveMessages(store.userId.orEmpty(), chat.id, messages)
                }
                hasMoreMessages = loadedMessages.size >= 20 || cachedMessages.isNotEmpty()
                val newest = loadedMessages.maxWithOrNull(compareBy<MessageItem> { it.createdAt }.thenBy { it.id })
                lastSyncedMessageTimestamp = newest?.createdAt ?: "1970-01-01T00:00:00Z"
                lastSyncedMessageId = newest?.id

                pendingForChat.forEach { pending ->
                    dispatchSecureMessage(chat, pending.messageId, pending.plaintext, pending.replyToId, pending.createdAt)
                }

                // These metadata requests are independent. Run them together after
                // the messages are already visible so they cannot delay chat opening.
                val metadata = kotlinx.coroutines.coroutineScope {
                    val reactionsJob = async { runCatching { api.messageReactions(chat.id) }.getOrDefault(emptyMap()) }
                    val pinnedJob = async { runCatching { api.pinnedMessage(chat.id) }.getOrNull() }
                    val manageJob = async { runCatching { api.canManageConversation(chat.id) }.getOrDefault(false) }
                    val receiptJob = async {
                        runCatching { api.peerReadReceiptAt(chat.id, chat.peerUserId) }.getOrNull()
                    }
                    val readJob = async { runCatching { api.markChatRead(chat.id, readReceiptsEnabled) } }
                    val result = listOf(
                        reactionsJob.await(), pinnedJob.await(), manageJob.await(), receiptJob.await()
                    )
                    readJob.await()
                    result
                }
                @Suppress("UNCHECKED_CAST")
                val reactions = metadata[0] as Map<String, List<MessageReaction>>
                val pinned = metadata[1] as String?
                val manage = metadata[2] as Boolean
                val receipt = metadata[3] as String?
                if (selected?.id == chat.id) {
                    messageReactions = reactions
                    pinnedMessageId = pinned
                    canManagePins = manage
                    peerReadReceiptAt = receipt
                    chats = chats.map { if (it.id == chat.id) it.copy(unreadCount = 0) else it }
                }
            } catch (e: Exception) {
                if (selected?.id == chat.id) error = e.message ?: "Не удалось загрузить сообщения"
            }
        }
    }


    fun sendSecureMessage(chat: ChatItem, text: String, replyToId: String?) {
        if (text.isBlank()) return
        val messageId = UUID.randomUUID().toString()
        draft = ""
        chatDrafts[chat.id] = ""
        replyTarget = null
        val createdAt = java.time.Instant.now().toString()
        val optimistic = MessageItem(
            messageId, store.userId.orEmpty(), text, createdAt,
            "text", replyToId, encrypted = true, securityWarning = false
        )
        messages = (messages + optimistic).distinctBy { it.id }.sortedBy { it.createdAt }
        scope.launch(Dispatchers.IO) {
            localCache.saveMessages(store.userId.orEmpty(), chat.id, messages)
        }
        dispatchSecureMessage(chat, messageId, text, replyToId, createdAt)
    }

    LaunchedEffect(authCallback) {
        if (authCallback?.scheme == "nexura" && authCallback.host == "auth" && authCallback.path == "/callback") {
            busy = true
            error = ""
            try {
                api.consumeAuthCallback(authCallback)
                signedIn = !store.accessToken.isNullOrBlank()
                notice = "Добро пожаловать в Nexura!"
            } catch (e: Exception) {
                error = e.message ?: "Не удалось завершить вход"
            } finally { busy = false }
        }
    }

    LaunchedEffect(authCallback, signedIn, chats) {
        val targetChatId = if (authCallback?.scheme == "nexura" && authCallback.host == "chat") {
            authCallback.pathSegments.firstOrNull()?.takeIf { raw ->
                raw.matches(Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
            }?.let { raw -> runCatching { UUID.fromString(raw).toString() }.getOrNull() }
        } else null
        if (signedIn && targetChatId != null) {
            val targetChat = chats.firstOrNull { it.id.equals(targetChatId, ignoreCase = true) }
            if (targetChat != null && selected?.id != targetChat.id) {
                openChat(targetChat)
            }
        }
    }

    LaunchedEffect(signedIn) {
        if (signedIn) {
            launch {
                while (true) {
                    if (context.getSharedPreferences("nexura_runtime", Context.MODE_PRIVATE).getBoolean("activity_visible", false)) runCatching { api.updatePresenceHeartbeat() }
                    delay(60_000L)
                }
            }
            try {
                // Load the last known conversation list before waiting for Supabase.
                val cachedChats = withContext(Dispatchers.IO) {
                    localCache.readChats(store.userId.orEmpty())
                }
                if (cachedChats.isNotEmpty()) {
                    chats = cachedChats
                    val cachedPreviews = withContext(Dispatchers.IO) {
                        cachedChats.mapNotNull { cachedChat ->
                            localCache.readMessages(store.userId.orEmpty(), cachedChat.id)
                                .lastOrNull()?.let { cachedChat.id to chatMessagePreview(it, store.userId.orEmpty()) }
                        }
                    }
                    chatPreviews.clear()
                    cachedPreviews.forEach { (chatId, preview) -> chatPreviews[chatId] = preview }
                }
                api.restoreSession()
                api.myProfile() // Create a profile row for accounts that have not opened settings yet.
                val initialPrivacy = api.privacySettings()
                showOnlineStatus = initialPrivacy.showOnlineStatus
                readReceiptsEnabled = initialPrivacy.readReceiptsEnabled
                // Prepare local E2EE state before chats become tappable; key publication is
                // network work and happens after the chat list has rendered.
                val e2eeUserId = store.userId
                val localE2eeClient = if (!e2eeUserId.isNullOrBlank()) {
                    runCatching { E2eeClient(context.applicationContext, e2eeUserId) }
                        .onFailure { e2eeStatus = "Не удалось подготовить E2EE: ${it.message ?: "ошибка устройства"}" }
                        .getOrNull()
                } else null
                val latestChats = api.chats()
                chats = latestChats
                withContext(Dispatchers.IO) { localCache.saveChats(store.userId.orEmpty(), latestChats) }
                if (localE2eeClient != null) {
                    val hadLocalDevice = localE2eeClient.hasLocalDevice()
                    val previousServerDevice = !hadLocalDevice && api.hasRegisteredE2eeDevice()
                    val cloudBackupExists = !hadLocalDevice && api.hasE2eeBackup()
                    if (!hadLocalDevice && cloudBackupExists) {
                        e2eeClient = null
                        e2eeRecoveryRequired = true
                        e2eeStatus = "Найдена защищённая копия. Требуется восстановление ключей."
                    } else {
                        e2eeClient = localE2eeClient
                        try {
                            val available = api.publishE2eeDevice(localE2eeClient)
                            e2eeStatus = if (previousServerDevice) {
                                "Создано новое E2EE-устройство · старые ключи не найдены"
                            } else "Устройство зарегистрировано · $available ключей"
                            runCatching { api.syncE2eeBackupIfEnabled(localE2eeClient) }
                        } catch (e: Exception) {
                            e2eeStatus = "Каталог ключей недоступен: ${e.message ?: "ожидается настройка сервера"}"
                        }
                    }
                }
                val outgoingClient = e2eeClient
                if (outgoingClient != null) {
                    // A fresh install has no local message cache. Fetch one newest message per
                    // recent chat through the normal E2EE decrypt path so the list can show real
                    // previews without ever requesting or storing server-side plaintext.
                    launch {
                        latestChats.take(50).chunked(4).forEach { batch ->
                            val previewRows = coroutineScope {
                                batch.map { chat ->
                                    async(Dispatchers.IO) {
                                        runCatching {
                                            val newest = api.messages(chat.id, outgoingClient, pageSize = 1).lastOrNull()
                                            if (newest != null && !newest.content.startsWith("🔒") &&
                                                !newest.content.startsWith("⚠")) {
                                                val existing = localCache.readMessages(store.userId.orEmpty(), chat.id)
                                                localCache.saveMessages(
                                                    store.userId.orEmpty(), chat.id,
                                                    (existing + newest).distinctBy { it.id }
                                                )
                                                chat.id to chatMessagePreview(newest, store.userId.orEmpty())
                                            } else null
                                        }.getOrNull()
                                    }
                                }.mapNotNull { it.await() }
                            }
                            previewRows.forEach { (chatId, preview) -> chatPreviews[chatId] = preview }
                        }
                    }
                    val pendingOutgoing = withContext(Dispatchers.IO) {
                        outgoingClient.pendingOutgoingMessages()
                    }
                    pendingOutgoing.forEach { pending ->
                        val targetChat = chats.firstOrNull { it.id == pending.conversationId }
                        if (targetChat != null) {
                            dispatchSecureMessage(
                                targetChat, pending.messageId, pending.plaintext,
                                pending.replyToId, pending.createdAt
                            )
                        }
                    }
                }
                // Persistently retry FCM token registration when network/auth becomes available.
                com.nexura.messenger.push.PushTokenSyncWorker.enqueue(context)
            } catch (e: Exception) {
                error = e.message ?: "Не удалось восстановить сессию"
                if (store.accessToken.isNullOrBlank() && store.refreshToken.isNullOrBlank()) signedIn = false
            }

            // Keep the recipient's public one-time-key pool replenished automatically.
            // This runs while Nexura is active, so people never need to open key settings.
            launch {
                delay(30_000L)
                var retryDelay = 30_000L
                while (true) {
                    val client = e2eeClient
                    if (client == null || e2eeRecoveryRequired) {
                        delay(60_000L)
                        continue
                    }
                    try {
                        val available = withContext(Dispatchers.IO) {
                            api.publishE2eeDevice(client)
                        }
                        e2eeStatus = "Защита обновлена · $available ключей"
                        retryDelay = 120_000L
                        delay(120_000L)
                    } catch (e: Exception) {
                        // Keep the failure visible so users know why key replenishment failed.
                        e2eeStatus = "Не удалось обновить ключи: ${e.message ?: "ошибка сети или сервера"}"
                        delay(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(300_000L)
                    }
                }
            }
        }
    }

    // Durable outbox recovery: retry queued encrypted messages while the app is active.
    // The queue is stored locally under Android Keystore; the server receives ciphertext only.
    // A bounded backoff avoids hammering Supabase when connectivity or a recipient key is unavailable.
    LaunchedEffect(signedIn) {
        if (signedIn) {
            var retryDelay = 30_000L
            while (true) {
                delay(retryDelay)
                val client = e2eeClient
                if (client == null || e2eeRecoveryRequired) {
                    retryDelay = (retryDelay * 2).coerceAtMost(300_000L)
                    continue
                }
                try {
                    val pending = withContext(Dispatchers.IO) {
                        client.pendingOutgoingMessages()
                    }
                    if (pending.isEmpty()) {
                        retryDelay = 30_000L
                        continue
                    }
                    // Refresh chat metadata so queued messages can resume even if the
                    // conversation list was stale when the device came back online.
                    val latestChats = api.chats()
                    chats = latestChats
                    var resumed = 0
                    pending.forEach { item ->
                        val target = latestChats.firstOrNull { it.id == item.conversationId } ?: return@forEach
                        val state = messageDelivery[item.messageId]
                        if (state != "sending" && state != "sent") {
                            dispatchSecureMessage(
                                target, item.messageId, item.plaintext, item.replyToId, item.createdAt
                            )
                            resumed++
                        }
                    }
                    retryDelay = if (resumed > 0) 30_000L else (retryDelay * 2).coerceAtMost(300_000L)
                } catch (_: Exception) {
                    retryDelay = (retryDelay * 2).coerceAtMost(300_000L)
                }
            }
        }
    }

    // One authenticated Realtime socket watches the user's recent conversations.
    // Incoming events are sync hints; ciphertext is fetched and decrypted through the existing E2EE path.
    LaunchedEffect(signedIn, chats.map { it.id }.take(50)) {
        realtimeConnected = false
        val watchedIds = chats.map { it.id }.take(50)
        if (signedIn && watchedIds.isNotEmpty()) {
            realtimeClient.observeConversations(watchedIds).collect { event ->
                realtimeConnected = event.connected
                val changedChatId = event.changedConversationId
                if (event.connected && event.messageChanged && changedChatId != null && e2eeClient != null) {
                    runCatching {
                        val cached = withContext(Dispatchers.IO) {
                            localCache.readMessages(store.userId.orEmpty(), changedChatId)
                        }
                        val newestCached = cached.maxWithOrNull(compareBy<MessageItem> { it.createdAt }.thenBy { it.id })
                        val latest = if (newestCached == null) {
                            api.messages(changedChatId, e2eeClient, pageSize = 20)
                        } else {
                            api.messages(
                                changedChatId, e2eeClient, newestCached.createdAt,
                                afterMessageId = newestCached.id
                            )
                        }
                        if (latest.isNotEmpty()) {
                            val newest = latest.maxWithOrNull(compareBy<MessageItem> { it.createdAt }.thenBy { it.id })
                            // Keep the chat-list row in sync even when the changed conversation is not open.
                            // Only decrypted plaintext returned by the existing E2EE path is eligible.
                            if (newest != null && !newest.content.startsWith("🔒") && !newest.content.startsWith("⚠")) {
                                chatPreviews[changedChatId] = chatMessagePreview(newest, store.userId.orEmpty())
                            }
                            val base = if (selected?.id == changedChatId) messages else cached
                            val merged = (base + latest).distinctBy { it.id }
                                .sortedWith(compareBy<MessageItem> { it.createdAt }.thenBy { it.id })
                            withContext(Dispatchers.IO) {
                                localCache.saveMessages(store.userId.orEmpty(), changedChatId, merged)
                            }
                            if (selected?.id == changedChatId) {
                                messages = merged
                                val newest = latest.maxWithOrNull(compareBy<MessageItem> { it.createdAt }.thenBy { it.id })
                                if (newest != null) {
                                    val timestamp = lastSyncedMessageTimestamp
                                    val id = lastSyncedMessageId
                                    if (timestamp == null || newest.createdAt > timestamp ||
                                        (newest.createdAt == timestamp && newest.id > id.orEmpty())) {
                                        lastSyncedMessageTimestamp = newest.createdAt
                                        lastSyncedMessageId = newest.id
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Keep messages and the chat list responsive; optimize the work, not refresh frequency.
    LaunchedEffect(signedIn, selected?.id) {
        if (signedIn) {
            var retryDelay = 2_500L
            var lastReceiptRefresh = 0L
            while (true) {
                val currentChat = selected
                delay(if (currentChat != null && realtimeConnected) 20_000L else if (currentChat != null) retryDelay else maxOf(20_000L, retryDelay))
                try {
                    if (currentChat != null) {
                        val lastTimestamp = lastSyncedMessageTimestamp
                        val latest = api.messages(currentChat.id, e2eeClient, lastTimestamp, afterMessageId = lastSyncedMessageId)
                        if (currentChat.kind == "direct" && System.currentTimeMillis() - lastReceiptRefresh >= 15_000L) {
                            peerReadReceiptAt = api.peerReadReceiptAt(currentChat.id, currentChat.peerUserId)
                            lastReceiptRefresh = System.currentTimeMillis()
                        }
                        if (selected?.id == currentChat.id && latest.isNotEmpty()) {
                            messages = (messages + latest).distinctBy { it.id }.sortedWith(compareBy<MessageItem> { it.createdAt }.thenBy { it.id })
                            withContext(Dispatchers.IO) {
                                localCache.saveMessages(store.userId.orEmpty(), currentChat.id, messages)
                            }
                            val newest = latest.maxWithOrNull(compareBy<MessageItem> { it.createdAt }.thenBy { it.id })
                            if (newest != null) {
                                val currentTimestamp = lastSyncedMessageTimestamp
                                val currentId = lastSyncedMessageId
                                if (currentTimestamp == null || newest.createdAt > currentTimestamp ||
                                    (newest.createdAt == currentTimestamp && newest.id > currentId.orEmpty())) {
                                    lastSyncedMessageTimestamp = newest.createdAt
                                    lastSyncedMessageId = newest.id
                                }
                            }
                        }
                    } else if (System.currentTimeMillis() - lastReceiptRefresh >= 60_000L) {
                        val latestChats = api.chats()
                        chats = latestChats
                        withContext(Dispatchers.IO) {
                            localCache.saveChats(store.userId.orEmpty(), latestChats)
                        }
                        lastReceiptRefresh = System.currentTimeMillis()
                    }
                    retryDelay = 2_500L
                } catch (_: Exception) {
                    // Back off only on actual request failures; normal refresh cadence stays fast.
                    retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    BackHandler(enabled = signedIn && (selected != null || settingsOpen || createGroupOpen || homeTab != "chats")) {
        when {
            editingTarget != null -> editingTarget = null
            createGroupOpen -> createGroupOpen = false
            selected != null -> { selected = null; messages = emptyList(); replyTarget = null; loadChats() }
            settingsOpen -> { settingsOpen = false; error = ""; notice = "" }
            homeTab != "chats" -> homeTab = "chats"
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, secondary = Accent2, background = Bg, surface = Surface1,
            onBackground = MainText, onSurface = MainText
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            when {
                !signedIn -> {
                    Column(
                        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF121D32), Bg, Bg)))
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                        verticalArrangement = Arrangement.Top
                    ) {
                        Box(Modifier.size(62.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Accent, Accent2))), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ChatBubbleOutline, null, tint = Color.White, modifier = Modifier.size(31.dp))
                        }
                        Spacer(Modifier.height(22.dp))
                        Text("nexura", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = MainText, letterSpacing = (-1).sp)
                        Text("Твоё пространство для общения", color = SecondaryText, fontSize = 15.sp, modifier = Modifier.padding(top = 5.dp, bottom = 28.dp))
                        OutlinedButton(
                            onClick = {
                                val redirect = java.net.URLEncoder.encode(NexuraApi.AUTH_REDIRECT, "UTF-8")
                                val url = "${NexuraApi.BASE_URL}/auth/v1/authorize?provider=google&redirect_to=$redirect"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MainText)
                        ) { Text("Продолжить с Google", fontWeight = FontWeight.SemiBold) }
                        Text("или войдите по электронной почте", color = SecondaryText, fontSize = 13.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
                        if (phoneMode) {
                            AuthField(phone, { phone = it; smsSent = false }, "Номер телефона", "+49 123 456789", keyboard = ImeAction.Next)
                            if (smsSent) AuthField(phoneCode, { phoneCode = it.filter(Char::isDigit).take(8) }, "Код из SMS", "Введите код")
                            Text("Используйте международный формат номера", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        } else {
                            if (register) AuthField(displayName, { displayName = it }, "Ваше имя", "Как к вам обращаться")
                            AuthField(email, { email = it }, "Email", "name@example.com", keyboard = ImeAction.Next)
                            OutlinedTextField(
                                value = password, onValueChange = { password = it },
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                label = { Text("Пароль") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                shape = RoundedCornerShape(16.dp),
                                colors = authFieldColors()
                            )
                        }
                        if (error.isNotBlank()) Text(error, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                        if (notice.isNotBlank()) Text(notice, color = Success, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true; error = ""; notice = ""
                                    try {
                                        if (phoneMode) {
                                            if (!smsSent) {
                                                api.sendPhoneCode(phone)
                                                smsSent = true
                                                notice = "Код отправлен на $phone"
                                            } else {
                                                api.verifyPhoneCode(phone, phoneCode)
                                                signedIn = true
                                            }
                                        } else {
                                            if (register) api.signUp(email.trim(), password, displayName.trim())
                                            else api.signIn(email.trim(), password)
                                            // Successful auth methods have already validated and persisted
                                            // the session on Dispatchers.IO; do not decrypt preferences on UI.
                                            signedIn = true
                                        }
                                    } catch (e: Exception) { error = e.message ?: "Не удалось выполнить вход" }
                                    finally { busy = false }
                                }
                            },
                            enabled = !busy && if (phoneMode) phone.startsWith("+") && phone.count { it.isDigit() } in 8..15 && (!smsSent || phoneCode.length in 4..8)
                                else email.contains("@") && password.length >= 6 && (!register || displayName.isNotBlank()),
                            modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text(if (busy) "Подождите…" else if (phoneMode) if (smsSent) "Подтвердить код" else "Получить SMS-код" else if (register) "Создать аккаунт" else "Войти", fontWeight = FontWeight.SemiBold) }
                        if (!phoneMode) {
                            OutlinedButton(
                                onClick = { },
                                enabled = false,
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(50.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = SecondaryText,
                                    disabledContentColor = SecondaryText.copy(alpha = 0.8f)
                                )
                            ) { Text("Регистрация через Telegram · Скоро", fontWeight = FontWeight.Medium) }
                            Text("Вход через Telegram находится в разработке", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
                            TextButton(onClick = { register = !register; error = ""; notice = "" }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                Text(if (register) "Уже есть аккаунт? Войти" else "Создать аккаунт")
                            }
                            if (!register) TextButton(
                                onClick = {
                                    scope.launch {
                                        busy = true; error = ""; notice = ""
                                        try {
                                            api.resendConfirmation(email.trim())
                                            notice = "Если адрес зарегистрирован, письмо подтверждения будет отправлено."
                                        } catch (e: Exception) { error = e.message ?: "Не удалось отправить письмо" }
                                        finally { busy = false }
                                    }
                                }, enabled = !busy && email.contains("@"), modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) { Text("Отправить письмо повторно", color = SecondaryText, fontSize = 12.sp) }
                        }
                        Text("Защищённое пространство для ваших разговоров", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 18.dp))
                    }
                }
                selected == null && settingsOpen -> {
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { settingsOpen = false; error = ""; notice = "" }) { Icon(Icons.Default.ArrowBack, "Назад", tint = MainText) }
                            Text("Настройки", color = MainText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("АККАУНТ", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Card(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 22.dp), colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.clickable { avatarPicker.launch("image/*") }) {
                                        if (profilePhotoUri != null) AsyncImage(model = profilePhotoUri, contentDescription = "Фото профиля", modifier = Modifier.size(58.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                        else Avatar(profileName.ifBlank { "Ваш профиль" }, profileAvatar)
                                    }
                                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                        Text(profileName.ifBlank { "Ваш аккаунт Nexura" }, color = MainText, fontWeight = FontWeight.SemiBold)
                                        Text("Нажмите на фото, чтобы изменить", color = SecondaryText, fontSize = 11.sp)
                                    }
                                    TextButton(onClick = { avatarPicker.launch("image/*") }) { Text("Фото") }
                                }
                                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Surface2)
                                Text("Имя в Nexura", color = SecondaryText, fontSize = 12.sp)
                                OutlinedTextField(value = profileName, onValueChange = { profileName = it.take(40) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true, placeholder = { Text("Ваше имя") }, shape = RoundedCornerShape(12.dp), colors = authFieldColors())
                                Text("Аватар", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 7.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("🙂", "😎", "🦊", "🐼", "🐸", "🐱", "🚀", "🌙").forEach { emoji ->
                                        Box(Modifier.size(38.dp).clip(CircleShape).background(if (profileAvatar == emoji) Accent.copy(alpha = 0.25f) else Surface2).clickable { profileAvatar = emoji; profilePhotoUri = null }, contentAlignment = Alignment.Center) { Text(emoji, fontSize = 21.sp) }
                                    }
                                }
                                Text("О себе", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                                OutlinedTextField(value = profileBio, onValueChange = { profileBio = it.take(160) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3, placeholder = { Text("Расскажите немного о себе") }, shape = RoundedCornerShape(12.dp), colors = authFieldColors())
                                Text("${profileBio.length}/160", color = SecondaryText, fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
                                Text("Возраст (необязательно)", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 5.dp))
                                OutlinedTextField(value = profileAge, onValueChange = { profileAge = it.filter(Char::isDigit).take(3) }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Например, 25") }, shape = RoundedCornerShape(12.dp), colors = authFieldColors())
                                Button(onClick = { scope.launch {
                                    savingProfile = true; error = ""; notice = ""
                                    try {
                                        if (profilePhotoUri != null) {
                                            val uri = profilePhotoUri!!
                                            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Не удалось прочитать фотографию") }
                                            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                                            profileAvatar = withContext(Dispatchers.IO) { api.uploadAvatar(bytes, mime) }
                                            profilePhotoUri = null
                                        }
                                        val age = profileAge.toIntOrNull()
                                        if (profileAge.isNotBlank() && age == null) throw IOException("Проверьте возраст")
                                        api.updateMyProfile(profileName, profileAvatar, profileBio, age)
                                        notice = "Профиль сохранён."
                                    } catch (e: Exception) { error = e.message ?: "Не удалось сохранить профиль" }
                                    finally { savingProfile = false }
                                } }, enabled = !savingProfile && profileName.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 14.dp), shape = RoundedCornerShape(12.dp)) { Text(if (savingProfile) "Сохраняем…" else "Сохранить профиль") }
                                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Surface2)
                                Text("ID пользователя", color = SecondaryText, fontSize = 12.sp)
                                Text(store.userId ?: "Не определён", color = MainText, fontSize = 12.sp)
                            }
                        }
                        Text("ПРИВАТНОСТЬ", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Card(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 20.dp), colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Показывать статус «В сети»", color = MainText, fontWeight = FontWeight.Medium)
                                        Text("Если выключить, другие не увидят время последнего посещения.", color = SecondaryText, fontSize = 11.sp)
                                    }
                                    Switch(checked = showOnlineStatus, enabled = !savingPrivacy, onCheckedChange = { enabled ->
                                        val old = showOnlineStatus
                                        showOnlineStatus = enabled
                                        scope.launch {
                                            savingPrivacy = true
                                            try { api.updatePrivacySettings(enabled, readReceiptsEnabled); notice = "Настройки приватности сохранены." }
                                            catch (e: Exception) { showOnlineStatus = old; error = e.message ?: "Не удалось сохранить приватность" }
                                            finally { savingPrivacy = false }
                                        }
                                    })
                                }
                                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Surface2)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Отчёты о прочтении", color = MainText, fontWeight = FontWeight.Medium)
                                        Text("При отключении собеседник не увидит, что вы прочитали сообщения.", color = SecondaryText, fontSize = 11.sp)
                                    }
                                    Switch(checked = readReceiptsEnabled, enabled = !savingPrivacy, onCheckedChange = { enabled ->
                                        val old = readReceiptsEnabled
                                        readReceiptsEnabled = enabled
                                        scope.launch {
                                            savingPrivacy = true
                                            try { api.updatePrivacySettings(showOnlineStatus, enabled); notice = "Настройки приватности сохранены." }
                                            catch (e: Exception) { readReceiptsEnabled = old; error = e.message ?: "Не удалось сохранить приватность" }
                                            finally { savingPrivacy = false }
                                        }
                                    })
                                }
                                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Surface2)
                                Text("Заблокированные пользователи", color = MainText, fontWeight = FontWeight.SemiBold)
                                if (blockedUsers.isEmpty()) Text("Список пуст", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                                else blockedUsers.forEach { blocked ->
                                    Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Avatar(blocked.displayName, blocked.avatar)
                                        Text(blocked.displayName, color = MainText, modifier = Modifier.weight(1f).padding(start = 10.dp), maxLines = 1)
                                        TextButton(onClick = {
                                            scope.launch {
                                                try { api.unblockUser(blocked.id); blockedUsers = blockedUsers.filterNot { it.id == blocked.id }; notice = "Пользователь разблокирован." }
                                                catch (e: Exception) { error = e.message ?: "Не удалось разблокировать" }
                                            }
                                        }) { Text("Разблокировать") }
                                    }
                                }
                            }
                        }
                        Text("СЕАНС И БЕЗОПАСНОСТЬ", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Card(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Сквозное шифрование", color = MainText, fontWeight = FontWeight.SemiBold)
                                Text(e2eeStatus, color = if (e2eeStatus.startsWith("Устройство зарегистрировано") || e2eeStatus.startsWith("Защита обновлена")) Success else Color(0xFFFFC56E), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                                val client = e2eeClient
                                val bundle = runCatching { JSONObject(if (client?.hasLocalDevice() == true) client.ensureDevice() else "{}") }.getOrNull()
                                if (client != null && bundle != null && bundle.has("curve25519") && bundle.has("ed25519")) {
                                    Text("Отпечаток этого устройства", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
                                    Text(client.deviceFingerprint(bundle.getString("curve25519"), bundle.getString("ed25519")), color = MainText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Text("Сравните отпечатки устройств лично или по другому доверенному каналу. Текст, фото и видео шифруются до отправки. В личном чате пока поддерживается одно активное устройство собеседника.", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                                if (client != null && client.hasLocalDevice()) {
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            backupBusy = true
                                            error = ""
                                            notice = ""
                                            try {
                                                val code = client.createRecoveryCode()
                                                backupRecoveryCode = code
                                                api.uploadE2eeBackup(client)
                                                notice = "Зашифрованная копия сохранена. Сохраните ключ восстановления отдельно."
                                            } catch (e: Exception) { error = e.message ?: "Не удалось создать резервную копию" }
                                            finally { backupBusy = false }
                                        }
                                    }, enabled = !backupBusy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(12.dp)) {
                                        Text(if (backupBusy) "Создаём копию…" else "Создать / обновить резервную копию")
                                    }
                                    val savedCode = backupRecoveryCode.ifBlank { client.recoveryCode().orEmpty() }
                                    if (savedCode.isNotBlank()) {
                                        Text("КЛЮЧ ВОССТАНОВЛЕНИЯ · СОХРАНИТЕ ВНЕ ТЕЛЕФОНА", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                                        Text(savedCode, color = MainText, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).background(Surface2, RoundedCornerShape(8.dp)).clickable {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Nexura recovery key", savedCode))
                                            notice = "Ключ скопирован. Храните его в надёжном месте."
                                        }.padding(10.dp))
                                        Text("Без этого ключа сервер не сможет восстановить копию. Не отправляйте его никому.", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                                    }
                                }
                            }
                        }
                        if (e2eeClient?.hasLocalDevice() == true) {
                            Card(Modifier.fillMaxWidth().padding(top = 10.dp), colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("Устройства с доступом к E2EE", color = MainText, fontWeight = FontWeight.SemiBold)
                                    Text("Отозванное устройство больше не получит новые ключи и не сможет отправлять защищённые сообщения от этой идентичности.", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                                    if (ownE2eeDevices.isEmpty()) {
                                        Text("Активные устройства не найдены. Обновите список.", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                                    } else {
                                        ownE2eeDevices.forEachIndexed { index, device ->
                                            val currentDevice = device.deviceId == e2eeClient?.deviceId()
                                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Surface2)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Lock, null, tint = if (currentDevice) Success else Accent, modifier = Modifier.size(20.dp))
                                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                                    Text(if (currentDevice) "Это устройство" else "Другое устройство", color = MainText, fontWeight = FontWeight.Medium)
                                                    Text("ID: ${device.deviceId.take(8)}…", color = SecondaryText, fontSize = 11.sp)
                                                }
                                                if (currentDevice) {
                                                    Text("Текущее", color = Success, fontSize = 11.sp)
                                                } else {
                                                    TextButton(enabled = !busy, onClick = { deviceToRevoke = device }) {
                                                        Text("Отозвать", color = Danger)
                                                    }
                                                }
                                            }
                                            Text("Отпечаток: ${runCatching { e2eeClient?.deviceFingerprint(device.curve25519Key, device.ed25519Key) }.getOrNull().orEmpty()}", color = SecondaryText, fontSize = 10.sp, modifier = Modifier.padding(start = 30.dp, top = 3.dp))
                                        }
                                    }
                                }
                            }
                        }
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Авторизация", color = MainText, fontWeight = FontWeight.SemiBold)
                                Text("Сессия хранится на устройстве в зашифрованном виде.", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                                OutlinedButton(onClick = { scope.launch { busy = true; error = ""; notice = ""; try { api.refreshSession(); chats = api.chats(); notice = "Сессия успешно обновлена." } catch (e: Exception) { error = e.message ?: "Не удалось обновить сессию" } finally { busy = false } } }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), shape = RoundedCornerShape(13.dp)) { Text(if (busy) "Проверяем…" else "Проверить и обновить сессию") }
                                OutlinedButton(onClick = { showTerminateOthersDialog = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 9.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) {
                                    Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Завершить сеансы на других устройствах")
                                }
                                Text("Текущий вход останется активным. На других устройствах потребуется войти заново.", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
                            }
                        }
                        if (error.isNotBlank()) Text(error, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                        if (notice.isNotBlank()) Text(notice, color = Success, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        OutlinedButton(onClick = { showLogoutDialog = true }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 28.dp).height(50.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Text("Выйти из аккаунта") }
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                            NexuraBottomBar(
                                selected = homeTab,
                                profileSelected = true,
                                onSelect = { tab -> homeTab = tab; settingsOpen = false; createGroupOpen = false },
                                onProfile = { }
                            )
                        }
                    }
                    if (showLogoutDialog) AlertDialog(onDismissRequest = { showLogoutDialog = false }, title = { Text("Выйти из Nexura?") }, text = { Text("Вы сможете войти снова, используя свой способ авторизации. Зашифрованные ключи останутся на этом устройстве, а временный криптографический кэш будет очищен.") }, confirmButton = { TextButton(onClick = { showLogoutDialog = false; scope.launch { busy = true; try { val e2eeToClear = e2eeClient; val serverSignOut = runCatching { api.signOut() }; val cacheClear = runCatching { e2eeToClear?.clearVolatileState() }; signedIn = false; chats = emptyList(); selected = null; settingsOpen = false; e2eeClient = null; e2eeStatus = "Шифрование не проверено"; notice = when { cacheClear.isFailure -> "Локальный выход выполнен, но временный E2EE-кэш не удалось полностью очистить. Закройте приложение и запустите снова."; serverSignOut.isFailure -> "Локальный выход выполнен. Сервер не подтвердил отзыв сессии; проверьте подключение и завершите другие сеансы отдельно."; else -> "" } } catch (e: Exception) { error = e.message ?: "Ошибка выхода" } finally { busy = false } } }) { Text("Выйти", color = Danger) } }, dismissButton = { TextButton(onClick = { showLogoutDialog = false }) { Text("Отмена") } })
                    if (deviceToRevoke != null) AlertDialog(
                        onDismissRequest = { if (!busy) deviceToRevoke = null },
                        title = { Text("Отозвать E2EE-устройство?") },
                        text = { Text("Устройство ${deviceToRevoke?.deviceId?.take(8)}… будет отключено от E2EE. Его активные одноразовые ключи удалятся, а повторная регистрация с тем же ID будет заблокирована. Уже сохранённые данные не стираются, а вход в аккаунт может остаться активным. Для завершения всех остальных входов используйте отдельную кнопку ниже.") },
                        confirmButton = {
                            TextButton(enabled = !busy, onClick = {
                                val target = deviceToRevoke ?: return@TextButton
                                deviceToRevoke = null
                                scope.launch {
                                    busy = true; error = ""; notice = ""
                                    try {
                                        api.revokeOwnE2eeDevice(target.deviceId)
                                        ownE2eeDevices = api.ownE2eeDevices()
                                        notice = "Устройство отозвано. Его старая E2EE-идентичность больше не принимается сервером."
                                    } catch (e: Exception) {
                                        error = e.message ?: "Не удалось отозвать устройство"
                                    } finally { busy = false }
                                }
                            }) { Text(if (busy) "Отзываем…" else "Отозвать", color = Danger) }
                        },
                        dismissButton = { TextButton(enabled = !busy, onClick = { deviceToRevoke = null }) { Text("Отмена") } }
                    )
                    if (showTerminateOthersDialog) AlertDialog(
                        onDismissRequest = { if (!busy) showTerminateOthersDialog = false },
                        title = { Text("Завершить другие сеансы?") },
                        text = { Text("Nexura завершит все остальные сеансы входа в этот аккаунт. На других телефонах и компьютерах потребуется авторизоваться заново. Это устройство останется в аккаунте.") },
                        confirmButton = {
                            TextButton(enabled = !busy, onClick = {
                                showTerminateOthersDialog = false
                                scope.launch {
                                    busy = true; error = ""; notice = ""
                                    try {
                                        api.signOutOtherSessions()
                                        notice = "Все остальные сеансы входа завершены. Это устройство осталось подключённым."
                                    } catch (e: Exception) {
                                        error = e.message ?: "Не удалось завершить другие сеансы"
                                    } finally { busy = false }
                                }
                            }) { Text(if (busy) "Завершаем…" else "Завершить", color = Danger) }
                        },
                        dismissButton = { TextButton(enabled = !busy, onClick = { showTerminateOthersDialog = false }) { Text("Отмена") } }
                    )
                }
                selected == null -> {
                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Accent, Accent2))), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.ChatBubbleOutline, null, tint = Color.White, modifier = Modifier.size(23.dp))
                                }
                                Column(Modifier.padding(start = 11.dp)) {
                                    Text("Nexura", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = MainText, letterSpacing = (-0.6).sp)
                                    Text("Ваши разговоры", color = SecondaryText, fontSize = 12.sp)
                                }
                            }
                            Surface(shape = CircleShape, color = Surface1) {
                                IconButton(onClick = { createGroupOpen = true; groupTitle = ""; groupSearch = ""; groupUsers = emptyList(); selectedGroupUsers = emptySet(); error = "" }) {
                                    Icon(Icons.Default.GroupAdd, "Создать группу", tint = MainText)
                                }
                            }
                            Spacer(Modifier.width(7.dp))
                            Surface(shape = CircleShape, color = Surface1) {
                                IconButton(onClick = { settingsOpen = true; error = ""; notice = ""; scope.launch { try { val p = api.myProfile(); profileName = p.displayName; profileAvatar = p.avatar.ifBlank { "🙂" }; profileBio = p.bio; profileAge = p.age?.toString().orEmpty(); val privacy = api.privacySettings(); showOnlineStatus = privacy.showOnlineStatus; readReceiptsEnabled = privacy.readReceiptsEnabled; blockedUsers = api.blockedUsers(); ownE2eeDevices = api.ownE2eeDevices() } catch (e: Exception) { error = e.message ?: "Не удалось загрузить настройки" } } }) {
                                    Icon(Icons.Default.Settings, "Профиль и настройки", tint = SecondaryText)
                                }
                            }
                        }
                        if (homeTab == "chats") {
                            OutlinedTextField(
                                value = chatFilter,
                                onValueChange = { chatFilter = it },
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp).heightIn(min = 54.dp),
                                singleLine = true,
                                placeholder = { Text("Поиск по чатам", fontSize = 14.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, null, tint = SecondaryText) },
                                trailingIcon = {
                                    if (chatFilter.isNotBlank()) IconButton(onClick = { chatFilter = "" }) {
                                        Text("×", color = SecondaryText, fontSize = 22.sp)
                                    }
                                },
                                shape = RoundedCornerShape(18.dp),
                                colors = authFieldColors()
                            )
                        }
                        if (createGroupOpen) {
                            Card(
                                Modifier.fillMaxWidth().padding(top = 12.dp),
                                colors = CardDefaults.cardColors(containerColor = Surface1),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier.size(44.dp).clip(RoundedCornerShape(15.dp))
                                                .background(Brush.linearGradient(listOf(Accent, Accent2))),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.GroupAdd, null, tint = Color.White, modifier = Modifier.size(22.dp))
                                        }
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                            Text("Новая группа", color = MainText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                            Text("Общий чат для ваших людей", color = SecondaryText, fontSize = 12.sp)
                                        }
                                        IconButton(onClick = { createGroupOpen = false }) {
                                            Icon(Icons.Default.Close, "Закрыть", tint = SecondaryText)
                                        }
                                    }
                                    OutlinedTextField(
                                        value = groupTitle,
                                        onValueChange = { groupTitle = it.take(60) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text("Название группы") },
                                        placeholder = { Text("Например, Друзья") },
                                        shape = RoundedCornerShape(16.dp),
                                        colors = authFieldColors()
                                    )
                                    Text("Добавить участников", color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = groupSearch,
                                            onValueChange = { groupSearch = it; groupUsers = emptyList(); error = "" },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            placeholder = { Text("Имя или username") },
                                            leadingIcon = { Icon(Icons.Default.Search, null, tint = SecondaryText) },
                                            shape = RoundedCornerShape(16.dp),
                                            colors = authFieldColors(),
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                            keyboardActions = KeyboardActions(onSearch = {
                                                if (groupSearch.trim().length >= 2 && !searching) scope.launch {
                                                    searching = true
                                                    error = ""
                                                    try { groupUsers = api.findUsers(groupSearch) }
                                                    catch (e: Exception) { error = e.message ?: "Не удалось найти участников" }
                                                    finally { searching = false }
                                                }
                                            })
                                        )
                                        IconButton(
                                            onClick = {
                                                if (groupSearch.trim().length >= 2 && !searching) scope.launch {
                                                    searching = true
                                                    error = ""
                                                    try { groupUsers = api.findUsers(groupSearch) }
                                                    catch (e: Exception) { error = e.message ?: "Не удалось найти участников" }
                                                    finally { searching = false }
                                                }
                                            },
                                            enabled = groupSearch.length >= 2 && !searching
                                        ) {
                                            Icon(Icons.Default.Search, "Найти", tint = Accent)
                                        }
                                    }
                                    if (groupUsers.isNotEmpty()) {
                                        Column(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface2)
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            groupUsers.forEachIndexed { index, person ->
                                                Row(
                                                    Modifier.fillMaxWidth().clickable {
                                                        selectedGroupUsers = if (person.id in selectedGroupUsers) {
                                                            selectedGroupUsers - person.id
                                                        } else if (selectedGroupUsers.size >= 49) {
                                                            error = "В группе может быть не более 50 участников вместе с вами"
                                                            selectedGroupUsers
                                                        } else selectedGroupUsers + person.id
                                                    }.padding(vertical = 9.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Avatar(person.displayName, person.avatar)
                                                    Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                                        Text(person.displayName, color = MainText, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                                        Text(if (person.id in selectedGroupUsers) "Добавлен в группу" else "Пользователь Nexura", color = SecondaryText, fontSize = 11.sp)
                                                    }
                                                    Checkbox(
                                                        checked = person.id in selectedGroupUsers,
                                                        onCheckedChange = { checked ->
                                                            selectedGroupUsers = if (checked) {
                                                                if (selectedGroupUsers.size < 49) selectedGroupUsers + person.id
                                                                else { error = "В группе может быть не более 50 участников вместе с вами"; selectedGroupUsers }
                                                            } else selectedGroupUsers - person.id
                                                        }
                                                    )
                                                }
                                                if (index != groupUsers.lastIndex) Divider(color = SecondaryText.copy(alpha = 0.2f))
                                            }
                                        }
                                    } else {
                                        Text(
                                            "Найдите людей по имени или username и отметьте тех, кого хотите добавить.",
                                            color = SecondaryText, fontSize = 12.sp
                                        )
                                    }
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface2).padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.GroupAdd, null, tint = Accent, modifier = Modifier.size(19.dp))
                                        Text(
                                            "Выбрано: ${selectedGroupUsers.size} из 49 участников",
                                            color = MainText, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                                            modifier = Modifier.padding(start = 9.dp).weight(1f)
                                        )
                                        Text("Вы — владелец", color = SecondaryText, fontSize = 11.sp)
                                    }
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                creatingGroup = true
                                                error = ""
                                                try {
                                                    val id = api.createGroup(groupTitle, selectedGroupUsers.toList())
                                                    val chat = ChatItem(id, groupTitle.trim(), "group", null)
                                                    createGroupOpen = false
                                                    selected = chat
                                                    messages = emptyList()
                                                    chats = api.chats()
                                                } catch (e: Exception) {
                                                    error = e.message ?: "Не удалось создать группу"
                                                } finally { creatingGroup = false }
                                            }
                                        },
                                        enabled = !creatingGroup && groupTitle.trim().length in 2..60 && selectedGroupUsers.size in 1..49,
                                        modifier = Modifier.fillMaxWidth().height(52.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text(if (creatingGroup) "Создаём группу…" else "Создать группу", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        if (homeTab == "contacts") {
                            Column(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Контакты", color = MainText, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
                                        Text("Люди, с которыми можно общаться в Nexura", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                                    }
                                    Box(
                                        Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                                            .background(Brush.linearGradient(listOf(Accent, Accent2))),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.GroupAdd, null, tint = Color.White, modifier = Modifier.size(21.dp))
                                    }
                                }
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Surface1),
                                    shape = RoundedCornerShape(22.dp),
                                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Refresh, null, tint = Accent, modifier = Modifier.size(19.dp))
                                            Text("Синхронизация контактов", color = MainText, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 9.dp))
                                        }
                                        Text(
                                            "Найдём знакомых из адресной книги, которые уже зарегистрированы. Номера не отображаются в профиле.",
                                            color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp)
                                        )
                                        Button(
                                            onClick = {
                                                if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) syncContacts()
                                                else contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                            },
                                            enabled = !syncingContacts,
                                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                                            shape = RoundedCornerShape(15.dp)
                                        ) {
                                            Text(if (syncingContacts) "Синхронизируем…" else if (contactsSynced) "Обновить контакты" else "Найти знакомых")
                                        }
                                    }
                                }
                                if (contactsError.isNotBlank()) Text(contactsError, color = if (contactsSynced) SecondaryText else Danger, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                                if (contactsSynced) {
                                    val registered = deviceContacts.count { it.match != null }
                                    Row(
                                        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Column(
                                            Modifier.weight(1f).clip(RoundedCornerShape(15.dp)).background(Surface1).padding(12.dp)
                                        ) {
                                            Text("$registered", color = Success, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                            Text("В Nexura", color = SecondaryText, fontSize = 11.sp)
                                        }
                                        Column(
                                            Modifier.weight(1f).clip(RoundedCornerShape(15.dp)).background(Surface1).padding(12.dp)
                                        ) {
                                            Text("${deviceContacts.size - registered}", color = MainText, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                            Text("Можно пригласить", color = SecondaryText, fontSize = 11.sp)
                                        }
                                    }
                                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 18.dp)) {
                                        items(deviceContacts, key = { it.phone }) { person ->
                                            Row(
                                                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                                    .clip(RoundedCornerShape(17.dp)).background(Surface1)
                                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (person.match != null) Avatar(person.match.displayName, person.match.avatar) else Avatar(person.name, "👤")
                                                Column(Modifier.weight(1f).padding(start = 11.dp, end = 4.dp)) {
                                                    Text(person.match?.displayName ?: person.name, color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                                                    Text(if (person.match != null) "Уже в Nexura" else "Ещё не зарегистрирован", color = if (person.match != null) Success else SecondaryText, fontSize = 11.sp)
                                                }
                                                if (person.match != null) {
                                                    TextButton(onClick = {
                                                        scope.launch {
                                                            try {
                                                                val id = api.createDirect(person.match.userId)
                                                                openChat(ChatItem(id, person.match.displayName, "direct", null, avatar = person.match.avatar))
                                                                homeTab = "chats"
                                                            } catch (e: Exception) { error = e.message ?: "Не удалось открыть чат" }
                                                        }
                                                    }) { Text("Написать", maxLines = 1) }
                                                } else TextButton(onClick = { inviteContact(person) }) { Text("Пригласить", maxLines = 1) }
                                            }
                                        }
                                    }
                                } else Spacer(Modifier.weight(1f))
                            }
                        }
                        if (homeTab == "search") {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        ) {
                        Text("Найти людей", color = MainText, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 5.dp))
                        Text("По имени или отображаемому имени", color = SecondaryText, fontSize = 13.sp, modifier = Modifier.padding(bottom = 14.dp))
                        OutlinedTextField(
                            value = search, onValueChange = { search = it; users = emptyList() },
                            modifier = Modifier.fillMaxWidth(), placeholder = { Text("Например, Алексей") },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = SecondaryText) },
                            trailingIcon = { if (search.isNotBlank()) IconButton(onClick = { search = ""; users = emptyList() }) { Text("×", color = SecondaryText, fontSize = 22.sp) } },
                            singleLine = true, shape = RoundedCornerShape(18.dp), colors = authFieldColors(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                if (search.length >= 2) scope.launch { searching = true; try { users = api.findUsers(search) } catch (e: Exception) { error = e.message ?: "Поиск недоступен" }; searching = false }
                            })
                        )
                        Button(
                            onClick = { scope.launch { searching = true; error = ""; try { users = api.findUsers(search) } catch (e: Exception) { error = e.message ?: "Поиск недоступен" }; searching = false } },
                            enabled = search.length >= 2 && !searching,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(48.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text(if (searching) "Ищем…" else "Найти человека") }
                        if (searching) {
                            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
                                Text("Ищем людей…", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(start = 9.dp))
                            }
                        }
                        if (users.isNotEmpty()) {
                            Text("Результаты поиска", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                            users.forEach { user ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).clickable(
                                        enabled = openingDirectUserId == null
                                    ) {
                                        if (openingDirectUserId != null) return@clickable
                                        openingDirectUserId = user.id
                                        scope.launch {
                                            error = ""
                                            try {
                                                // The RPC is idempotent for a user pair; refresh the list and
                                                // reuse the canonical chat instead of adding a duplicate locally.
                                                val id = api.createDirect(user.id)
                                                val refreshedChats = api.chats()
                                                chats = refreshedChats
                                                val chat = refreshedChats.firstOrNull { it.id == id }
                                                    ?: ChatItem(id, user.displayName, "direct", null, avatar = user.avatar, peerUserId = user.id)
                                                if (refreshedChats.none { it.id == id }) chats = refreshedChats + chat
                                                openChat(chat)
                                                homeTab = "chats"
                                            } catch (e: Exception) {
                                                error = e.message ?: "Не удалось открыть чат"
                                            } finally {
                                                openingDirectUserId = null
                                            }
                                        }
                                    }.padding(vertical = 11.dp, horizontal = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Avatar(user.displayName, user.avatar)
                                    Text(user.displayName, color = MainText, modifier = Modifier.padding(start = 12.dp).weight(1f))
                                    Text(
                                        if (openingDirectUserId == user.id) "Открываем…" else "Написать",
                                        color = if (openingDirectUserId == null || openingDirectUserId == user.id) Accent else SecondaryText,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        }
                        }
                        if (homeTab == "chats") {
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Сообщения", color = MainText, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.35).sp)
                                Text("Ваши личные и групповые чаты", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                            Surface(shape = RoundedCornerShape(12.dp), color = Surface1) {
                                Text("${visibleChats.size}", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                            }
                        }
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(end = 4.dp)
                        ) {
                            item { FilterChip(selected = chatListFilter == "all", onClick = { chatListFilter = "all" }, label = { Text("Все чаты") }) }
                            item { FilterChip(selected = chatListFilter == "unread", onClick = { chatListFilter = "unread" }, label = { Text("Непрочитанные") }) }
                            item { FilterChip(selected = chatListFilter == "favorites", onClick = { chatListFilter = "favorites" }, label = { Text("Избранное") }) }
                        }
                        if (error.isNotBlank()) Text(error, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                        if (visibleChats.isEmpty()) {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(76.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.ChatBubbleOutline, null, tint = Accent, modifier = Modifier.size(34.dp))
                                    }
                                    Text(if (busy) "Загружаем чаты…" else if (chatFilter.isNotBlank()) "Ничего не найдено" else "Здесь пока тихо", color = MainText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                                    Text(if (chatFilter.isNotBlank()) "Попробуйте изменить запрос" else "Найдите человека через вкладку «Поиск» и начните разговор", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                                    if (chatFilter.isBlank() && !busy) {
                                        TextButton(onClick = { homeTab = "search" }) { Text("Найти собеседника") }
                                    }
                                }
                            }
                        } else {
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
                                items(visibleChats, key = { it.id }) { chat ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                            .clip(RoundedCornerShape(19.dp))
                                            .background(Surface1)
                                            .clickable { openChat(chat) }
                                            .padding(start = 12.dp, end = 7.dp, top = 11.dp, bottom = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ChatListAvatar(chat.title, chat.avatar, chat.kind == "group", chat.unreadCount > 0)
                                        Column(Modifier.weight(1f).padding(start = 12.dp, end = 5.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(chat.title, color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                                if (chat.updated != null) {
                                                    Text(chatTimeLabel(chat.updated), color = if (chat.unreadCount > 0) Accent else SecondaryText, fontSize = 11.sp)
                                                }
                                            }
                                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(if (chat.kind == "group") Icons.Default.GroupAdd else Icons.Default.ChatBubbleOutline, null, tint = SecondaryText, modifier = Modifier.size(13.dp))
                                                Text(chatPreviews[chat.id] ?: if (chat.kind == "group") "Групповой чат" else "Начните общение", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(start = 5.dp).weight(1f), maxLines = 1)
                                                if (chat.unreadCount > 0) {
                                                    Box(Modifier.clip(CircleShape).background(Accent).defaultMinSize(minWidth = 22.dp).padding(horizontal = 7.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
                                                        Text(chat.unreadCount.coerceAtMost(99).toString(), color = Bg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                        IconButton(onClick = {
                                            if (favoriteChatIds.contains(chat.id)) favoriteChatIds.remove(chat.id) else favoriteChatIds.add(chat.id)
                                            context.getSharedPreferences("nexura_chat_prefs_${store.userId.orEmpty()}", 0)
                                                .edit().putStringSet("favorite_chat_ids", favoriteChatIds.toSet()).apply()
                                        }) {
                                            Icon(
                                                if (favoriteChatIds.contains(chat.id)) Icons.Default.Star else Icons.Default.StarBorder,
                                                contentDescription = if (favoriteChatIds.contains(chat.id)) "Убрать из избранного" else "В избранное",
                                                tint = if (favoriteChatIds.contains(chat.id)) Accent else SecondaryText
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        }
                        NexuraBottomBar(
                            selected = homeTab,
                            profileSelected = false,
                            onSelect = { tab -> homeTab = tab; settingsOpen = false; createGroupOpen = false },
                            onProfile = {
                                settingsOpen = true
                                error = ""; notice = ""
                                scope.launch {
                                    try { val p = api.myProfile(); profileName = p.displayName; profileAvatar = p.avatar.ifBlank { "🙂" } }
                                    catch (e: Exception) { error = e.message ?: "Не удалось загрузить профиль" }
                                }
                            }
                        )
                    }
                }
                else -> {
                    if (forwardingTarget != null) {
                        val sourceMessage = forwardingTarget!!
                        val destinations = chats.filter {
                            it.id != selected?.id &&
                                (forwardingSearch.isBlank() || it.title.contains(forwardingSearch, ignoreCase = true))
                        }
                        AlertDialog(
                            onDismissRequest = {
                                if (!forwardingBusy) {
                                    forwardingTarget = null
                                    forwardingSelectedChatIds = emptySet()
                                }
                            },
                            title = { Text(if (forwardingBusy) "Пересылаем…" else "Переслать сообщение") },
                            text = {
                                Column(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                                    Text(
                                        if (sourceMessage.messageType == "media")
                                            "Вложение будет расшифровано только на этом устройстве и отдельно зашифровано для каждого выбранного чата."
                                        else "Сообщение будет заново зашифровано для каждого выбранного чата.",
                                        color = SecondaryText, fontSize = 12.sp
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = forwardingSearch,
                                        onValueChange = { forwardingSearch = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        placeholder = { Text("Найти чат") },
                                        leadingIcon = { Icon(Icons.Default.Search, null) }
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                                        destinations.forEach { destination ->
                                            val checked = destination.id in forwardingSelectedChatIds
                                            Row(
                                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                                    .clickable(enabled = !forwardingBusy) {
                                                        forwardingSelectedChatIds =
                                                            if (checked) forwardingSelectedChatIds - destination.id
                                                            else forwardingSelectedChatIds + destination.id
                                                    }.padding(horizontal = 8.dp, vertical = 9.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                ChatListAvatar(destination.title, destination.avatar, destination.kind == "group", destination.unreadCount > 0)
                                                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                                    Text(destination.title, color = MainText, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                                    Text(if (destination.kind == "group") "Группа" else "Личный чат", color = SecondaryText, fontSize = 11.sp)
                                                }
                                                Checkbox(
                                                    checked = checked,
                                                    onCheckedChange = { value ->
                                                        forwardingSelectedChatIds =
                                                            if (value) forwardingSelectedChatIds + destination.id
                                                            else forwardingSelectedChatIds - destination.id
                                                    },
                                                    enabled = !forwardingBusy
                                                )
                                            }
                                        }
                                        if (destinations.isEmpty()) {
                                            Text("Чаты не найдены.", color = SecondaryText, modifier = Modifier.padding(12.dp))
                                        }
                                    }
                                    Text(
                                        "Выбрано: ${forwardingSelectedChatIds.size}",
                                        color = Accent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = !forwardingBusy && forwardingSelectedChatIds.isNotEmpty(),
                                    onClick = {
                                        val targets = chats.filter { it.id in forwardingSelectedChatIds }
                                        forwardingBusy = true
                                        scope.launch {
                                            var sentCount = 0
                                            try {
                                                val client = e2eeClient ?: throw IOException("Ключ E2EE ещё не готов")
                                                targets.forEach { destination ->
                                                    if (sourceMessage.messageType == "media") {
                                                        if (sourceMessage.mediaMimeType.isNullOrBlank() ||
                                                            sourceMessage.mediaFileName.isNullOrBlank() ||
                                                            sourceMessage.mediaKind !in setOf("image", "video", "audio", "file")) {
                                                            throw IOException("Не хватает проверенных данных о вложении для безопасной пересылки")
                                                        }
                                                        val sourceUri = Uri.parse(sourceMessage.content)
                                                        if (sourceUri.scheme != "file" || sourceUri.path.isNullOrBlank()) {
                                                            throw IOException("Локальная расшифрованная копия вложения недоступна")
                                                        }
                                                        val sourceFile = File(sourceUri.path!!)
                                                        if (!sourceFile.isFile || sourceFile.length() !in 1..E2eeMediaCrypto.MAX_PLAINTEXT_BYTES) {
                                                            throw IOException("Файл вложения отсутствует или превышает лимит 24 МБ")
                                                        }
                                                        val encrypted = withContext(Dispatchers.IO) {
                                                            E2eeMediaCrypto.encryptUri(
                                                                context.contentResolver, sourceUri, context.cacheDir,
                                                                sourceMessage.mediaMimeType, sourceMessage.mediaFileName
                                                            )
                                                        }
                                                        if (destination.kind == "direct") {
                                                            api.sendEncryptedMediaMessage(destination.id, client, encrypted, sourceMessage.mediaCaption.orEmpty())
                                                        } else {
                                                            api.sendEncryptedGroupMediaMessage(destination.id, client, encrypted, sourceMessage.mediaCaption.orEmpty())
                                                        }
                                                    } else {
                                                        dispatchSecureMessage(
                                                            destination, UUID.randomUUID().toString(),
                                                            sourceMessage.content, null, java.time.Instant.now().toString()
                                                        )
                                                    }
                                                    sentCount++
                                                }
                                                forwardingTarget = null
                                                forwardingSelectedChatIds = emptySet()
                                                notice = "Сообщение отправлено в выбранные чаты: $sentCount"
                                            } catch (e: Exception) {
                                                error = e.message ?: "Не удалось переслать сообщение"
                                            } finally {
                                                forwardingBusy = false
                                            }
                                        }
                                    }
                                ) { Text("Переслать (${forwardingSelectedChatIds.size})") }
                            },
                            dismissButton = {
                                TextButton(
                                    enabled = !forwardingBusy,
                                    onClick = {
                                        forwardingTarget = null
                                        forwardingSelectedChatIds = emptySet()
                                    }
                                ) { Text("Отмена") }
                            }
                        )
                    }
                    if (editingTarget != null) {
                        AlertDialog(
                            onDismissRequest = { editingTarget = null },
                            title = { Text("Изменить сообщение") },
                            text = { OutlinedTextField(value = editDraft, onValueChange = { editDraft = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5) },
                            confirmButton = { TextButton(onClick = {
                                val target = editingTarget!!
                                val chat = selected!!
                                scope.launch {
                                    try {
                                        if (target.encrypted) {
                                            val crypto = e2eeClient ?: throw IllegalStateException("Защищённое устройство недоступно")
                                            api.editEncryptedMessage(chat.id, crypto, target.id, editDraft, chat.kind == "group", target.replyToId)
                                        } else {
                                            api.editMessage(target.id, editDraft)
                                        }
                                        messages = api.messages(chat.id, e2eeClient)
                                        messageReactions = api.messageReactions(chat.id)
                                        pinnedMessageId = api.pinnedMessage(chat.id)
                                        editingTarget = null
                                    } catch (e: Exception) { error = e.message ?: "Не удалось изменить сообщение" }
                                }
                            }) { Text("Сохранить") } },
                            dismissButton = { TextButton(onClick = { editingTarget = null }) { Text("Отмена") } }
                        )
                    }
                    val chat = selected!!
                    if (chatThemeOpen) {
                        val wallpaperOptions = listOf("Фиолетовая ночь" to Color(0xFF100C1D), "Графит" to Color(0xFF171D27), "Сланец" to Color(0xFF1B2634), "Синий вечер" to Color(0xFF101D32), "Лес" to Color(0xFF14251F), "Бордовый" to Color(0xFF2A1922))
                        val bubbleOptions = listOf("Фиолетовый" to Color(0xFF6945C7), "Лавандовый" to Color(0xFF7657B8), "Синий" to Color(0xFF304B83), "Голубой" to Color(0xFF245C78), "Изумрудный" to Color(0xFF23634F), "Розовый" to Color(0xFF79445F), "Графит" to Color(0xFF414B59))
                        AlertDialog(
                            onDismissRequest = { chatThemeOpen = false },
                            containerColor = Surface1,
                            title = { Text("Оформление чата", color = MainText, fontWeight = FontWeight.Bold) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("Фон переписки", color = MainText, fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                        wallpaperOptions.forEach { (label, color) ->
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(54.dp).clickable {
                                                chatWallpaperColor = color
                                                selected?.id?.let { id -> context.getSharedPreferences("nexura_chat_prefs_${store.userId.orEmpty()}", Context.MODE_PRIVATE).edit().putInt("wallpaper_$id", color.toArgb()).apply() }
                                            }) {
                                                Box(Modifier.size(38.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                                                    if (chatWallpaperColor == color) Icon(Icons.Default.Check, "Выбрано", tint = Color.White, modifier = Modifier.size(17.dp))
                                                }
                                                Text(label, color = SecondaryText, fontSize = 9.sp, maxLines = 1)
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = Surface2)
                                    Text("Цвет ваших сообщений", color = MainText, fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                        bubbleOptions.forEach { (label, color) ->
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(54.dp).clickable {
                                                chatBubbleColor = color
                                                selected?.id?.let { id -> context.getSharedPreferences("nexura_chat_prefs_${store.userId.orEmpty()}", Context.MODE_PRIVATE).edit().putInt("bubble_$id", color.toArgb()).apply() }
                                            }) {
                                                Box(Modifier.size(38.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                                                    if (chatBubbleColor == color) Icon(Icons.Default.Check, "Выбрано", tint = Color.White, modifier = Modifier.size(17.dp))
                                                }
                                                Text(label, color = SecondaryText, fontSize = 9.sp, maxLines = 1)
                                            }
                                        }
                                    }
                                    Text("Настройки сохраняются только для этого чата и не меняют оформление у собеседника.", color = SecondaryText, fontSize = 11.sp)
                                }
                            },
                            confirmButton = { TextButton(onClick = { chatThemeOpen = false }) { Text("Готово") } },
                            dismissButton = { TextButton(onClick = {
                                chatWallpaperColor = Color(0xFF0D1118)
                                chatBubbleColor = Color(0xFF304B83)
                                selected?.id?.let { id -> context.getSharedPreferences("nexura_chat_prefs_${store.userId.orEmpty()}", Context.MODE_PRIVATE).edit().remove("wallpaper_$id").remove("bubble_$id").apply() }
                            }) { Text("Сбросить") } }
                        )
                    }
                    if (chatGallery != null) {
                        Dialog(onDismissRequest = { chatGallery = null }) {
                            Surface(shape = RoundedCornerShape(22.dp), color = Surface1, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
                                Column(Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Медиа чата", color = MainText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { chatGallery = null }) { Icon(Icons.Default.Close, "Закрыть", tint = SecondaryText) }
                                    }
                                    Text("Все вложения и ссылки в одном месте", color = SecondaryText, fontSize = 12.sp)
                                    Spacer(Modifier.height(10.dp))
                                    val allGalleryItems = chatGallery.orEmpty()
                                    val galleryCategories = listOf("Все", "Фото", "Видео", "Голосовые", "Файлы", "Ссылки")
                                    val galleryItems = allGalleryItems.filter { item ->
                                        val isLink = item.mediaKind == null && Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).containsMatchIn(item.content)
                                        when (galleryCategory) {
                                            "Фото" -> item.mediaKind == "image"
                                            "Видео" -> item.mediaKind == "video"
                                            "Голосовые" -> item.mediaKind == "audio"
                                            "Файлы" -> item.mediaKind == "file" || item.messageType == "file"
                                            "Ссылки" -> isLink
                                            else -> item.mediaKind in setOf("image", "video", "audio", "file") || item.messageType == "file" || isLink
                                        }
                                    }
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                                        items(galleryCategories) { category ->
                                            FilterChip(
                                                selected = galleryCategory == category,
                                                onClick = { galleryCategory = category },
                                                label = { Text(category, fontSize = 12.sp) }
                                            )
                                        }
                                    }
                                    if (galleryItems.isEmpty()) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(if (allGalleryItems.isEmpty()) "В этом чате пока нет вложений" else "В этой категории пока пусто", color = SecondaryText)
                                        }
                                    } else {
                                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            items(galleryItems, key = { it.id }) { item ->
                                                val linkUrl = if (item.mediaKind == null) Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).find(item.content)?.value else null
                                                Row(
                                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2)
                                                        .clickable {
                                                            when (item.mediaKind) {
                                                                "video", "image" -> {
                                                                    mediaViewerUri = Uri.parse(item.content)
                                                                    mediaViewerType = item.mediaKind ?: "image"
                                                                    chatGallery = null
                                                                }
                                                                "audio" -> {
                                                                    chatGallery = null
                                                                    chatSearchOpen = false
                                                                    chatSearchQuery = ""
                                                                    scrollToMessageId = item.id
                                                                    scope.launch {
                                                                        try {
                                                                            if (messages.none { it.id == item.id }) {
                                                                                messages = (messages + api.messages(chat.id, e2eeClient, beforeCreatedAt = item.createdAt)).distinctBy { it.id }.sortedBy { it.createdAt }
                                                                            }
                                                                        } catch (e: Exception) { error = e.message ?: "Не удалось открыть сообщение" }
                                                                    }
                                                                }
                                                                "file" -> {
                                                                    try {
                                                                        val local = File(Uri.parse(item.content).path ?: throw IOException("Файл недоступен"))
                                                                        val contentUri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", local)
                                                                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(contentUri, item.mediaMimeType ?: "*/*")
                                                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                                        context.startActivity(intent)
                                                                    } catch (e: Exception) {
                                                                        notice = "Не найдено приложение для открытия файла или файл недоступен"
                                                                    }
                                                                }
                                                                else -> if (item.messageType == "file" && item.content.startsWith("http")) {
                                                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.content))) }
                                                                } else if (linkUrl != null) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl))) }
                                                            }
                                                        }.padding(10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    when (item.mediaKind) {
                                                        "image" -> AsyncImage(model = item.content, contentDescription = "Фото", modifier = Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                                        "video" -> Box(Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, "Видео", tint = Color.White) }
                                                        "audio" -> Box(Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)).background(Surface1), contentAlignment = Alignment.Center) { Icon(Icons.Default.Mic, "Голосовое", tint = Accent) }
                                                        "file" -> Box(Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)).background(Surface1), contentAlignment = Alignment.Center) { Icon(Icons.Default.Description, "Документ", tint = Accent) }
                                                        else -> Box(Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)).background(Surface1), contentAlignment = Alignment.Center) {
                                                            Icon(if (item.messageType == "file") Icons.Default.Description else Icons.Default.Link, if (item.messageType == "file") "Документ" else "Ссылка", tint = Accent)
                                                        }
                                                    }
                                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                                        Text(when(item.mediaKind) { "audio" -> "Голосовое сообщение"; "video" -> "Видео"; "image" -> "Фотография"; "file" -> item.mediaFileName ?: "Документ"; else -> if (item.messageType == "file") "Документ" else "Ссылка" }, color = MainText, fontWeight = FontWeight.Medium, maxLines = 1)
                                                        Text(linkUrl ?: item.mediaCaption ?: messageTimeLabel(item.createdAt), color = SecondaryText, fontSize = 12.sp, maxLines = 2)
                                                    }
                                                    IconButton(onClick = {
                                                        chatGallery = null
                                                        chatSearchOpen = false
                                                        chatSearchQuery = ""
                                                        scrollToMessageId = item.id
                                                        scope.launch {
                                                            try {
                                                                if (messages.none { it.id == item.id }) {
                                                                    messages = (messages + api.messages(chat.id, e2eeClient, beforeCreatedAt = item.createdAt)).distinctBy { it.id }.sortedBy { it.createdAt }
                                                                }
                                                            } catch (e: Exception) { error = e.message ?: "Не удалось открыть сообщение" }
                                                        }
                                                    }) { Icon(Icons.Default.ArrowForward, "Перейти к сообщению", tint = SecondaryText) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (mediaViewerUri != null) {
                        Dialog(
                            onDismissRequest = { mediaViewerUri = null },
                            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
                        ) {
                            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                                if (mediaViewerType == "video") {
                                    AndroidView(
                                        factory = { ctx ->
                                            VideoView(ctx).apply {
                                                val playerControls = MediaController(ctx)
                                                playerControls.setAnchorView(this)
                                                setMediaController(playerControls)
                                                setVideoURI(mediaViewerUri)
                                                setOnPreparedListener { player -> player.isLooping = false; start() }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    AsyncImage(
                                        model = mediaViewerUri,
                                        contentDescription = "Фото на весь экран",
                                        modifier = Modifier.fillMaxSize().clickable { mediaViewerUri = null },
                                        contentScale = ContentScale.Fit
                                    )
                                }
                                IconButton(
                                    onClick = { mediaViewerUri = null },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 12.dp)
                                        .background(Color(0x99000000), CircleShape)
                                ) { Text("×", color = Color.White, fontSize = 28.sp) }
                            }
                        }
                    }
                    if (trustCandidate != null && trustPeerId != null) {
                        val remote = trustCandidate!!
                        val client = e2eeClient
                        val ownBundle = runCatching { JSONObject(client?.ensureDevice() ?: "{}") }.getOrNull()
                        val ownCurve = ownBundle?.optString("curve25519").orEmpty()
                        val ownEd = ownBundle?.optString("ed25519").orEmpty()
                        val sharedFingerprint = if (client != null && ownCurve.isNotBlank() && ownEd.isNotBlank()) {
                            runCatching {
                                client.conversationFingerprint(
                                    ownCurve, ownEd, remote.curve25519Key, remote.ed25519Key
                                )
                            }.getOrElse { "Недоступен" }
                        } else "Недоступен"
                        val remoteFingerprint = client?.deviceFingerprint(remote.curve25519Key, remote.ed25519Key) ?: "Недоступен"
                        val ownFingerprint = if (ownCurve.isNotBlank() && ownEd.isNotBlank()) {
                            client?.deviceFingerprint(ownCurve, ownEd) ?: "Недоступен"
                        } else "Недоступен"
                        AlertDialog(
                            onDismissRequest = { trustCandidate = null; trustPeerId = null; groupTrustQueue = emptyList() },
                            title = { Text("Проверка устройства") },
                            text = {
                                Column {
                                    Text("Этот общий код безопасности должен полностью совпадать у вас и собеседника в одном и том же чате. Сверьте его лично или по видеосвязи. Если код отличается — не подтверждайте устройство.")
                                    Text("ОБЩИЙ КОД ЧАТА", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
                                    Text(sharedFingerprint, color = Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(
                                        if (groupTrustQueue.isNotEmpty()) "Устройство участника: ${trustPeerId}" else "Отпечаток устройства собеседника",
                                        color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)
                                    )
                                    Text(remoteFingerprint, color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text("Отпечаток вашего устройства", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                                    Text(ownFingerprint, color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    try {
                                        client ?: throw IllegalStateException("Ключ устройства не готов")
                                        client.trustRemoteDevice(trustPeerId!!, remote.deviceId, remote.curve25519Key, remote.ed25519Key)
                                        notice = "Устройство подтверждено. Сообщения этому ключу будут шифроваться."
                                        if (groupTrustQueue.isNotEmpty()) {
                                            val remaining = groupTrustQueue.drop(1)
                                            groupTrustQueue = remaining
                                            if (remaining.isNotEmpty()) {
                                                trustPeerId = remaining.first().first
                                                trustCandidate = remaining.first().second
                                            } else {
                                                trustCandidate = null
                                                trustPeerId = null
                                                notice = "Все устройства участников группы подтверждены"
                                            }
                                        } else {
                                            trustCandidate = null
                                            trustPeerId = null
                                        }
                                    } catch (e: Exception) { error = e.message ?: "Не удалось сохранить доверие" }
                                }) { Text("Я сверил отпечаток") }
                            },
                            dismissButton = {
                                TextButton(onClick = { trustCandidate = null; trustPeerId = null; groupTrustQueue = emptyList() }) { Text("Отмена") }
                            }
                        )
                    }
                    confirmBlockProfile?.let { profile ->
                        AlertDialog(
                            onDismissRequest = { confirmBlockProfile = null },
                            title = { Text("Заблокировать пользователя?") },
                            text = { Text("${profile.displayName} не сможет писать вам в личном чате. Вы также не сможете отправлять ему сообщения, а существующая переписка будет скрыта.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmBlockProfile = null
                                    scope.launch {
                                        try {
                                            api.blockUser(profile.id)
                                            viewedProfile = null
                                            viewedPresence = null
                                            selected = null
                                            messages = emptyList()
                                            chats = api.chats()
                                            notice = "Пользователь заблокирован."
                                        } catch (e: Exception) { error = e.message ?: "Не удалось заблокировать пользователя" }
                                    }
                                }) { Text("Заблокировать", color = Danger) }
                            },
                            dismissButton = { TextButton(onClick = { confirmBlockProfile = null }) { Text("Отмена") } }
                        )
                    }
                    viewedProfile?.let { profile ->
                        Dialog(onDismissRequest = { viewedProfile = null; profileMedia = emptyList() }) {
                            Card(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 650.dp),
                                colors = CardDefaults.cardColors(containerColor = Surface1),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                        Text("Профиль", color = MainText, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterStart))
                                        IconButton(onClick = { viewedProfile = null; profileMedia = emptyList() }) { Text("×", color = SecondaryText, fontSize = 24.sp) }
                                    }
                                    Avatar(profile.displayName, profile.avatar)
                                    Text(profile.displayName, color = MainText, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                                    if (profile.username.isNotBlank()) Text("@${profile.username}", color = SecondaryText, fontSize = 13.sp)
                                    viewedPresence?.takeIf { it.isVisible }?.let { presence ->
                                        Text(if (presence.isOnline) "● В сети" else if (presence.lastSeenAt != null) "Был(а) в ${messageTimeLabel(presence.lastSeenAt)}" else "Не в сети",
                                            color = if (presence.isOnline) Success else SecondaryText, fontSize = 12.sp,
                                            modifier = Modifier.padding(top = 4.dp))
                                    }
                                    if (profile.age != null) Text("${profile.age} лет", color = SecondaryText, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                                    Text(profile.bio.ifBlank { "Пользователь пока ничего не рассказал о себе" }, color = MainText, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                                    OutlinedButton(onClick = { confirmBlockProfile = profile }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) {
                                        Icon(Icons.Default.Block, null, modifier = Modifier.size(17.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Text("Заблокировать пользователя")
                                    }
                                    HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Surface2)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Медиа из чата", color = MainText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        Text("${profileMedia.size}", color = SecondaryText, fontSize = 12.sp)
                                    }
                                    if (loadingProfile) {
                                        CircularProgressIndicator(Modifier.padding(20.dp), color = Accent)
                                    } else if (profileMedia.isEmpty()) {
                                        Text("Фото и видео пока нет", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(vertical = 16.dp))
                                    } else {
                                        profileMedia.chunked(3).forEach { group ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                group.forEach { item ->
                                                    Box(
                                                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                                                            .background(Surface2)
                                                            .clickable {
                                                                mediaViewerUri = Uri.parse(item.content)
                                                                mediaViewerType = if (item.mediaKind == "video") "video" else "image"
                                                            }
                                                    ) {
                                                        AsyncImage(model = item.content, contentDescription = "Открыть медиа из переписки", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                                        if (item.mediaKind == "video") Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(30.dp))
                                                    }
                                                }
                                                repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Keep the chat viewport below the status bar and resize it above the keyboard.
                    // The message area takes the remaining height; the composer stays at the bottom.
                    Box(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        Row(
                            Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Surface1, Color(0xFF21143B)))).padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { selected = null; peerReadReceiptAt = null; messages = emptyList(); replyTarget = null; loadChats() }) {
                                Icon(Icons.Default.ArrowBack, "Назад к чатам", tint = MainText)
                            }
                            if (chat.kind == "group") {
                                ChatListAvatar(chat.title, chat.avatar, true, false)
                            } else {
                                Box(Modifier.clickable {
                                    val peerId = chat.peerUserId
                                    if (peerId != null) {
                                        loadingProfile = true
                                        scope.launch {
                                            try {
                                                viewedProfile = api.userProfile(peerId)
                                                viewedPresence = api.userPresence(peerId)
                                                profileMedia = api.messages(chat.id, e2eeClient, mediaOnly = true)
                                            } catch (e: Exception) { error = e.message ?: "Не удалось открыть профиль" }
                                            finally { loadingProfile = false }
                                        }
                                    } else error = "Не удалось определить собеседника"
                                }) { ChatListAvatar(chat.title, chat.avatar, false, false) }
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp).clickable(enabled = chat.kind == "direct") {
                                val peerId = chat.peerUserId
                                if (peerId != null) {
                                    loadingProfile = true
                                    scope.launch {
                                        try {
                                            viewedProfile = api.userProfile(peerId)
                                                viewedPresence = api.userPresence(peerId)
                                            profileMedia = api.messages(chat.id, e2eeClient, mediaOnly = true)
                                        } catch (e: Exception) { error = e.message ?: "Не удалось открыть профиль" }
                                        finally { loadingProfile = false }
                                    }
                                }
                            }) {
                                Text(chat.title, color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                                val presence = chatPresence?.takeIf { it.isVisible }
                                Text(
                                    when {
                                        chat.kind == "group" -> "Групповой чат"
                                        presence?.isOnline == true -> "● В сети"
                                        presence?.lastSeenAt != null -> "Был(а) в ${messageTimeLabel(presence.lastSeenAt)}"
                                        else -> "Личная переписка"
                                    },
                                    color = if (presence?.isOnline == true) Success else SecondaryText,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                            IconButton(onClick = { chatThemeOpen = true }) {
                                Icon(Icons.Default.Palette, "Оформление чата", tint = if (chatThemeOpen) Accent else SecondaryText)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        val client = e2eeClient ?: throw IllegalStateException("Ключ E2EE ещё не готов")
                                        if (chat.kind == "direct") {
                                            val peerId = api.directPeerUserId(chat.id)
                                            val devices = api.activeE2eeDevices(peerId)
                                            if (devices.isEmpty()) throw IllegalStateException("У собеседника пока нет активных устройств Nexura.")
                                            trustPeerId = peerId
                                            trustCandidate = devices.first()
                                        } else {
                                            val ownDeviceId = client.deviceId()
                                            val members = api.groupMemberIds(chat.id)
                                            val queue = members.flatMap { memberId ->
                                                val devices = api.activeE2eeDevices(memberId).filterNot { memberId == store.userId && it.deviceId == ownDeviceId }
                                                if (devices.isEmpty() && memberId != store.userId) throw IllegalStateException("У участника $memberId пока нет активного E2EE-устройства")
                                                devices.map { memberId to it }
                                            }.filter { (memberId, device) ->
                                                !client.isRemoteDeviceTrusted(memberId, device.deviceId, device.curve25519Key, device.ed25519Key)
                                            }
                                            if (queue.isEmpty()) {
                                                notice = "Все активные устройства участников уже подтверждены"
                                            } else {
                                                groupTrustQueue = queue
                                                trustPeerId = queue.first().first
                                                trustCandidate = queue.first().second
                                            }
                                        }
                                    } catch (e: Exception) {
                                        error = e.message ?: "Не удалось получить ключи устройств"
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Lock, if (chat.kind == "group") "Проверить устройства группы" else "Проверить устройство", tint = Accent)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        val media = api.messages(chat.id, e2eeClient, mediaOnly = true)
                                        val recentLinks = api.messages(chat.id, e2eeClient).filter {
                                            it.messageType == "text" && Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).containsMatchIn(it.content)
                                        }
                                        chatGallery = (media + recentLinks).distinctBy { it.id }.sortedByDescending { it.createdAt }
                                    } catch (e: Exception) { error = e.message ?: "Не удалось загрузить галерею чата" }
                                }
                            }) {
                                Icon(Icons.Default.Image, "Медиа чата", tint = if (chatGallery != null) Accent else SecondaryText)
                            }
                            IconButton(onClick = { chatSearchOpen = !chatSearchOpen; if (!chatSearchOpen) chatSearchQuery = "" }) {
                                Icon(Icons.Default.Search, "Поиск по переписке", tint = if (chatSearchOpen) Accent else SecondaryText)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        messages = api.messages(chat.id, e2eeClient)
                                        messageReactions = api.messageReactions(chat.id)
                                        pinnedMessageId = api.pinnedMessage(chat.id)
                                        canManagePins = api.canManageConversation(chat.id)
                                    } catch (e: Exception) { error = e.message ?: "Не удалось обновить чат" }
                                }
                            }) {
                                Icon(Icons.Default.Refresh, "Обновить сообщения", tint = SecondaryText)
                            }
                        }
                        if (chatSearchOpen) {
                            OutlinedTextField(
                                value = chatSearchQuery,
                                onValueChange = { chatSearchQuery = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                placeholder = { Text("Найти сообщение в этом чате") },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                trailingIcon = {
                                    if (chatSearchQuery.isNotEmpty()) {
                                        IconButton(onClick = { chatSearchQuery = "" }) {
                                            Icon(Icons.Default.Close, "Очистить поиск")
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MainText,
                                    unfocusedTextColor = MainText,
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Surface2
                                )
                            )
                        }
                        if (pinnedMessageId != null) {
                            val pinned = messages.firstOrNull { it.id == pinnedMessageId }
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(10.dp)).background(Surface2).padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📌", fontSize = 16.sp)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text("Закреплённое сообщение", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Text(pinned?.content?.take(90) ?: "Откройте чат, чтобы загрузить сообщение", color = MainText, fontSize = 12.sp, maxLines = 1)
                                }
                                if (canManagePins) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            try {
                                                api.setPinnedMessage(chat.id, null)
                                                pinnedMessageId = null
                                            } catch (e: Exception) { error = e.message ?: "Не удалось открепить" }
                                        }
                                    }) { Text("Снять", color = Accent) }
                                }
                            }
                        }
                        HorizontalDivider(color = Surface2)
                        val visibleMessages = remember(messages, chatSearchQuery) {
                            if (chatSearchQuery.isBlank()) messages
                            else messages.filter { it.content.contains(chatSearchQuery.trim(), ignoreCase = true) }
                        }
                        if (messages.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(if (busy) "Загружаем сообщения…" else "Напишите первое сообщение 👋", color = SecondaryText, fontSize = 13.sp)
                            }
                        } else {
                            if (visibleMessages.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("Ничего не найдено", color = SecondaryText, fontSize = 13.sp)
                                }
                            } else {
                                val messageListState = rememberLazyListState()
                                var suppressNextAutoScroll by remember(chat.id) { mutableStateOf(false) }
                                var initialScrollCompleted by remember(chat.id) { mutableStateOf(false) }
                                LaunchedEffect(chat.id, messageListState) {
                                    snapshotFlow { messageListState.firstVisibleItemIndex to messageListState.isScrollInProgress }
                                        .collect { (firstVisibleIndex, scrolling) ->
                                            if (initialScrollCompleted && scrolling && firstVisibleIndex <= 1 && hasMoreMessages && !loadingOlderMessages && chatSearchQuery.isBlank()) {
                                                val cursor = messages.minWithOrNull(compareBy<MessageItem> { it.createdAt }.thenBy { it.id })
                                                if (cursor != null) {
                                                    loadingOlderMessages = true
                                                    try {
                                                        val anchorIndex = messageListState.firstVisibleItemIndex
                                                        val anchorOffset = messageListState.firstVisibleItemScrollOffset
                                                        val older = api.messages(
                                                            chat.id, e2eeClient,
                                                            beforeCreatedAt = cursor.createdAt,
                                                            beforeMessageId = cursor.id,
                                                            pageSize = 30
                                                        )
                                                        if (selected?.id == chat.id) {
                                                            val knownIds = messages.asSequence().map { it.id }.toHashSet()
                                                            val additions = older.filterNot { it.id in knownIds }
                                                            hasMoreMessages = older.size >= 30 && additions.isNotEmpty()
                                                            if (additions.isNotEmpty()) {
                                                                suppressNextAutoScroll = true
                                                                messages = (additions + messages).distinctBy { it.id }
                                                                    .sortedWith(compareBy<MessageItem> { it.createdAt }.thenBy { it.id })
                                                                withContext(Dispatchers.IO) {
                                                                    localCache.saveMessages(store.userId.orEmpty(), chat.id, messages)
                                                                }
                                                                withFrameNanos { }
                                                                messageListState.scrollToItem(anchorIndex + additions.size, anchorOffset)
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        error = e.message ?: "Не удалось загрузить предыдущие сообщения"
                                                    } finally {
                                                        loadingOlderMessages = false
                                                    }
                                                }
                                            }
                                        }
                                }
                                LaunchedEffect(chat.id, visibleMessages.size, chatSearchQuery, scrollToMessageId) {
                                    if (suppressNextAutoScroll) {
                                        suppressNextAutoScroll = false
                                    } else if (visibleMessages.isNotEmpty()) {
                                        val targetIndex = scrollToMessageId?.let { id -> visibleMessages.indexOfFirst { it.id == id } } ?: -1
                                        // Avoid expensive fling animation on every sync, especially on low-memory phones.
                                        if (lowRamDevice) {
                                            messageListState.scrollToItem(if (targetIndex >= 0) targetIndex else visibleMessages.lastIndex)
                                        } else {
                                            messageListState.animateScrollToItem(if (targetIndex >= 0) targetIndex else visibleMessages.lastIndex)
                                        }
                                        if (targetIndex >= 0) scrollToMessageId = null
                                        initialScrollCompleted = true
                                    }
                                }
                                LazyColumn(
                                    state = messageListState,
                                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).clip(RoundedCornerShape(16.dp)).background(chatWallpaperColor),
                                    contentPadding = PaddingValues(bottom = 180.dp)
                                ) {
                                    items(visibleMessages, key = { it.id }, contentType = { it.messageType }) { msg ->
                                    val mine = msg.senderId == store.userId
                                    val isRead = mine && chat.kind == "direct" && peerReadReceiptAt?.let { it >= msg.createdAt } == true
                                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                                        Column(
                                            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(
                                                topStart = 20.dp, topEnd = 20.dp,
                                                bottomStart = if (mine) 20.dp else 6.dp,
                                                bottomEnd = if (mine) 6.dp else 20.dp
                                            )).background(if (mine) chatBubbleColor else Color(0xFF211A35))
                                                .animateContentSize(animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f))
                                                .combinedClickable(
                                                    onClick = { },
                                                    onLongClick = { openMessageMenu = msg.id }
                                                )
                                                .padding(horizontal = 15.dp, vertical = 12.dp)
                                        ) {
                                            if (msg.encrypted) {
                                                Text("🔒 Зашифровано E2EE", color = Success, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
                                            } else if (msg.securityWarning) {
                                                Text("⚠ Защита не подтверждена", color = Color(0xFFFFC56E), fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
                                            }
                                            if (msg.replyToId != null) {
                                                val parent = messages.firstOrNull { it.id == msg.replyToId }
                                                Row(
                                                    Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                                        .clip(RoundedCornerShape(9.dp))
                                                        .background(Surface1.copy(alpha = 0.88f))
                                                        .clickable { if (parent != null) scrollToMessageId = parent.id }
                                                        .padding(horizontal = 9.dp, vertical = 7.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
                                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                                        Text("Ответ на сообщение", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                                        Text(parent?.content?.take(85) ?: "Исходное сообщение", color = SecondaryText, fontSize = 11.sp, maxLines = 2)
                                                    }
                                                }
                                            }
                                            if (msg.messageType == "call" && msg.encrypted) {
                                                val call = runCatching { org.json.JSONObject(msg.content) }.getOrNull()
                                                val outgoing = call?.optString("direction") == "outgoing"
                                                val duration = call?.optLong("duration_seconds", 0L) ?: 0L
                                                val minutes = duration / 60L
                                                val seconds = duration % 60L
                                                val result = call?.optString("result").orEmpty()
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF253C56)), contentAlignment = Alignment.Center) {
                                                        Icon(Icons.Default.Call, null, tint = Color.White)
                                                    }
                                                    Column {
                                                        Text(
                                                            (if (outgoing) "Исходящий " else "Входящий ") + "аудиозвонок",
                                                            color = MainText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                                                        )
                                                        Text(
                                                            (if (result == "completed") "Разговор · " else "Без соединения · ") + String.format("%02d:%02d", minutes, seconds),
                                                            color = SecondaryText, fontSize = 12.sp
                                                        )
                                                    }
                                                }
                                            } else if (msg.messageType == "media" && msg.encrypted) {
                                                val mediaUri = Uri.parse(msg.content)
                                                if (msg.mediaKind == "audio") {
                                                    AudioMessagePlayer(mediaUri, msg.mediaCaption, msg.mediaFileName, msg.mediaMimeType)
                                                } else if (msg.mediaKind == "video") {
                                                    Box(
                                                        Modifier.width(230.dp).height(150.dp).clip(RoundedCornerShape(12.dp))
                                                            .background(Color.Black).clickable { mediaViewerUri = mediaUri; mediaViewerType = "video" },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(Icons.Default.PlayArrow, "Открыть видео", tint = Color.White, modifier = Modifier.size(54.dp))
                                                        Text("Видео", color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                                                    }
                                                    msg.mediaCaption?.takeIf { it.isNotBlank() }?.let {
                                                        Text(it, color = MainText, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                                                    }
                                                } else if (msg.mediaKind == "file") {
                                                    Row(
                                                        Modifier.width(250.dp).clip(RoundedCornerShape(16.dp))
                                                            .background(Brush.linearGradient(listOf(Color(0xFF29203E), Surface2)))
                                                            .border(1.dp, Accent.copy(alpha = 0.20f), RoundedCornerShape(16.dp))
                                                            .clickable {
                                                                try {
                                                                    val local = File(mediaUri.path ?: throw IOException("Файл недоступен"))
                                                                    val contentUri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", local)
                                                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(contentUri, msg.mediaMimeType ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                                                } catch (_: Exception) { notice = "Не удалось открыть документ. Установите приложение для этого формата." }
                                                            }.padding(12.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Box(
                                                            Modifier.size(46.dp).clip(RoundedCornerShape(13.dp))
                                                                .background(Brush.linearGradient(listOf(Accent.copy(alpha = 0.24f), Accent2.copy(alpha = 0.12f)))),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(Icons.Default.Description, "Документ", tint = Accent, modifier = Modifier.size(26.dp))
                                                        }
                                                        Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                                            Text(msg.mediaFileName ?: "Документ", color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2)
                                                            Text(msg.mediaMimeType ?: "ФАЙЛ · E2EE", color = SecondaryText, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                                                            Text("Нажмите, чтобы открыть", color = Accent, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                                                        }
                                                    }
                                                    msg.mediaCaption?.takeIf { it.isNotBlank() }?.let {
                                                        Text(it, color = MainText, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                                                    }
                                                } else {
                                                    AsyncImage(
                                                        model = mediaUri,
                                                        contentDescription = "Фото",
                                                        modifier = Modifier.width(230.dp).heightIn(max = 250.dp).clip(RoundedCornerShape(12.dp))
                                                            .clickable { mediaViewerUri = mediaUri; mediaViewerType = "image" },
                                                        contentScale = ContentScale.Crop
                                                    )
                                                    msg.mediaCaption?.takeIf { it.isNotBlank() }?.let {
                                                        Text(it, color = MainText, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                                                    }
                                                }
                                            } else if (msg.messageType == "image") {
                                                AsyncImage(model = msg.content, contentDescription = "Изображение", modifier = Modifier.width(230.dp).heightIn(max = 250.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                            } else {
                                                Text(msg.content, color = MainText, fontSize = 15.sp, lineHeight = 21.sp)
                                            }
                                            Row(
                                                Modifier.fillMaxWidth().padding(top = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                if (msg.editedAt != null) Text("изменено · ", color = Color(0xFFB7C4D9), fontSize = 10.sp)
                                                Text(messageTimeLabel(msg.createdAt), color = Color(0xFFB7C4D9), fontSize = 10.sp)
                                                if (mine) {
                                                    when (messageDelivery[msg.id]) {
                                                        "sending" -> Text("◷", color = Color(0xFFB7C4D9), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                                                        "pending" -> Text("В очереди · авто", color = Color(0xFFB7C4D9), fontSize = 10.sp, modifier = Modifier.padding(start = 5.dp))
                                                        "failed" -> Text(
                                                            "Не отправлено · Повторить",
                                                            color = Danger,
                                                            fontSize = 10.sp,
                                                            modifier = Modifier.padding(start = 5.dp).clickable {
                                                                failedMessageRetries[msg.id]?.let { retry ->
                                                                    dispatchSecureMessage(retry.first, msg.id, retry.second, retry.third)
                                                                }
                                                            }
                                                        )
                                                        "sent" -> if (chat.kind == "direct") Text(
                                                            if (isRead) "✓✓" else "✓",
                                                            color = if (isRead) Accent else Color(0xFFB7C4D9),
                                                            fontSize = 11.sp,
                                                            modifier = Modifier.padding(start = 4.dp)
                                                        )
                                                        else -> if (chat.kind == "direct") Text(
                                                            if (isRead) "✓✓" else "✓",
                                                            color = if (isRead) Accent else Color(0xFFB7C4D9),
                                                            fontSize = 11.sp,
                                                            modifier = Modifier.padding(start = 4.dp)
                                                        )
                                                    }
                                                }
                                                Box {
                                                    IconButton(
                                                        onClick = { openMessageMenu = if (openMessageMenu == msg.id) null else msg.id },
                                                        modifier = Modifier.size(24.dp)
                                                    ) { Icon(Icons.Default.MoreVert, "Действия с сообщением", tint = Color(0xFFB7C4D9), modifier = Modifier.size(15.dp)) }
                                                    DropdownMenu(
                                                        expanded = openMessageMenu == msg.id,
                                                        onDismissRequest = { openMessageMenu = null },
                                                        modifier = Modifier.width(236.dp)
                                                            .background(Surface1)
                                                    ) {
                                                        Text(
                                                            "СООБЩЕНИЕ",
                                                            color = SecondaryText,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            letterSpacing = 1.sp,
                                                            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 5.dp)
                                                        )
                                                        DropdownMenuItem(
                                                            text = { Text("Ответить") },
                                                            leadingIcon = { Text("↩", color = Accent, fontSize = 18.sp) },
                                                            onClick = { replyTarget = msg; openMessageMenu = null }
                                                        )
                                                        if (msg.encrypted && !msg.securityWarning && msg.messageType in setOf("text", "media") && chats.any { it.id != chat.id }) {
                                                            DropdownMenuItem(
                                                                text = { Text("Переслать") },
                                                                leadingIcon = { Icon(Icons.Default.ArrowForward, null, tint = Accent) },
                                                                onClick = {
                                                                    forwardingTarget = msg
                                                                    forwardingSelectedChatIds = emptySet()
                                                                    forwardingSearch = ""
                                                                    openMessageMenu = null
                                                                }
                                                            )
                                                        }
                                                        DropdownMenuItem(
                                                            text = { Text("Копировать текст") },
                                                            leadingIcon = { Text("▣", color = Accent, fontSize = 17.sp) },
                                                            onClick = {
                                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                                clipboard.setPrimaryClip(ClipData.newPlainText("Nexura message", msg.content))
                                                                Toast.makeText(context, "Текст скопирован", Toast.LENGTH_SHORT).show()
                                                                openMessageMenu = null
                                                            }
                                                        )
                                                        HorizontalDivider(color = SecondaryText.copy(alpha = 0.14f))
                                                        Text(
                                                            "РЕАКЦИЯ",
                                                            color = SecondaryText,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            letterSpacing = 1.sp,
                                                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 3.dp)
                                                        )
                                                        Row(
                                                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            listOf("👍", "❤️", "😂", "😮", "😢", "🔥", "👏", "🙏").forEach { emoji ->
                                                                AnimatedReactionPickerItem(
                                                                    emoji = emoji,
                                                                    onClick = {
                                                                        openMessageMenu = null
                                                                        scope.launch {
                                                                            try {
                                                                                api.toggleMessageReaction(chat.id, msg.id, emoji)
                                                                                messageReactions = api.messageReactions(chat.id)
                                                                            } catch (e: Exception) { error = e.message ?: "Не удалось поставить реакцию" }
                                                                        }
                                                                    }
                                                                )
                                                            }
                                                        }
                                                        if (canManagePins) {
                                                            HorizontalDivider(color = SecondaryText.copy(alpha = 0.14f))
                                                            DropdownMenuItem(
                                                                text = { Text(if (pinnedMessageId == msg.id) "Открепить" else "Закрепить в чате") },
                                                                leadingIcon = { Text("⌖", color = Accent, fontSize = 18.sp) },
                                                                onClick = {
                                                                    openMessageMenu = null
                                                                    scope.launch {
                                                                        try {
                                                                            api.setPinnedMessage(chat.id, if (pinnedMessageId == msg.id) null else msg.id)
                                                                            pinnedMessageId = api.pinnedMessage(chat.id)
                                                                        } catch (e: Exception) { error = e.message ?: "Не удалось изменить закрепление" }
                                                                    }
                                                                }
                                                            )
                                                        }
                                                        if (mine && msg.messageType == "text") {
                                                            HorizontalDivider(color = SecondaryText.copy(alpha = 0.14f))
                                                            Text(
                                                                "УПРАВЛЕНИЕ",
                                                                color = SecondaryText,
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                letterSpacing = 1.sp,
                                                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 3.dp)
                                                            )
                                                            if (!msg.securityWarning && (pinnedMessageId != msg.id || canManagePins)) {
                                                                DropdownMenuItem(
                                                                    text = { Text("Изменить") },
                                                                    leadingIcon = { Text("✎", color = Accent, fontSize = 17.sp) },
                                                                    onClick = { editingTarget = msg; editDraft = msg.content; openMessageMenu = null }
                                                                )
                                                            }
                                                            DropdownMenuItem(
                                                                text = { Text("Удалить", color = Danger) },
                                                                leadingIcon = { Text("⌫", color = Danger, fontSize = 17.sp) },
                                                                onClick = {
                                                                    openMessageMenu = null
                                                                    scope.launch {
                                                                        try {
                                                                            api.deleteMessage(msg.id)
                                                                            messages = messages.filterNot { it.id == msg.id }
                                                                            messageReactions = messageReactions - msg.id
                                                                            if (pinnedMessageId == msg.id) pinnedMessageId = null
                                                                        } catch (e: Exception) { error = e.message ?: "Не удалось удалить" }
                                                                    }
                                                                }
                                                            )
                                                        }
                                                    }}
                                                }
                                            }
                                            val reactionsForMessage = messageReactions[msg.id].orEmpty()
                                            AnimatedVisibility(
                                                visible = reactionsForMessage.isNotEmpty(),
                                                enter = fadeIn() + expandVertically(),
                                                exit = fadeOut() + shrinkVertically()
                                            ) {
                                                Row(
                                                    Modifier.fillMaxWidth().padding(top = 7.dp),
                                                    horizontalArrangement = Arrangement.Start,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    reactionsForMessage.forEach { reaction ->
                                                        AnimatedReactionChip(
                                                            emoji = reaction.emoji,
                                                            count = reaction.count,
                                                            selected = reaction.selectedByMe,
                                                            onClick = {
                                                                scope.launch {
                                                                    try {
                                                                        api.toggleMessageReaction(chat.id, msg.id, reaction.emoji)
                                                                        messageReactions = api.messageReactions(chat.id)
                                                                    } catch (e: Exception) { error = e.message ?: "Не удалось изменить реакцию" }
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Column(
                            Modifier.fillMaxWidth()
                        ) {
                            if (recordingVoice) Text("● Запись голосового сообщения… нажмите ■, чтобы отправить", color = Danger, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp))
                            if (error.isNotBlank()) Text(error, color = Danger, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp))
                            Column(
                                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Surface1, Bg))).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 9.dp)
                            ) {
                            if (replyTarget != null) {
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                                        .background(Brush.horizontalGradient(listOf(Color(0xFF251A3D), Surface2)))
                                        .border(1.dp, Accent.copy(alpha = 0.28f), RoundedCornerShape(13.dp))
                                        .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.width(3.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
                                    Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                                        Text("ОТВЕТ НА СООБЩЕНИЕ", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                                        Text(replyTarget!!.content.take(90).ifBlank { "Вложение" }, color = MainText, fontSize = 12.sp, maxLines = 2)
                                    }
                                    IconButton(onClick = { replyTarget = null }, modifier = Modifier.size(34.dp)) {
                                        Icon(Icons.Default.Close, "Отменить ответ", tint = SecondaryText, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            if (pendingMediaUri != null) {
                                Row(
                                    Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                        .clip(RoundedCornerShape(12.dp)).background(Surface2).padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val isVideo = context.contentResolver.getType(pendingMediaUri!!)?.startsWith("video/") == true
                                    AsyncImage(
                                        model = pendingMediaUri,
                                        contentDescription = if (isVideo) "Выбранное видео" else "Выбранное фото",
                                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        Text(if (isVideo) "Видео прикреплено" else "Фото прикреплено", color = MainText, fontSize = 13.sp)
                                        Text("Добавьте подпись или отправьте как есть", color = SecondaryText, fontSize = 11.sp)
                                    }
                                    IconButton(onClick = { pendingMediaUri = null }, modifier = Modifier.size(32.dp)) {
                                        Text("×", color = SecondaryText, fontSize = 22.sp)
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                IconButton(
                                    onClick = { mediaPicker.launch(arrayOf("*/*")) },
                                    enabled = !sendingImage && !recordingVoice,
                                    modifier = Modifier.size(44.dp).clip(CircleShape)
                                        .background(Surface2).align(Alignment.Bottom)
                                ) {
                                    if (sendingImage) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = Accent)
                                    else Icon(Icons.Default.Add, "Прикрепить фото или файл", tint = Accent, modifier = Modifier.size(23.dp))
                                }
                                Spacer(Modifier.width(7.dp))
                                OutlinedTextField(
                                    value = draft,
                                    onValueChange = {
                                        draft = it
                                        chatDrafts[chat.id] = it
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 132.dp),
                                    placeholder = { Text("Сообщение", fontSize = 14.sp, color = SecondaryText) },
                                    minLines = 1,
                                    maxLines = 4,
                                    shape = RoundedCornerShape(25.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = MainText,
                                        fontSize = 15.sp,
                                        lineHeight = 20.sp
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = MainText,
                                        unfocusedTextColor = MainText,
                                        cursorColor = Accent,
                                        focusedContainerColor = Surface2,
                                        unfocusedContainerColor = Surface2,
                                        disabledContainerColor = Surface2,
                                        focusedBorderColor = Accent.copy(alpha = 0.65f),
                                        unfocusedBorderColor = Color.Transparent,
                                        disabledBorderColor = Color.Transparent,
                                        focusedPlaceholderColor = SecondaryText,
                                        unfocusedPlaceholderColor = SecondaryText
                                    ),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(onSend = {
                                        val text = draft.trim()
                                        val media = pendingMediaUri
                                        if (media != null && !sendingImage) sendPendingMedia(chat, media, text, replyTarget?.id)
                                        else if (text.isNotEmpty()) sendSecureMessage(chat, text, replyTarget?.id)
                                    })
                                )
                                Spacer(Modifier.width(7.dp))
                                val hasOutgoingContent = draft.isNotBlank() || pendingMediaUri != null
                                val sendButtonScale by animateFloatAsState(
                                    targetValue = if (hasOutgoingContent || recordingVoice) 1.06f else 1f,
                                    animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
                                    label = "composer-action-scale"
                                )
                                IconButton(
                                    onClick = {
                                        if (hasOutgoingContent) {
                                            val text = draft.trim()
                                            val media = pendingMediaUri
                                            if (media != null && !sendingImage) sendPendingMedia(chat, media, text, replyTarget?.id)
                                            else if (text.isNotEmpty()) sendSecureMessage(chat, text, replyTarget?.id)
                                        } else if (!recordingVoice) {
                                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        } else {
                                            stopVoiceRecording(chat)
                                        }
                                    },
                                    enabled = !sendingImage,
                                    modifier = Modifier.size(46.dp)
                                        .graphicsLayer { scaleX = sendButtonScale; scaleY = sendButtonScale }
                                        .clip(CircleShape)
                                        .background(
                                            if (hasOutgoingContent || recordingVoice) Brush.linearGradient(listOf(Accent, Accent2))
                                            else Brush.linearGradient(listOf(Surface2, Surface2))
                                        )
                                        .align(Alignment.Bottom)
                                ) {
                                    if (sendingImage) CircularProgressIndicator(Modifier.size(19.dp), color = Bg, strokeWidth = 2.dp)
                                    else if (hasOutgoingContent) Icon(Icons.Default.Send, "Отправить", tint = Bg, modifier = Modifier.size(21.dp))
                                    else Icon(
                                        if (recordingVoice) Icons.Default.Stop else Icons.Default.Mic,
                                        if (recordingVoice) "Остановить и отправить голосовое" else "Записать голосовое",
                                        tint = if (recordingVoice) Bg else Accent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                        }
                        }
                            }
                        }
                        }
                    }
                }

    if (e2eeRecoveryRequired) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Восстановление защищённых чатов") },
            text = {
                Column {
                    Text("Найдена резервная копия ключей этого аккаунта. Введите ключ восстановления, сохранённый при создании копии.")
                    OutlinedTextField(value = recoveryCodeInput, onValueChange = { recoveryCodeInput = it.trim().take(64) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true,
                        label = { Text("Ключ восстановления") })
                    if (error.isNotBlank()) Text(error, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && recoveryCodeInput.isNotBlank(), onClick = {
                    scope.launch {
                        busy = true
                        error = ""
                        try {
                            val uid = store.userId ?: throw IOException("Сессия истекла")
                            val restoredClient = E2eeClient(context.applicationContext, uid)
                            api.restoreE2eeBackup(restoredClient, recoveryCodeInput)
                            val available = api.publishE2eeDevice(restoredClient)
                            e2eeClient = restoredClient
                            e2eeRecoveryRequired = false
                            e2eeStatus = "Ключи восстановлены · $available ключей"
                            recoveryCodeInput = ""
                            notice = "Защищённые ключи восстановлены. История снова доступна."
                        } catch (e: Exception) { error = e.message ?: "Не удалось восстановить ключи" }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Восстанавливаем…" else "Восстановить") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        error = ""
                        try {
                            val uid = store.userId ?: throw IOException("Сессия истекла")
                            val freshClient = E2eeClient(context.applicationContext, uid)
                            val available = api.publishE2eeDevice(freshClient)
                            api.removeOtherOwnE2eeDevices(freshClient.deviceId())
                            e2eeClient = freshClient
                            e2eeRecoveryRequired = false
                            e2eeStatus = "Создано новое устройство · старые сообщения не расшифруются"
                            notice = "Начато с нового устройства. Старая история без ключа восстановления недоступна."
                        } catch (e: Exception) { error = e.message ?: "Не удалось создать новое устройство" }
                        finally { busy = false }
                    }
                }) { Text("Начать без старой истории") }
            }
        )
    }
}
}
}

@Composable
private fun AudioMessagePlayer(
    uri: Uri,
    caption: String?,
    fileName: String?,
    mimeType: String?
) {
    val context = LocalContext.current
    var player by remember(uri) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(uri) { mutableStateOf(false) }
    var loading by remember(uri) { mutableStateOf(false) }
    var prepared by remember(uri) { mutableStateOf(false) }
    var durationMs by remember(uri) { mutableLongStateOf(0L) }
    var positionMs by remember(uri) { mutableLongStateOf(0L) }
    val activePlayer = rememberUpdatedState(player)

    LaunchedEffect(player, playing, prepared) {
        while (playing && prepared) {
            val current = player ?: break
            if (!runCatching { current.isPlaying }.getOrDefault(false)) break
            positionMs = runCatching { current.currentPosition.toLong() }.getOrDefault(positionMs)
            delay(250)
        }
    }

    DisposableEffect(uri) {
        onDispose {
            runCatching { activePlayer.value?.stop() }
            runCatching { activePlayer.value?.release() }
        }
    }

    fun formatTime(value: Long): String {
        val seconds = (value.coerceAtLeast(0L) / 1000L)
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60L, seconds % 60L)
    }

    val title = fileName?.takeIf { it.isNotBlank() && it != "voice.m4a" }
        ?: if (mimeType?.startsWith("audio/") == true) "Аудиофайл" else "Голосовое сообщение"
    val subtitle = caption?.takeIf { it.isNotBlank() } ?: mimeType?.uppercase(Locale.getDefault()) ?: "ЗАЩИЩЁННОЕ АУДИО"

    Column(
        Modifier.widthIn(min = 245.dp, max = 300.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF30234B), Surface2)))
            .border(1.dp, Accent.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (playing) {
                        runCatching { player?.pause() }
                        playing = false
                    } else if (prepared && player != null) {
                        runCatching {
                            if (positionMs >= durationMs && durationMs > 0L) {
                                player?.seekTo(0)
                                positionMs = 0L
                            }
                            player?.start()
                            playing = true
                        }.onFailure {
                            Toast.makeText(context, "Не удалось продолжить аудио", Toast.LENGTH_SHORT).show()
                        }
                    } else if (!loading) {
                        loading = true
                        try {
                            val next = MediaPlayer()
                            player = next
                            next.setOnPreparedListener { mp ->
                                durationMs = mp.duration.toLong().coerceAtLeast(0L)
                                positionMs = 0L
                                prepared = true
                                loading = false
                                mp.start()
                                playing = true
                            }
                            next.setOnCompletionListener { mp ->
                                playing = false
                                positionMs = 0L
                                runCatching { mp.seekTo(0) }
                            }
                            next.setOnErrorListener { mp, _, _ ->
                                loading = false
                                prepared = false
                                playing = false
                                runCatching { mp.release() }
                                player = null
                                Toast.makeText(context, "Не удалось воспроизвести аудио", Toast.LENGTH_SHORT).show()
                                true
                            }
                            next.setDataSource(context, uri)
                            next.prepareAsync()
                        } catch (_: Exception) {
                            loading = false
                            prepared = false
                            playing = false
                            runCatching { player?.release() }
                            player = null
                            Toast.makeText(context, "Не удалось открыть аудиофайл", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.size(48.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Accent, Accent2)))
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(21.dp), color = Bg, strokeWidth = 2.dp)
                else Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "Пауза" else "Воспроизвести",
                    tint = Bg,
                    modifier = Modifier.size(27.dp)
                )
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    title,
                    color = MainText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    subtitle,
                    color = SecondaryText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = "Аудио",
                tint = Accent,
                modifier = Modifier.padding(start = 6.dp).size(22.dp)
            )
        }
        Slider(
            value = if (durationMs > 0L) positionMs.coerceIn(0L, durationMs).toFloat() else 0f,
            onValueChange = { positionMs = it.toLong() },
            onValueChangeFinished = {
                if (prepared && durationMs > 0L) {
                    val target = positionMs.coerceIn(0L, durationMs).toInt()
                    runCatching { player?.seekTo(target) }
                    if (playing) runCatching { player?.start() }
                }
            },
            valueRange = 0f..(durationMs.coerceAtLeast(1L).toFloat()),
            enabled = prepared && durationMs > 0L,
            modifier = Modifier.fillMaxWidth().height(25.dp),
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = SecondaryText.copy(alpha = 0.28f)
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (loading) "Загрузка…" else formatTime(positionMs),
                color = SecondaryText,
                fontSize = 10.sp
            )
            Text(
                if (durationMs > 0L) formatTime(durationMs) else "--:--",
                color = SecondaryText,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun NexuraBottomBar(
    selected: String,
    profileSelected: Boolean,
    onSelect: (String) -> Unit,
    onProfile: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        color = Surface1,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 12.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.16f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 5.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val items = listOf(
                Triple("chats", "Чаты", Icons.Default.ChatBubbleOutline),
                Triple("contacts", "Контакты", Icons.Default.GroupAdd),
                Triple("search", "Поиск", Icons.Default.Search)
            )
            items.forEach { (key, label, icon) ->
                val active = selected == key && !profileSelected
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(17.dp))
                        .background(if (active) Accent.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { onSelect(key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(icon, contentDescription = label, tint = if (active) Accent else SecondaryText, modifier = Modifier.size(21.dp))
                    Text(label, color = if (active) Accent else SecondaryText, fontSize = 10.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(17.dp))
                    .background(if (profileSelected) Accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onProfile() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Профиль", tint = if (profileSelected) Accent else SecondaryText, modifier = Modifier.size(21.dp))
                Text("Профиль", color = if (profileSelected) Accent else SecondaryText, fontSize = 10.sp, fontWeight = if (profileSelected) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

private fun isRetryableOutboxFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    while (current != null && visited.add(current)) {
        if (current is NexuraHttpException) {
            return current.statusCode == 408 || current.statusCode == 425 ||
                current.statusCode == 429 || current.statusCode in 500..599
        }
        if (current is java.net.UnknownHostException ||
            current is java.net.ConnectException ||
            current is java.net.SocketTimeoutException ||
            current is java.net.SocketException ||
            current is java.net.NoRouteToHostException ||
            current is java.io.InterruptedIOException) return true
        current = current.cause
    }
    return false
}

private fun messageTimeLabel(value: String): String {
    val time = value.substringAfter("T", "").take(5)
    return if (time.length == 5) time else value.take(16).replace("T", " ")
}

private fun chatTimeLabel(value: String?): String {
    if (value.isNullOrBlank()) return ""
    val time = value.substringAfter("T", "").take(5)
    return if (time.length == 5) time else value.take(10)
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboard: ImeAction = ImeAction.Next
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        label = { Text(label) }, placeholder = { Text(placeholder) },
        singleLine = true, shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(imeAction = keyboard),
        colors = authFieldColors()
    )
}

@Composable
private fun authFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = Color(0xFF2B3A51),
    focusedLabelColor = Accent,
    unfocusedLabelColor = SecondaryText,
    cursorColor = Accent,
    focusedTextColor = MainText,
    unfocusedTextColor = MainText
)

private fun chatMessagePreview(message: MessageItem, ownUserId: String): String {
    val body = when (message.messageType.lowercase()) {
        "call" -> "Звонок"
        "image", "photo" -> message.mediaCaption?.takeIf { it.isNotBlank() } ?: "Фото"
        "video" -> message.mediaCaption?.takeIf { it.isNotBlank() } ?: "Видео"
        "voice", "audio" -> "Голосовое сообщение"
        "file" -> message.mediaFileName?.let { "Файл: $it" } ?: "Файл"
        else -> when (message.mediaKind?.lowercase()) {
            "image", "photo" -> message.mediaCaption?.takeIf { it.isNotBlank() } ?: "Фото"
            "video" -> message.mediaCaption?.takeIf { it.isNotBlank() } ?: "Видео"
            "voice", "audio" -> "Голосовое сообщение"
            "file" -> message.mediaFileName?.let { "Файл: $it" } ?: "Файл"
            else -> message.content.trim().ifBlank { "Сообщение" }
        }
    }
    val prefix = if (message.senderId == ownUserId) "Вы: " else ""
    return (prefix + body).replace(Regex("\\s+"), " ").take(100)
}

@Composable
private fun AnimatedReactionPickerItem(emoji: String, onClick: () -> Unit) {
    var pressed by remember(emoji) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 1.22f else 1f,
        animationSpec = spring(dampingRatio = 0.38f, stiffness = 620f),
        label = "reaction-picker-scale"
    )
    LaunchedEffect(pressed) {
        if (pressed) {
            delay(150)
            pressed = false
        }
    }
    Box(
        Modifier.size(36.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape).background(Surface2)
            .clickable { pressed = true; onClick() },
        contentAlignment = Alignment.Center
    ) { Text(emoji, fontSize = 20.sp) }
}

@Composable
private fun AnimatedReactionChip(emoji: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    var pulse by remember(emoji) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pulse || selected) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 500f),
        label = "reaction-chip-scale"
    )
    val chipColor by animateColorAsState(
        targetValue = if (selected) Accent.copy(alpha = 0.30f) else Surface2,
        animationSpec = spring(stiffness = 420f),
        label = "reaction-chip-color"
    )
    LaunchedEffect(pulse) {
        if (pulse) {
            delay(180)
            pulse = false
        }
    }
    Row(
        Modifier.padding(end = 6.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(18.dp)).background(chipColor)
            .border(1.dp, if (selected) Accent else SecondaryText.copy(alpha = 0.20f), RoundedCornerShape(18.dp))
            .clickable { pulse = true; onClick() }
            .animateContentSize(spring(dampingRatio = 0.78f, stiffness = 520f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 14.sp)
        Text(" $count", color = MainText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChatListAvatar(name: String, avatar: String?, isGroup: Boolean, hasUnread: Boolean) {
    val imageUrl = avatar?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    val emoji = avatar?.takeIf { it.isNotBlank() && imageUrl == null && it != "null" }
        ?: name.takeIf { it in listOf("🙂", "😎", "🦊", "🐼", "🐸", "🐱", "🚀", "🌙") }
    Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(52.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Accent.copy(alpha = 0.95f), Accent2.copy(alpha = 0.72f))))
                .padding(2.dp)
                .clip(CircleShape)
                .background(Surface1)
                .padding(2.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isGroup -> Box(
                    Modifier.fillMaxSize().clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Surface2, Accent.copy(alpha = 0.24f)))),
                    contentAlignment = Alignment.Center
                ) { Text("👥", fontSize = 23.sp) }
                imageUrl != null -> AsyncImage(
                    model = imageUrl,
                    contentDescription = "Аватар $name",
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                !emoji.isNullOrBlank() -> Box(
                    Modifier.fillMaxSize().clip(CircleShape).background(Surface2),
                    contentAlignment = Alignment.Center
                ) { Text(emoji, fontSize = 23.sp) }
                else -> Box(
                    Modifier.fillMaxSize().clip(CircleShape).background(Surface2),
                    contentAlignment = Alignment.Center
                ) {
                    Text(name.trim().firstOrNull()?.uppercase() ?: "N", color = MainText, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                }
            }
        }
        AnimatedVisibility(
            visible = hasUnread,
            enter = fadeIn() + scaleIn(initialScale = 0.45f),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            Box(
                Modifier.size(12.dp).clip(CircleShape)
                    .background(Accent)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(Bg)
            )
        }
    }
}

@Composable
private fun Avatar(name: String, avatar: String? = null) {
    val imageUrl = avatar?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    val emoji = avatar?.takeIf { it.isNotBlank() && imageUrl == null && it != "null" }
        ?: name.takeIf { it in listOf("🙂", "😎", "🦊", "🐼", "🐸", "🐱", "🚀", "🌙") }
    Box(Modifier.size(50.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Accent, Accent2))), contentAlignment = Alignment.Center) {
        when {
            imageUrl != null -> AsyncImage(model = imageUrl, contentDescription = "Аватар $name", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            !emoji.isNullOrBlank() -> Text(emoji, fontSize = 23.sp)
            else -> Text(name.trim().firstOrNull()?.uppercase() ?: "N", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun GroupAvatar() {
    Box(Modifier.size(50.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
        Text("👥", fontSize = 22.sp)
    }
}
