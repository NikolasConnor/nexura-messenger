package com.nexura.messenger

import android.Manifest
import android.content.Intent
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import java.util.Locale
import java.util.UUID
import java.io.IOException
import org.json.JSONObject
import android.os.Build
import android.net.Uri
import android.widget.VideoView
import android.widget.MediaController
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.nexura.messenger.calls.NexuraCallDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import com.google.firebase.messaging.FirebaseMessaging

private val Bg = Color(0xFF0D1118)
private val Surface1 = Color(0xFF171D27)
private val Surface2 = Color(0xFF242D3A)
private val Accent = Color(0xFF78AFFF)
private val Accent2 = Color(0xFF8E9DDA)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NexuraApp(api: NexuraApi, store: SessionStore, authCallback: Uri?) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(!store.accessToken.isNullOrBlank()) }
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
    var messages by remember { mutableStateOf(emptyList<MessageItem>()) }
    var lastSyncedMessageTimestamp by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var users by remember { mutableStateOf(emptyList<UserItem>()) }
    var searching by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var homeTab by remember { mutableStateOf("chats") }
    var deviceContacts by remember { mutableStateOf(emptyList<DeviceContact>()) }
    var syncingContacts by remember { mutableStateOf(false) }
    var contactsSynced by remember { mutableStateOf(false) }
    var contactsError by remember { mutableStateOf("") }
    var chatFilter by remember { mutableStateOf("") }
    var showChatFilter by remember { mutableStateOf(false) }
    var openMessageMenu by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showTerminateOthersDialog by remember { mutableStateOf(false) }
    var sendingMessage by remember { mutableStateOf(false) }
    var sendingImage by remember { mutableStateOf(false) }
    var pendingMediaUri by remember { mutableStateOf<Uri?>(null) }
    var mediaViewerUri by remember { mutableStateOf<Uri?>(null) }
    var mediaViewerType by remember { mutableStateOf("image") }
    var profileName by remember { mutableStateOf("") }
    var profileAvatar by remember { mutableStateOf("🙂") }
    var profileBio by remember { mutableStateOf("") }
    var profileAge by remember { mutableStateOf("") }
    var profilePhotoUri by remember { mutableStateOf<Uri?>(null) }
    var savingProfile by remember { mutableStateOf(false) }
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
    var editDraft by remember { mutableStateOf("") }
    var e2eeClient by remember { mutableStateOf<E2eeClient?>(null) }
    var e2eeStatus by remember { mutableStateOf("Шифрование не проверено") }
    var trustCandidate by remember { mutableStateOf<E2eeRemoteDevice?>(null) }
    var trustPeerId by remember { mutableStateOf<String?>(null) }
    var groupTrustQueue by remember { mutableStateOf(emptyList<Pair<String, E2eeRemoteDevice>>()) }
    var pendingSecureMessageId by remember { mutableStateOf<String?>(null) }
    var pendingSecureMessageText by remember { mutableStateOf<String?>(null) }
    var showCallDialog by remember { mutableStateOf(false) }
    var pendingCallAction by remember { mutableStateOf(authCallback?.getQueryParameter("action") ?: "show") }
    LaunchedEffect(selected?.id) {
        context.getSharedPreferences("nexura_runtime", android.content.Context.MODE_PRIVATE)
            .edit().putString("active_chat_id", selected?.id).apply()
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
                if (replyTarget == null) replyTarget = submittedReply
                error = e.message ?: "Не удалось отправить медиа"
            } finally {
                sendingImage = false
            }
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        sendPickedMedia(uri)
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        profilePhotoUri = uri
    }

    val visibleChats = chats.filter { it.title.contains(chatFilter, ignoreCase = true) }

    fun loadChats() {
        scope.launch {
            busy = true
            try {
                chats = api.chats()
                error = ""
            } catch (e: Exception) {
                error = e.message ?: "Не удалось загрузить чаты"
            } finally { busy = false }
        }
    }

    fun openChat(chat: ChatItem) {
        selected = chat
        messages = emptyList()
        lastSyncedMessageTimestamp = null
        replyTarget = null
        scope.launch {
            try {
                val loadedMessages = api.messages(chat.id, e2eeClient)
                if (selected?.id == chat.id) {
                    messages = loadedMessages
                    lastSyncedMessageTimestamp = loadedMessages.maxOfOrNull { it.createdAt } ?: "1970-01-01T00:00:00Z"
                }
                api.markChatRead(chat.id)
                chats = chats.map { if (it.id == chat.id) it.copy(unreadCount = 0) else it }
            } catch (e: Exception) { error = e.message ?: "Не удалось загрузить сообщения" }
        }
    }

    fun sendSecureMessage(chat: ChatItem, text: String, replyToId: String?) {
        if (sendingMessage) return
        if (pendingSecureMessageText != null && pendingSecureMessageText != text) {
            error = "Предыдущее зашифрованное сообщение ещё не подтверждено сервером. Повторите отправку того же текста."
            return
        }
        val messageId = pendingSecureMessageId ?: UUID.randomUUID().toString().also {
            pendingSecureMessageId = it
            pendingSecureMessageText = text
        }
        sendingMessage = true
        val submittedReply = replyTarget
        // Clear the composer immediately; restore the text only if delivery fails.
        draft = ""
        replyTarget = null
        val optimistic = MessageItem(
            messageId, store.userId.orEmpty(), text, java.time.Instant.now().toString(),
            "text", replyToId, encrypted = true, securityWarning = false
        )
        messages = (messages + optimistic).distinctBy { it.id }.sortedBy { it.createdAt }
        scope.launch {
            error = ""
            try {
                val client = e2eeClient ?: throw IllegalStateException("Локальный ключ E2EE ещё не готов")
                if (chat.kind == "direct") {
                    api.sendEncryptedDirectMessage(chat.id, client, messageId, text, replyToId)
                } else {
                    api.sendEncryptedGroupMessage(chat.id, client, messageId, text, replyToId = replyToId)
                }
                pendingSecureMessageId = null
                pendingSecureMessageText = null
                // The message is already visible; successful persistence confirms it.
            } catch (e: Exception) {
                messages = messages.filterNot { it.id == messageId }
                if (draft.isBlank()) draft = text
                if (replyTarget == null) replyTarget = submittedReply
                error = e.message ?: "Не удалось отправить защищённое сообщение"
            } finally {
                sendingMessage = false
            }
        }
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
            authCallback.pathSegments.firstOrNull()?.let { raw ->
                runCatching { UUID.fromString(raw).toString() }.getOrNull()
            }
        } else null
        if (signedIn && targetChatId != null) {
            val targetChat = chats.firstOrNull { it.id.equals(targetChatId, ignoreCase = true) }
            if (targetChat != null && selected?.id != targetChat.id) {
                openChat(targetChat)
            }
            if (targetChat != null && authCallback?.getQueryParameter("call") == "1") {
                pendingCallAction = authCallback.getQueryParameter("action") ?: "show"
                showCallDialog = true
            }
        }
    }

    LaunchedEffect(signedIn) {
        if (signedIn) {
            try {
                if (store.accessToken.isNullOrBlank()) api.refreshSession()
                api.myProfile() // Create a profile row for accounts that have not opened settings yet.
                // Prepare local E2EE state before chats become tappable; key publication is
                // network work and happens after the chat list has rendered.
                val e2eeUserId = store.userId
                val localE2eeClient = if (!e2eeUserId.isNullOrBlank()) {
                    runCatching { E2eeClient(context.applicationContext, e2eeUserId) }
                        .onSuccess { e2eeClient = it }
                        .onFailure { e2eeStatus = "Не удалось подготовить E2EE: ${it.message ?: "ошибка устройства"}" }
                        .getOrNull()
                } else null
                chats = api.chats()
                if (localE2eeClient != null) {
                    try {
                        val available = api.publishE2eeDevice(localE2eeClient)
                        e2eeStatus = "Устройство зарегистрировано · $available ключей"
                    } catch (e: Exception) {
                        e2eeStatus = "Каталог ключей недоступен: ${e.message ?: "ожидается настройка сервера"}"
                    }
                }
                val pushToken = FirebaseMessaging.getInstance().token.await()
                if (pushToken.isNotBlank()) api.registerPushToken(pushToken)
            } catch (e: Exception) {
                error = e.message ?: "Не удалось восстановить сессию"
                if (store.accessToken.isNullOrBlank()) signedIn = false
            }
        }
    }

    // Keep the open conversation fresh without requiring the user to tap Refresh.
    // The list is refreshed less often to avoid unnecessary database traffic.
    LaunchedEffect(signedIn, selected?.id) {
        if (signedIn) {
            var retryDelay = 2500L
            while (true) {
                delay(if (selected != null) retryDelay else maxOf(20_000L, retryDelay))
                try {
                    val currentChat = selected
                    if (currentChat != null) {
                        val lastTimestamp = lastSyncedMessageTimestamp
                        val latest = api.messages(currentChat.id, e2eeClient, lastTimestamp)
                        if (selected?.id == currentChat.id && latest.isNotEmpty()) {
                            messages = (messages + latest).distinctBy { it.id }.sortedBy { it.createdAt }
                            lastSyncedMessageTimestamp = maxOf(
                                lastSyncedMessageTimestamp ?: latest.first().createdAt,
                                latest.maxOf { it.createdAt }
                            )
                        }
                    } else {
                        chats = api.chats()
                    }
                    retryDelay = 2500L
                } catch (_: Exception) {
                    // Back off during network outages instead of hammering the API every 2.5s.
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
                                                signedIn = !store.accessToken.isNullOrBlank()
                                            }
                                        } else {
                                            if (register) api.signUp(email.trim(), password, displayName.trim())
                                            else api.signIn(email.trim(), password)
                                            signedIn = !store.accessToken.isNullOrBlank()
                                            if (!signedIn && register) notice = "Аккаунт создан. Подтвердите email по ссылке из письма."
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
                        Text("СЕАНС И БЕЗОПАСНОСТЬ", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Card(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Сквозное шифрование", color = MainText, fontWeight = FontWeight.SemiBold)
                                Text(e2eeStatus, color = if (e2eeStatus.startsWith("Устройство зарегистрировано")) Success else Color(0xFFFFC56E), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                                val client = e2eeClient
                                val bundle = runCatching { JSONObject(client?.ensureDevice() ?: "{}") }.getOrNull()
                                if (client != null && bundle != null && bundle.has("curve25519") && bundle.has("ed25519")) {
                                    Text("Отпечаток этого устройства", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
                                    Text(client.deviceFingerprint(bundle.getString("curve25519"), bundle.getString("ed25519")), color = MainText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Text("Сравните отпечатки устройств лично или по другому доверенному каналу. Текст, фото и видео шифруются до отправки. В личном чате пока поддерживается одно активное устройство собеседника.", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
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
                    if (showLogoutDialog) AlertDialog(onDismissRequest = { showLogoutDialog = false }, title = { Text("Выйти из Nexura?") }, text = { Text("Вы сможете войти снова, используя свой способ авторизации.") }, confirmButton = { TextButton(onClick = { showLogoutDialog = false; scope.launch { busy = true; try { api.signOut(); signedIn = false; chats = emptyList(); selected = null; settingsOpen = false; e2eeClient = null; e2eeStatus = "Шифрование не проверено"; notice = "" } catch (e: Exception) { error = e.message ?: "Ошибка выхода" } finally { busy = false } } }) { Text("Выйти", color = Danger) } }, dismissButton = { TextButton(onClick = { showLogoutDialog = false }) { Text("Отмена") } })
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
                                IconButton(onClick = { settingsOpen = true; error = ""; notice = ""; scope.launch { try { val p = api.myProfile(); profileName = p.displayName; profileAvatar = p.avatar.ifBlank { "🙂" }; profileBio = p.bio; profileAge = p.age?.toString().orEmpty() } catch (e: Exception) { error = e.message ?: "Не удалось загрузить профиль" } } }) {
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
                            Card(Modifier.fillMaxWidth().padding(top = 12.dp), colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Новая группа", color = MainText, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { createGroupOpen = false }) { Text("Закрыть") }
                                    }
                                    OutlinedTextField(value = groupTitle, onValueChange = { groupTitle = it.take(60) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Название группы") }, shape = RoundedCornerShape(12.dp), colors = authFieldColors())
                                    OutlinedTextField(value = groupSearch, onValueChange = { groupSearch = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Найти участников") }, shape = RoundedCornerShape(12.dp), colors = authFieldColors(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (groupSearch.length >= 2) scope.launch { try { groupUsers = api.findUsers(groupSearch) } catch (e: Exception) { error = e.message ?: "Не удалось найти участников" } } }))
                                    TextButton(onClick = { if (groupSearch.length >= 2) scope.launch { try { groupUsers = api.findUsers(groupSearch) } catch (e: Exception) { error = e.message ?: "Не удалось найти участников" } } }) { Text("Искать") }
                                    groupUsers.forEach { person ->
                                        Row(Modifier.fillMaxWidth().clickable { selectedGroupUsers = if (person.id in selectedGroupUsers) selectedGroupUsers - person.id else if (selectedGroupUsers.size >= 49) { error = "В группе может быть не более 50 участников вместе с вами"; selectedGroupUsers } else selectedGroupUsers + person.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = person.id in selectedGroupUsers, onCheckedChange = { checked -> selectedGroupUsers = if (checked) selectedGroupUsers + person.id else selectedGroupUsers - person.id })
                                            Avatar(person.displayName, person.avatar)
                                            Text(person.displayName, color = MainText, modifier = Modifier.padding(start = 10.dp).weight(1f))
                                        }
                                    }
                                    Text("Выбрано: ${selectedGroupUsers.size} из 49 участников (вы будете владельцем)", color = SecondaryText, fontSize = 12.sp)
                                    Button(onClick = { scope.launch { creatingGroup = true; error = ""; try { val id = api.createGroup(groupTitle, selectedGroupUsers.toList()); val chat = ChatItem(id, groupTitle.trim(), "group", null); createGroupOpen = false; selected = chat; messages = emptyList(); chats = api.chats() } catch (e: Exception) { error = e.message ?: "Не удалось создать группу" } finally { creatingGroup = false } } }, enabled = !creatingGroup && groupTitle.trim().length in 2..60 && selectedGroupUsers.size in 1..49, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(if (creatingGroup) "Создаём…" else "Создать группу") }
                                }
                            }
                        }
                        if (homeTab == "contacts") {
                            Column(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp)) {
                                Text("Контакты", color = MainText, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                                Text("Найдём знакомых, которые уже используют Nexura", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                                Card(colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text("Синхронизация адресной книги", color = MainText, fontWeight = FontWeight.SemiBold)
                                        Text("Номера используются только для поиска совпадений. Они не публикуются в профиле.", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                                        Button(onClick = {
                                            if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) syncContacts()
                                            else contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                        }, enabled = !syncingContacts, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(12.dp)) {
                                            Text(if (syncingContacts) "Синхронизируем…" else if (contactsSynced) "Обновить контакты" else "Найти контакты")
                                        }
                                    }
                                }
                                if (contactsError.isNotBlank()) Text(contactsError, color = if (contactsSynced) SecondaryText else Danger, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                                if (contactsSynced) {
                                    val registered = deviceContacts.count { it.match != null }
                                    Text("В Nexura: $registered · Не зарегистрированы: ${deviceContacts.size - registered}", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
                                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 18.dp)) {
                                        items(deviceContacts, key = { it.phone }) { person ->
                                            Row(
                                                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                                    .clip(RoundedCornerShape(16.dp)).background(Surface1)
                                                    .padding(horizontal = 11.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (person.match != null) Avatar(person.match.displayName, person.match.avatar) else Avatar(person.name, "👤")
                                                Column(Modifier.weight(1f).padding(start = 11.dp, end = 4.dp)) {
                                                    Text(person.match?.displayName ?: person.name, color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                                                    Text(if (person.match != null) "Уже в Nexura" else "Можно пригласить", color = if (person.match != null) Success else SecondaryText, fontSize = 11.sp)
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
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).clickable {
                                        scope.launch {
                                            try {
                                                val id = api.createDirect(user.id)
                                                val chat = ChatItem(id, user.displayName, "direct", null, avatar = user.avatar, peerUserId = user.id)
                                                selected = chat
                                                messages = api.messages(id, e2eeClient)
                                                loadChats()
                                            } catch (e: Exception) { error = e.message ?: "Не удалось открыть чат" }
                                        }
                                    }.padding(vertical = 11.dp, horizontal = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Avatar(user.displayName, user.avatar)
                                    Text(user.displayName, color = MainText, modifier = Modifier.padding(start = 12.dp).weight(1f))
                                    Text("Написать", color = Accent, fontSize = 12.sp)
                                }
                            }
                        }
                        }
                        }
                        if (homeTab == "chats") {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Сообщения", color = MainText, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${visibleChats.size}", color = SecondaryText, fontSize = 12.sp)
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
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                            .clickable { openChat(chat) }
                                            .padding(horizontal = 8.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (chat.kind == "group") GroupAvatar() else Avatar(chat.title, chat.avatar)
                                        Column(Modifier.weight(1f).padding(start = 13.dp, end = 8.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(chat.title, color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                                Text(chatTimeLabel(chat.updated), color = if (chat.unreadCount > 0) Accent else SecondaryText, fontSize = 11.sp)
                                            }
                                            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(if (chat.kind == "group") Icons.Default.GroupAdd else Icons.Default.ChatBubbleOutline, null, tint = SecondaryText, modifier = Modifier.size(13.dp))
                                                Text(if (chat.kind == "group") "Групповой чат" else "Личная переписка", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(start = 5.dp), maxLines = 1)
                                                Spacer(Modifier.weight(1f))
                                                if (chat.unreadCount > 0) {
                                                    Box(Modifier.clip(CircleShape).background(Accent).defaultMinSize(minWidth = 21.dp).padding(horizontal = 7.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
                                                        Text(chat.unreadCount.coerceAtMost(99).toString(), color = Bg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = Surface1.copy(alpha = 0.9f), modifier = Modifier.padding(start = 72.dp))
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
                    if (editingTarget != null) {
                        AlertDialog(
                            onDismissRequest = { editingTarget = null },
                            title = { Text("Изменить сообщение") },
                            text = { OutlinedTextField(value = editDraft, onValueChange = { editDraft = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5) },
                            confirmButton = { TextButton(onClick = { val target = editingTarget!!; scope.launch { try { api.editMessage(target.id, editDraft); messages = api.messages(selected!!.id, e2eeClient); editingTarget = null } catch (e: Exception) { error = e.message ?: "Не удалось изменить сообщение" } } }) { Text("Сохранить") } },
                            dismissButton = { TextButton(onClick = { editingTarget = null }) { Text("Отмена") } }
                        )
                    }
                    val chat = selected!!
                    if (chat.kind == "direct" && !store.userId.isNullOrBlank() && !store.accessToken.isNullOrBlank()) {
                        NexuraCallDialog(
                            api = api,
                            chat = chat,
                            ownUserId = store.userId!!,
                            accessToken = store.accessToken!!,
                            visible = showCallDialog,
                            autoAction = pendingCallAction,
                            onCallFinished = { duration, outgoing, video, result ->
                                if (outgoing) scope.launch {
                                    try {
                                        val client = e2eeClient ?: throw IllegalStateException("Не удалось сохранить историю звонка: E2EE не готово")
                                        val record = org.json.JSONObject()
                                            .put("kind", if (video) "video" else "audio")
                                            .put("duration_seconds", duration)
                                            .put("direction", if (outgoing) "outgoing" else "incoming")
                                            .put("result", result)
                                            .toString()
                                        api.sendEncryptedDirectMessage(
                                            chat.id, client, UUID.randomUUID().toString(), record,
                                            messageType = "call"
                                        )
                                        if (selected?.id == chat.id) {
                                            messages = api.messages(chat.id, client)
                                        }
                                    } catch (e: Exception) {
                                        error = e.message ?: "Не удалось сохранить запись о звонке"
                                    }
                                }
                            },
                            onDismiss = { showCallDialog = false; pendingCallAction = "show" }
                        )
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
                        val remoteFingerprint = client?.deviceFingerprint(remote.curve25519Key, remote.ed25519Key) ?: "Недоступен"
                        val ownFingerprint = if (ownBundle != null && ownBundle.has("curve25519") && ownBundle.has("ed25519")) {
                            client?.deviceFingerprint(ownBundle.getString("curve25519"), ownBundle.getString("ed25519")) ?: "Недоступен"
                        } else "Недоступен"
                        AlertDialog(
                            onDismissRequest = { trustCandidate = null; trustPeerId = null; groupTrustQueue = emptyList() },
                            title = { Text("Проверка устройства") },
                            text = {
                                Column {
                                    Text("Сравните отпечаток собеседника с отпечатком его устройства по другому доверенному каналу — например, лично или по видеосвязи. Не подтверждайте ключ, если сравнить его не удалось.")
                                    Text(
                                        if (groupTrustQueue.isNotEmpty()) "Устройство участника: ${trustPeerId}" else "Устройство собеседника",
                                        color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp)
                                    )
                                    Text(remoteFingerprint, color = MainText, fontWeight = FontWeight.Bold)
                                    Text("Ваш отпечаток", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                                    Text(ownFingerprint, color = MainText, fontWeight = FontWeight.Bold)
                                    Text("Собеседник должен увидеть ваш отпечаток у себя и сравнить его с тем, что показывается на его устройстве.", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
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
                                    if (profile.age != null) Text("${profile.age} лет", color = SecondaryText, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                                    Text(profile.bio.ifBlank { "Пользователь пока ничего не рассказал о себе" }, color = MainText, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
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
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().background(Surface1).padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { selected = null; messages = emptyList(); replyTarget = null; loadChats() }) {
                                Icon(Icons.Default.ArrowBack, "Назад к чатам", tint = MainText)
                            }
                            if (chat.kind == "group") {
                                GroupAvatar()
                            } else {
                                Box(Modifier.clickable {
                                    val peerId = chat.peerUserId
                                    if (peerId != null) {
                                        loadingProfile = true
                                        scope.launch {
                                            try {
                                                viewedProfile = api.userProfile(peerId)
                                                profileMedia = api.messages(chat.id, e2eeClient, mediaOnly = true)
                                            } catch (e: Exception) { error = e.message ?: "Не удалось открыть профиль" }
                                            finally { loadingProfile = false }
                                        }
                                    } else error = "Не удалось определить собеседника"
                                }) { Avatar(chat.title, chat.avatar) }
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp).clickable(enabled = chat.kind == "direct") {
                                val peerId = chat.peerUserId
                                if (peerId != null) {
                                    loadingProfile = true
                                    scope.launch {
                                        try {
                                            viewedProfile = api.userProfile(peerId)
                                            profileMedia = api.messages(chat.id, e2eeClient, mediaOnly = true)
                                        } catch (e: Exception) { error = e.message ?: "Не удалось открыть профиль" }
                                        finally { loadingProfile = false }
                                    }
                                }
                            }) {
                                Text(chat.title, color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                                Text(
                                    if (chat.kind == "group") "Групповой чат" else "Личная переписка",
                                    color = SecondaryText,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                            if (chat.kind == "direct") {
                                IconButton(onClick = {
                                    pendingCallAction = "start_audio"
                                    showCallDialog = true
                                }) {
                                    Icon(Icons.Default.Call, "Голосовой звонок", tint = Accent)
                                }
                                IconButton(onClick = {
                                    pendingCallAction = "start_video"
                                    showCallDialog = true
                                }) {
                                    Icon(Icons.Default.Videocam, "Видеозвонок", tint = Accent)
                                }
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
                            IconButton(onClick = { scope.launch { try { messages = api.messages(chat.id, e2eeClient) } catch (e: Exception) { error = e.message ?: "Не удалось обновить чат" } } }) {
                                Icon(Icons.Default.Refresh, "Обновить сообщения", tint = SecondaryText)
                            }
                        }
                        HorizontalDivider(color = Surface2)
                        if (messages.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(if (busy) "Загружаем сообщения…" else "Напишите первое сообщение 👋", color = SecondaryText, fontSize = 13.sp)
                            }
                        } else {
                            val messageListState = rememberLazyListState()
                            LaunchedEffect(chat.id, messages.size) {
                                if (messages.isNotEmpty()) messageListState.animateScrollToItem(messages.lastIndex)
                            }
                            LazyColumn(
                                state = messageListState,
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                contentPadding = PaddingValues(bottom = 10.dp)
                            ) {
                                items(messages, key = { it.id }) { msg ->
                                    val mine = msg.senderId == store.userId
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                                        Column(
                                            Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(
                                                topStart = 18.dp, topEnd = 18.dp,
                                                bottomStart = if (mine) 18.dp else 5.dp,
                                                bottomEnd = if (mine) 5.dp else 18.dp
                                            )).background(if (mine) Color(0xFF304B83) else Surface1).padding(horizontal = 13.dp, vertical = 10.dp)
                                        ) {
                                            if (msg.encrypted) {
                                                Text("🔒 Зашифровано E2EE", color = Success, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
                                            } else if (msg.securityWarning) {
                                                Text("⚠ Защита не подтверждена", color = Color(0xFFFFC56E), fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
                                            }
                                            if (msg.replyToId != null) {
                                                val parent = messages.firstOrNull { it.id == msg.replyToId }
                                                Text("↪ Ответ: ${parent?.content?.take(70) ?: "сообщение"}", color = Accent, fontSize = 11.sp, modifier = Modifier.padding(bottom = 5.dp))
                                            }
                                            if (msg.messageType == "call" && msg.encrypted) {
                                                val call = runCatching { org.json.JSONObject(msg.content) }.getOrNull()
                                                val video = call?.optString("kind") == "video"
                                                val outgoing = call?.optString("direction") == "outgoing"
                                                val duration = call?.optLong("duration_seconds", 0L) ?: 0L
                                                val minutes = duration / 60L
                                                val seconds = duration % 60L
                                                val result = call?.optString("result").orEmpty()
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF253C56)), contentAlignment = Alignment.Center) {
                                                        Icon(if (video) Icons.Default.Videocam else Icons.Default.Call, null, tint = Color.White)
                                                    }
                                                    Column {
                                                        Text(
                                                            (if (outgoing) "Исходящий " else "Входящий ") + if (video) "видеозвонок" else "аудиозвонок",
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
                                                if (msg.mediaKind == "video") {
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
                                                Text(msg.content, color = MainText, fontSize = 14.sp)
                                            }
                                            Row(
                                                Modifier.fillMaxWidth().padding(top = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                if (msg.editedAt != null) Text("изменено · ", color = Color(0xFFB7C4D9), fontSize = 10.sp)
                                                Text(messageTimeLabel(msg.createdAt), color = Color(0xFFB7C4D9), fontSize = 10.sp)
                                                Box {
                                                    IconButton(
                                                        onClick = { openMessageMenu = if (openMessageMenu == msg.id) null else msg.id },
                                                        modifier = Modifier.size(24.dp)
                                                    ) { Icon(Icons.Default.MoreVert, "Действия с сообщением", tint = Color(0xFFB7C4D9), modifier = Modifier.size(15.dp)) }
                                                    DropdownMenu(
                                                        expanded = openMessageMenu == msg.id,
                                                        onDismissRequest = { openMessageMenu = null }
                                                    ) {
                                                        DropdownMenuItem(text = { Text("Ответить") }, onClick = { replyTarget = msg; openMessageMenu = null })
                                                        if (mine && msg.messageType == "text" && !msg.encrypted) {
                                                            DropdownMenuItem(text = { Text("Изменить") }, onClick = { editingTarget = msg; editDraft = msg.content; openMessageMenu = null })
                                                            DropdownMenuItem(
                                                                text = { Text("Удалить", color = Danger) },
                                                                onClick = {
                                                                    openMessageMenu = null
                                                                    scope.launch {
                                                                        try { api.deleteMessage(msg.id); messages = messages.filterNot { it.id == msg.id } }
                                                                        catch (e: Exception) { error = e.message ?: "Не удалось удалить" }
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
                        if (error.isNotBlank()) Text(error, color = Danger, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp))
                        Column(
                            Modifier.fillMaxWidth().background(Surface1).navigationBarsPadding().imePadding().padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            if (replyTarget != null) {
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2).padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Ответ на сообщение", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        Text(replyTarget!!.content.take(75).ifBlank { "Фото" }, color = SecondaryText, fontSize = 12.sp, maxLines = 1)
                                    }
                                    IconButton(onClick = { replyTarget = null }, modifier = Modifier.size(32.dp)) {
                                        Text("×", color = SecondaryText, fontSize = 24.sp)
                                    }
                                }
                                Spacer(Modifier.height(7.dp))
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { mediaPicker.launch(arrayOf("image/*", "video/*")) },
                                    enabled = !sendingImage,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    if (sendingImage) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = Accent)
                                    else Icon(Icons.Default.Add, "Прикрепить фото или видео", tint = Accent)
                                }
                                OutlinedTextField(
                                    value = draft, onValueChange = { draft = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("Написать сообщение", fontSize = 14.sp) },
                                    maxLines = 5, shape = RoundedCornerShape(24.dp), colors = authFieldColors(),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(onSend = {
                                        val text = draft.trim()
                                        val media = pendingMediaUri
                                        if (media != null && !sendingImage) sendPendingMedia(chat, media, text, replyTarget?.id)
                                        else if (text.isNotEmpty() && !sendingMessage) sendSecureMessage(chat, text, replyTarget?.id)
                                    })
                                )
                                Spacer(Modifier.width(7.dp))
                                IconButton(
                                    onClick = {
                                        val text = draft.trim()
                                        val media = pendingMediaUri
                                        if (media != null && !sendingImage) sendPendingMedia(chat, media, text, replyTarget?.id)
                                        else if (text.isNotEmpty() && !sendingMessage) sendSecureMessage(chat, text, replyTarget?.id)
                                    },
                                    enabled = (draft.isNotBlank() || pendingMediaUri != null) && !sendingMessage && !sendingImage,
                                    modifier = Modifier.size(46.dp).background(if (draft.isNotBlank() || pendingMediaUri != null) Accent else Surface2, CircleShape)
                                ) {
                                    if (sendingMessage || sendingImage) CircularProgressIndicator(Modifier.size(19.dp), color = Bg, strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Send, "Отправить", tint = if (draft.isNotBlank() || pendingMediaUri != null) Bg else SecondaryText)
                                }
                            }
                        }
                    }
                }
            }
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
        color = Color(0xFF171F2D),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 12.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))
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
