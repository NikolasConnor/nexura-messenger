package com.nexura.messenger

import android.Manifest
import android.content.Intent
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import java.util.Locale
import java.util.UUID
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
import androidx.compose.material.icons.filled.ChatBubbleOutline
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import com.google.firebase.messaging.FirebaseMessaging

private val Bg = Color(0xFF0B1220)
private val Surface1 = Color(0xFF151F30)
private val Surface2 = Color(0xFF202D42)
private val Accent = Color(0xFF75B7FF)
private val Accent2 = Color(0xFF8C9EFF)
private val MainText = Color(0xFFF4F6FC)
private val SecondaryText = Color(0xFFA7B4C8)
private val Danger = Color(0xFFFF858D)
private val Success = Color(0xFF8DDEB0)

private data class DeviceContact(val name: String, val phone: String, val match: ContactMatch? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SessionStore(this)
        val api = NexuraApi(store)
        setContent { NexuraApp(api, store, intent?.data) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
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
    var sendingMessage by remember { mutableStateOf(false) }
    var sendingImage by remember { mutableStateOf(false) }
    var mediaViewerUri by remember { mutableStateOf<Uri?>(null) }
    var mediaViewerType by remember { mutableStateOf("image") }
    var profileName by remember { mutableStateOf("") }
    var profileAvatar by remember { mutableStateOf("🙂") }
    var savingProfile by remember { mutableStateOf(false) }
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
        val chat = selected ?: return
        if (uri == null) return
        scope.launch {
            sendingImage = true
            error = ""
            var encrypted: EncryptedMediaFile? = null
            try {
                val client = e2eeClient ?: throw IllegalStateException("Ключ E2EE ещё не готов")
                if (chat.kind == "direct") {
                    val peerId = api.directPeerUserId(chat.id)
                    val devices = api.activeE2eeDevices(peerId)
                    if (devices.size != 1) throw IllegalStateException("Для личного чата требуется ровно одно активное устройство собеседника.")
                    val peer = devices.single()
                    if (!client.isRemoteDeviceTrusted(peerId, peer.deviceId, peer.curve25519Key, peer.ed25519Key)) {
                        throw IllegalStateException("Сначала проверьте отпечаток устройства собеседника через замок вверху чата.")
                    }
                } else {
                    val members = api.groupMemberIds(chat.id).filter { it != store.userId }
                    for (memberId in members) {
                        val devices = api.activeE2eeDevices(memberId)
                        if (devices.isEmpty()) throw IllegalStateException("У участника $memberId нет активного E2EE-устройства.")
                        if (devices.any { !client.isRemoteDeviceTrusted(memberId, it.deviceId, it.curve25519Key, it.ed25519Key) }) {
                            throw IllegalStateException("Сначала проверьте все устройства участников через кнопку замка.")
                        }
                    }
                }
                encrypted = withContext(Dispatchers.IO) {
                    E2eeMediaCrypto.encryptUri(context.contentResolver, uri, context.cacheDir)
                }
                if (chat.kind == "direct") {
                    val peerId = api.directPeerUserId(chat.id)
                    val peer = api.activeE2eeDevices(peerId).single()
                    api.sendEncryptedMediaMessage(chat.id, peer, client, encrypted!!)
                } else {
                    api.sendEncryptedGroupMediaMessage(chat.id, client, encrypted!!)
                }
                messages = api.messages(chat.id, client)
                notice = "Медиа отправлено с E2EE-шифрованием"
            } catch (e: Exception) {
                encrypted?.file?.delete()
                error = e.message ?: "Не удалось отправить медиа"
            } finally {
                sendingImage = false
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        sendPickedMedia(uri)
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        sendPickedMedia(uri)
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
        replyTarget = null
        scope.launch {
            try {
                messages = api.messages(chat.id, e2eeClient)
                api.markChatRead(chat.id)
                chats = chats.map { if (it.id == chat.id) it.copy(unreadCount = 0) else it }
            } catch (e: Exception) { error = e.message ?: "Не удалось загрузить сообщения" }
        }
    }

    fun sendSecureMessage(chat: ChatItem, text: String, replyToId: String?) {
        if (pendingSecureMessageText != null && pendingSecureMessageText != text) {
            error = "Предыдущее зашифрованное сообщение ещё не подтверждено сервером. Повторите отправку того же текста."
            return
        }
        val messageId = pendingSecureMessageId ?: UUID.randomUUID().toString().also {
            pendingSecureMessageId = it
            pendingSecureMessageText = text
        }
        scope.launch {
            sendingMessage = true
            error = ""
            try {
                val client = e2eeClient ?: throw IllegalStateException("Локальный ключ E2EE ещё не готов")
                if (chat.kind == "direct") {
                    val peerId = api.directPeerUserId(chat.id)
                    val devices = api.activeE2eeDevices(peerId)
                    if (devices.size != 1) throw IllegalStateException("Для личного чата требуется ровно одно активное устройство собеседника")
                    val remote = devices.single()
                    if (!client.isRemoteDeviceTrusted(peerId, remote.deviceId, remote.curve25519Key, remote.ed25519Key)) {
                        throw IllegalStateException("Нажмите на замок и сверьте отпечаток устройства собеседника")
                    }
                    api.sendEncryptedDirectMessage(chat.id, remote, client, messageId, text, replyToId)
                } else {
                    val members = api.groupMemberIds(chat.id).filter { it != store.userId }
                    for (memberId in members) {
                        val devices = api.activeE2eeDevices(memberId)
                        if (devices.isEmpty()) throw IllegalStateException("У участника $memberId нет активного E2EE-устройства")
                        if (devices.any { !client.isRemoteDeviceTrusted(memberId, it.deviceId, it.curve25519Key, it.ed25519Key) }) {
                            throw IllegalStateException("Не все устройства участников подтверждены. Нажмите на замок группы.")
                        }
                    }
                    api.sendEncryptedGroupMessage(chat.id, client, messageId, text)
                }
                draft = ""
                replyTarget = null
                pendingSecureMessageId = null
                pendingSecureMessageText = null
                messages = api.messages(chat.id, client)
            } catch (e: Exception) {
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

    LaunchedEffect(signedIn) {
        if (signedIn) {
            try {
                if (store.accessToken.isNullOrBlank()) api.refreshSession()
                api.myProfile() // Create a profile row for accounts that have not opened settings yet.
                // Initialize local E2EE identity and publish public keys when the
                // reviewed key-directory migration has been deployed.
                val e2eeUserId = store.userId
                if (!e2eeUserId.isNullOrBlank()) {
                    val client = E2eeClient(context.applicationContext, e2eeUserId)
                    e2eeClient = client
                    try {
                        val available = api.publishE2eeDevice(client)
                        e2eeStatus = "Устройство зарегистрировано · $available ключей"
                    } catch (e: Exception) {
                        e2eeStatus = "Каталог ключей недоступен: ${e.message ?: "ожидается настройка сервера"}"
                    }
                }
                val pushToken = FirebaseMessaging.getInstance().token.await()
                if (pushToken.isNotBlank()) api.registerPushToken(pushToken)
                chats = api.chats()
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
            while (true) {
                delay(if (selected != null) 5000L else 20000L)
                try {
                    val currentChat = selected
                    if (currentChat != null) {
                        val latest = api.messages(currentChat.id, e2eeClient)
                        if (selected?.id == currentChat.id) messages = latest
                    } else {
                        chats = api.chats()
                    }
                } catch (_: Exception) {
                    // A temporary network interruption should not erase the current screen.
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !phoneMode, onClick = { phoneMode = false; error = ""; notice = "" }, label = { Text("Email") })
                            FilterChip(selected = phoneMode, onClick = { phoneMode = true; error = ""; notice = "" }, label = { Text("Телефон") })
                        }
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
                                onClick = {
                                    val redirect = java.net.URLEncoder.encode(NexuraApi.AUTH_REDIRECT, "UTF-8")
                                    val url = "${NexuraApi.BASE_URL}/auth/v1/authorize?provider=google&redirect_to=$redirect"
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MainText)
                            ) { Text("Продолжить с Google", fontWeight = FontWeight.Medium) }
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
                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { settingsOpen = false; error = ""; notice = "" }) { Icon(Icons.Default.ArrowBack, "Назад", tint = MainText) }
                            Text("Настройки", color = MainText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("АККАУНТ", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Card(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 22.dp), colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) { Avatar(profileAvatar); Column(Modifier.padding(start = 12.dp)) { Text(profileName.ifBlank { "Ваш аккаунт Nexura" }, color = MainText, fontWeight = FontWeight.SemiBold); Text("Профиль Nexura", color = Success, fontSize = 12.sp) } }
                                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Surface2)
                                Text("Имя в Nexura", color = SecondaryText, fontSize = 12.sp)
                                OutlinedTextField(value = profileName, onValueChange = { profileName = it.take(40) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true, placeholder = { Text("Ваше имя") }, shape = RoundedCornerShape(12.dp), colors = authFieldColors())
                                Text("Аватар", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 7.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("🙂", "😎", "🦊", "🐼", "🐸", "🐱", "🚀", "🌙").forEach { emoji ->
                                        Box(Modifier.size(38.dp).clip(CircleShape).background(if (profileAvatar == emoji) Accent.copy(alpha = 0.25f) else Surface2).clickable { profileAvatar = emoji }, contentAlignment = Alignment.Center) { Text(emoji, fontSize = 21.sp) }
                                    }
                                }
                                Button(onClick = { scope.launch { savingProfile = true; error = ""; notice = ""; try { api.updateMyProfile(profileName, profileAvatar); notice = "Профиль сохранён." } catch (e: Exception) { error = e.message ?: "Не удалось сохранить профиль" } finally { savingProfile = false } } }, enabled = !savingProfile && profileName.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 14.dp), shape = RoundedCornerShape(12.dp)) { Text(if (savingProfile) "Сохраняем…" else "Сохранить профиль") }
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
                                Text("Для защиты переписки сравните отпечатки устройств лично или по другому доверенному каналу. Группы и вложения пока не поддерживают E2EE.", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                            }
                        }
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Авторизация", color = MainText, fontWeight = FontWeight.SemiBold)
                                Text("Сессия хранится на устройстве в зашифрованном виде.", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                                OutlinedButton(onClick = { scope.launch { busy = true; error = ""; notice = ""; try { api.refreshSession(); chats = api.chats(); notice = "Сессия успешно обновлена." } catch (e: Exception) { error = e.message ?: "Не удалось обновить сессию" } finally { busy = false } } }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), shape = RoundedCornerShape(13.dp)) { Text(if (busy) "Проверяем…" else "Проверить и обновить сессию") }
                            }
                        }
                        if (error.isNotBlank()) Text(error, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                        if (notice.isNotBlank()) Text(notice, color = Success, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { showLogoutDialog = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).height(50.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Text("Выйти из аккаунта") }
                    }
                    if (showLogoutDialog) AlertDialog(onDismissRequest = { showLogoutDialog = false }, title = { Text("Выйти из Nexura?") }, text = { Text("Вы сможете войти снова, используя свой способ авторизации.") }, confirmButton = { TextButton(onClick = { showLogoutDialog = false; scope.launch { busy = true; try { api.signOut(); signedIn = false; chats = emptyList(); selected = null; settingsOpen = false; e2eeClient = null; e2eeStatus = "Шифрование не проверено"; notice = "" } catch (e: Exception) { error = e.message ?: "Ошибка выхода" } finally { busy = false } } }) { Text("Выйти", color = Danger) } }, dismissButton = { TextButton(onClick = { showLogoutDialog = false }) { Text("Отмена") } })
                }
                selected == null -> {
                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Nexura", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = MainText, letterSpacing = (-0.5).sp)
                                Text("Всегда на связи", color = SecondaryText, fontSize = 13.sp)
                            }
                            IconButton(onClick = { showChatFilter = !showChatFilter; if (!showChatFilter) chatFilter = "" }) {
                                Icon(Icons.Default.Search, "Поиск по чатам", tint = MainText)
                            }
                            IconButton(onClick = { createGroupOpen = true; groupTitle = ""; groupSearch = ""; groupUsers = emptyList(); selectedGroupUsers = emptySet(); error = "" }) { Icon(Icons.Default.GroupAdd, "Создать группу", tint = Accent) }
                            IconButton(onClick = { settingsOpen = true; error = ""; notice = ""; scope.launch { try { val p = api.myProfile(); profileName = p.displayName; profileAvatar = p.avatar.ifBlank { "🙂" } } catch (e: Exception) { error = e.message ?: "Не удалось загрузить профиль" } } }) {
                                Icon(Icons.Default.Settings, "Профиль и настройки", tint = SecondaryText)
                            }
                        }
                        if (homeTab == "chats" && showChatFilter) {
                            OutlinedTextField(
                                value = chatFilter,
                                onValueChange = { chatFilter = it },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                singleLine = true,
                                placeholder = { Text("Поиск по вашим чатам") },
                                leadingIcon = { Icon(Icons.Default.Search, null, tint = SecondaryText) },
                                trailingIcon = {
                                    if (chatFilter.isNotBlank()) TextButton(onClick = { chatFilter = "" }) { Text("Очистить") }
                                },
                                shape = RoundedCornerShape(14.dp),
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
                                        Row(Modifier.fillMaxWidth().clickable { selectedGroupUsers = if (person.id in selectedGroupUsers) selectedGroupUsers - person.id else selectedGroupUsers + person.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = person.id in selectedGroupUsers, onCheckedChange = { checked -> selectedGroupUsers = if (checked) selectedGroupUsers + person.id else selectedGroupUsers - person.id })
                                            Avatar(person.displayName, person.avatar)
                                            Text(person.displayName, color = MainText, modifier = Modifier.padding(start = 10.dp).weight(1f))
                                        }
                                    }
                                    Text("Выбрано: ${selectedGroupUsers.size}", color = SecondaryText, fontSize = 12.sp)
                                    Button(onClick = { scope.launch { creatingGroup = true; error = ""; try { val id = api.createGroup(groupTitle, selectedGroupUsers.toList()); val chat = ChatItem(id, groupTitle.trim(), "group", null); createGroupOpen = false; selected = chat; messages = emptyList(); chats = api.chats() } catch (e: Exception) { error = e.message ?: "Не удалось создать группу" } finally { creatingGroup = false } } }, enabled = !creatingGroup && groupTitle.trim().length >= 2 && selectedGroupUsers.isNotEmpty(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(if (creatingGroup) "Создаём…" else "Создать группу") }
                                }
                            }
                        }
                        if (homeTab == "contacts") {
                            Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
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
                                            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                                if (person.match != null) Avatar(person.match.displayName, person.match.avatar) else Avatar(person.name, "👤")
                                                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                                    Text(person.match?.displayName ?: person.name, color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                                    Text(if (person.match != null) "Уже в Nexura" else "Пока не зарегистрирован", color = if (person.match != null) Success else SecondaryText, fontSize = 11.sp)
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
                                                    }) { Text("Написать") }
                                                } else TextButton(onClick = { inviteContact(person) }) { Text("Пригласить") }
                                            }
                                            HorizontalDivider(color = Surface1)
                                        }
                                    }
                                } else Spacer(Modifier.weight(1f))
                            }
                        }
                        if (homeTab == "search") {
                        OutlinedTextField(
                            value = search, onValueChange = { search = it; users = emptyList() },
                            modifier = Modifier.fillMaxWidth(), placeholder = { Text("Найти человека по имени") },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = SecondaryText) },
                            trailingIcon = { if (search.isNotBlank()) TextButton(onClick = { search = ""; users = emptyList() }) { Text("Сбросить") } },
                            singleLine = true, shape = RoundedCornerShape(16.dp), colors = authFieldColors(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                if (search.length >= 2) scope.launch { searching = true; try { users = api.findUsers(search) } catch (e: Exception) { error = e.message ?: "Поиск недоступен" }; searching = false }
                            })
                        )
                        Button(
                            onClick = { scope.launch { searching = true; error = ""; try { users = api.findUsers(search) } catch (e: Exception) { error = e.message ?: "Поиск недоступен" }; searching = false } },
                            enabled = search.length >= 2 && !searching,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(44.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text(if (searching) "Ищем…" else "Найти пользователя") }
                        if (users.isNotEmpty()) {
                            Text("Результаты поиска", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                            users.forEach { user ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                                        scope.launch {
                                            try {
                                                val id = api.createDirect(user.id)
                                                val chat = ChatItem(id, user.displayName, "direct", null, avatar = user.avatar)
                                                selected = chat
                                                messages = api.messages(id, e2eeClient)
                                                loadChats()
                                            } catch (e: Exception) { error = e.message ?: "Не удалось открыть чат" }
                                        }
                                    }.padding(vertical = 9.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Avatar(user.displayName, user.avatar)
                                    Text(user.displayName, color = MainText, modifier = Modifier.padding(start = 12.dp).weight(1f))
                                    Text("Написать", color = Accent, fontSize = 12.sp)
                                }
                            }
                        }
                        }
                        if (homeTab == "chats") {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Недавние", color = MainText, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${visibleChats.size} чатов", color = SecondaryText, fontSize = 12.sp)
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
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { openChat(chat) }.padding(vertical = 13.dp, horizontal = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (chat.kind == "group") GroupAvatar() else Avatar(chat.title, chat.avatar)
                                        Column(Modifier.weight(1f).padding(start = 13.dp)) {
                                            Text(chat.title, color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                                            Text(if (chat.kind == "group") "Групповой чат · Открыть переписку" else "Личный чат · Открыть переписку", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                                        }
                                        if (chat.unreadCount > 0) {
                                            Box(Modifier.clip(CircleShape).background(Accent).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                                Text(chat.unreadCount.coerceAtMost(99).toString(), color = Bg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        } else Text("›", color = SecondaryText, fontSize = 24.sp)
                                    }
                                    HorizontalDivider(color = Surface1)
                                }
                            }
                        }
                        }
                        NavigationBar(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = Surface1,
                            tonalElevation = 0.dp
                        ) {
                            NavigationBarItem(
                                selected = homeTab == "chats",
                                onClick = { homeTab = "chats"; createGroupOpen = false },
                                icon = { Icon(Icons.Default.ChatBubbleOutline, contentDescription = null) },
                                label = { Text("Чаты") }
                            )
                            NavigationBarItem(
                                selected = homeTab == "contacts",
                                onClick = { homeTab = "contacts"; createGroupOpen = false },
                                icon = { Icon(Icons.Default.GroupAdd, contentDescription = null) },
                                label = { Text("Контакты") }
                            )
                            NavigationBarItem(
                                selected = homeTab == "search",
                                onClick = { homeTab = "search"; createGroupOpen = false },
                                icon = { Icon(Icons.Default.Search, contentDescription = null) },
                                label = { Text("Поиск") }
                            )
                            NavigationBarItem(
                                selected = settingsOpen,
                                onClick = {
                                    settingsOpen = true
                                    error = ""; notice = ""
                                    scope.launch {
                                        try {
                                            val p = api.myProfile()
                                            profileName = p.displayName
                                            profileAvatar = p.avatar.ifBlank { "🙂" }
                                        } catch (e: Exception) {
                                            error = e.message ?: "Не удалось загрузить профиль"
                                        }
                                    }
                                },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Профиль") }
                            )
                        }
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
                            onDismissRequest = { trustCandidate = null; trustPeerId = null },
                            title = { Text("Проверка устройства") },
                            text = {
                                Column {
                                    Text("Сравните отпечаток собеседника с отпечатком его устройства по другому доверенному каналу — например, лично или по видеосвязи. Не подтверждайте ключ, если сравнить его не удалось.")
                                    Text("Устройство собеседника", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
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
                                        trustCandidate = null
                                        trustPeerId = null
                                    } catch (e: Exception) { error = e.message ?: "Не удалось сохранить доверие" }
                                }) { Text("Я сверил отпечаток") }
                            },
                            dismissButton = {
                                TextButton(onClick = { trustCandidate = null; trustPeerId = null }) { Text("Отмена") }
                            }
                        )
                    }
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().background(Surface1).padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { selected = null; messages = emptyList(); replyTarget = null; loadChats() }) {
                                Icon(Icons.Default.ArrowBack, "Назад к чатам", tint = MainText)
                            }
                            if (chat.kind == "group") GroupAvatar() else Avatar(chat.title, chat.avatar)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(chat.title, color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                                Text(
                                    if (chat.kind == "group") "Групповой чат · E2EE пока недоступно"
                                    else "Личная переписка · требуется проверка устройства",
                                    color = SecondaryText,
                                    fontSize = 12.sp
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        val peerId = api.directPeerUserId(chat.id)
                                        val devices = api.activeE2eeDevices(peerId)
                                        if (devices.size != 1) throw IllegalStateException("Найдено устройств: ${devices.size}. Пока поддерживается одно.")
                                        trustPeerId = peerId
                                        trustCandidate = devices.single()
                                    } catch (e: Exception) {
                                        error = e.message ?: "Не удалось получить ключи устройства"
                                    }
                                }
                            }, enabled = chat.kind == "direct") {
                                Icon(Icons.Default.Lock, "Проверить устройство", tint = Accent)
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
                                            if (msg.messageType == "media" && msg.encrypted) {
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
                                                } else {
                                                    AsyncImage(
                                                        model = mediaUri,
                                                        contentDescription = "Фото",
                                                        modifier = Modifier.width(230.dp).heightIn(max = 250.dp).clip(RoundedCornerShape(12.dp))
                                                            .clickable { mediaViewerUri = mediaUri; mediaViewerType = "image" },
                                                        contentScale = ContentScale.Crop
                                                    )
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
                                                Text(msg.createdAt.replace("T", " ").take(16), color = Color(0xFFB7C4D9), fontSize = 10.sp)
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
                            Text(
                                if (chat.kind == "direct") "Медиа шифруется на устройстве перед отправкой · до 24 МБ"
                                else "E2EE-медиа для групп пока не поддерживается",
                                color = SecondaryText, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp, bottom = 5.dp)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { imagePicker.launch("image/*") },
                                    enabled = !sendingImage && chat.kind == "direct",
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    if (sendingImage) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = Accent)
                                    else Icon(Icons.Default.Image, "Отправить фото", tint = Accent)
                                }
                                IconButton(
                                    onClick = { videoPicker.launch("video/mp4") },
                                    enabled = !sendingImage && chat.kind == "direct",
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Text("🎬", fontSize = 19.sp)
                                }
                                OutlinedTextField(
                                    value = draft, onValueChange = { draft = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("Написать сообщение", fontSize = 14.sp) },
                                    maxLines = 5, shape = RoundedCornerShape(24.dp), colors = authFieldColors(),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(onSend = {
                                        val text = draft.trim()
                                        if (text.isNotEmpty() && !sendingMessage) sendSecureMessage(chat, text, replyTarget?.id)
                                    })
                                )
                                Spacer(Modifier.width(7.dp))
                                IconButton(
                                    onClick = {
                                        val text = draft.trim()
                                        if (text.isNotEmpty() && !sendingMessage) sendSecureMessage(chat, text, replyTarget?.id)
                                    },
                                    enabled = draft.isNotBlank() && !sendingMessage,
                                    modifier = Modifier.size(46.dp).background(if (draft.isNotBlank()) Accent else Surface2, CircleShape)
                                ) {
                                    if (sendingMessage) CircularProgressIndicator(Modifier.size(19.dp), color = Bg, strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Send, "Отправить", tint = if (draft.isNotBlank()) Bg else SecondaryText)
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
    Box(Modifier.size(54.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Accent, Accent2))), contentAlignment = Alignment.Center) {
        when {
            imageUrl != null -> AsyncImage(model = imageUrl, contentDescription = "Аватар $name", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            !emoji.isNullOrBlank() -> Text(emoji, fontSize = 23.sp)
            else -> Text(name.trim().firstOrNull()?.uppercase() ?: "N", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun GroupAvatar() {
    Box(Modifier.size(54.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
        Text("👥", fontSize = 22.sp)
    }
}
