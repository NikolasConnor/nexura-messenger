package com.nexura.messenger

import android.Manifest
import android.content.Intent
import android.os.Build
import android.net.Uri
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
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexura.messenger.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import com.google.firebase.messaging.FirebaseMessaging

private val Bg = Color(0xFF080D16)
private val Surface1 = Color(0xFF111A29)
private val Surface2 = Color(0xFF172337)
private val Accent = Color(0xFF86A5FF)
private val Accent2 = Color(0xFF9A8BFF)
private val MainText = Color(0xFFF4F6FC)
private val SecondaryText = Color(0xFF91A0B7)
private val Danger = Color(0xFFFF858D)
private val Success = Color(0xFF8DDEB0)

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
    var showLogoutDialog by remember { mutableStateOf(false) }
    var sendingMessage by remember { mutableStateOf(false) }
    var sendingImage by remember { mutableStateOf(false) }
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

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && selected != null) {
            scope.launch {
                sendingImage = true
                error = ""
                try {
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Не удалось прочитать изображение")
                    api.sendImageMessage(selected!!.id, bytes, mime)
                    messages = api.messages(selected!!.id)
                } catch (e: Exception) {
                    error = e.message ?: "Не удалось отправить изображение"
                } finally {
                    sendingImage = false
                }
            }
        }
    }

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
                messages = api.messages(chat.id)
                api.markChatRead(chat.id)
                chats = chats.map { if (it.id == chat.id) it.copy(unreadCount = 0) else it }
            } catch (e: Exception) { error = e.message ?: "Не удалось загрузить сообщения" }
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
                delay(if (selected != null) 2500L else 15000L)
                try {
                    val currentChat = selected
                    if (currentChat != null) {
                        val latest = api.messages(currentChat.id)
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
                        Card(Modifier.fillMaxWidth().padding(top = 10.dp), colors = CardDefaults.cardColors(containerColor = Surface1), shape = RoundedCornerShape(18.dp)) {
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
                    if (showLogoutDialog) AlertDialog(onDismissRequest = { showLogoutDialog = false }, title = { Text("Выйти из Nexura?") }, text = { Text("Вы сможете войти снова, используя свой способ авторизации.") }, confirmButton = { TextButton(onClick = { showLogoutDialog = false; scope.launch { busy = true; try { api.signOut(); signedIn = false; chats = emptyList(); selected = null; settingsOpen = false; notice = "" } catch (e: Exception) { error = e.message ?: "Ошибка выхода" } finally { busy = false } } }) { Text("Выйти", color = Danger) } }, dismissButton = { TextButton(onClick = { showLogoutDialog = false }) { Text("Отмена") } })
                }
                selected == null -> {
                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("nexura", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, color = MainText, letterSpacing = (-1).sp)
                                Text("Сообщения", color = SecondaryText, fontSize = 13.sp)
                            }
                            IconButton(onClick = { loadChats() }) { Icon(Icons.Default.Refresh, "Обновить", tint = MainText) }
                            IconButton(onClick = { createGroupOpen = true; groupTitle = ""; groupSearch = ""; groupUsers = emptyList(); selectedGroupUsers = emptySet(); error = "" }) { Icon(Icons.Default.GroupAdd, "Создать группу", tint = Accent) }
                            IconButton(onClick = { settingsOpen = true; error = ""; notice = ""; scope.launch { try { val p = api.myProfile(); profileName = p.displayName; profileAvatar = p.avatar.ifBlank { "🙂" } } catch (e: Exception) { error = e.message ?: "Не удалось загрузить профиль" } } }) {
                                Icon(Icons.Default.Settings, "Настройки", tint = SecondaryText)
                            }
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
                                                messages = api.messages(id)
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
                        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Ваши чаты", color = MainText, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${chats.size}", color = SecondaryText, fontSize = 13.sp)
                        }
                        if (error.isNotBlank()) Text(error, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                        if (chats.isEmpty()) {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(76.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.ChatBubbleOutline, null, tint = Accent, modifier = Modifier.size(34.dp))
                                    }
                                    Text(if (busy) "Загружаем чаты…" else "Здесь пока тихо", color = MainText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                                    Text("Найдите человека выше и начните разговор", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        } else {
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
                                items(chats, key = { it.id }) { chat ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { openChat(chat) }.padding(vertical = 11.dp, horizontal = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (chat.kind == "group") GroupAvatar() else Avatar(chat.title, chat.avatar)
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                            Text(chat.title, color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                            Text(if (chat.kind == "group") "Групповой чат" else "Личная переписка", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
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
                            confirmButton = { TextButton(onClick = { val target = editingTarget!!; scope.launch { try { api.editMessage(target.id, editDraft); messages = api.messages(selected!!.id); editingTarget = null } catch (e: Exception) { error = e.message ?: "Не удалось изменить сообщение" } } }) { Text("Сохранить") } },
                            dismissButton = { TextButton(onClick = { editingTarget = null }) { Text("Отмена") } }
                        )
                    }
                    val chat = selected!!
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().background(Surface1).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selected = null; messages = emptyList(); loadChats() }) { Icon(Icons.Default.ArrowBack, "Назад", tint = MainText) }
                            if (chat.kind == "group") GroupAvatar() else Avatar(chat.title)
                            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                Text(chat.title, color = MainText, fontWeight = FontWeight.SemiBold)
                                Text(if (chat.kind == "group") "Группа" else "Nexura", color = SecondaryText, fontSize = 11.sp)
                            }
                            IconButton(onClick = { scope.launch { try { messages = api.messages(chat.id) } catch (e: Exception) { error = e.message ?: "Ошибка обновления" } } }) {
                                Icon(Icons.Default.Refresh, "Обновить", tint = SecondaryText)
                            }
                        }
                        HorizontalDivider(color = Surface2)
                        if (messages.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("Напишите первое сообщение 👋", color = SecondaryText, fontSize = 13.sp)
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
                                            if (msg.replyToId != null) {
                                                val parent = messages.firstOrNull { it.id == msg.replyToId }
                                                Text("↪ Ответ: ${parent?.content?.take(70) ?: "сообщение"}", color = Accent, fontSize = 11.sp, modifier = Modifier.padding(bottom = 5.dp))
                                            }
                                            if (msg.messageType == "image") {
                                                AsyncImage(model = msg.content, contentDescription = "Изображение", modifier = Modifier.width(230.dp).heightIn(max = 250.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                            } else {
                                                Text(msg.content, color = MainText, fontSize = 14.sp)
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 5.dp)) {
                                                if (msg.editedAt != null) Text("изменено · ", color = Color(0xFFB7C4D9), fontSize = 9.sp)
                                                Text(msg.createdAt.replace("T", " ").take(16), color = Color(0xFFB7C4D9), fontSize = 9.sp)
                                            }
                                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                                TextButton(onClick = { replyTarget = msg }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("Ответить", fontSize = 10.sp, color = Accent) }
                                                if (mine && msg.messageType == "text") {
                                                    TextButton(onClick = { editingTarget = msg; editDraft = msg.content }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("Изменить", fontSize = 10.sp, color = Accent) }
                                                    TextButton(onClick = { scope.launch { try { api.deleteMessage(msg.id); messages = messages.filterNot { it.id == msg.id } } catch (e: Exception) { error = e.message ?: "Не удалось удалить" } } }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("Удалить", fontSize = 10.sp, color = Danger) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (error.isNotBlank()) Text(error, color = Danger, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp))
                        Row(
                            Modifier.fillMaxWidth().background(Surface1).navigationBarsPadding().imePadding().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (replyTarget != null) {
                                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text("Ответ на: ${replyTarget!!.content.take(45)}", color = Accent, fontSize = 11.sp)
                                    TextButton(onClick = { replyTarget = null }, contentPadding = PaddingValues(0.dp)) { Text("Отменить", color = SecondaryText, fontSize = 10.sp) }
                                }
                            }
                            IconButton(onClick = { imagePicker.launch("image/*") }, enabled = !sendingImage && !sendingMessage) {
                                if (sendingImage) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = Accent)
                                else Icon(Icons.Default.Image, "Прикрепить фото", tint = Accent)
                            }
                            OutlinedTextField(
                                value = draft, onValueChange = { draft = it },
                                modifier = Modifier.weight(1f), placeholder = { Text("Сообщение…") },
                                maxLines = 4, shape = RoundedCornerShape(22.dp), colors = authFieldColors(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    val text = draft.trim()
                                    if (text.isNotEmpty() && !sendingMessage) scope.launch {
                                        sendingMessage = true
                                        error = ""
                                        try { api.sendMessage(chat.id, text, replyTarget?.id); draft = ""; replyTarget = null; messages = api.messages(chat.id) }
                                        catch (e: Exception) { error = e.message ?: "Не удалось отправить сообщение" }
                                        finally { sendingMessage = false }
                                    }
                                })
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    val text = draft.trim()
                                    if (text.isNotEmpty() && !sendingMessage) scope.launch {
                                        sendingMessage = true
                                        error = ""
                                        try { api.sendMessage(chat.id, text); draft = ""; messages = api.messages(chat.id) }
                                        catch (e: Exception) { error = e.message ?: "Не удалось отправить сообщение" }
                                        finally { sendingMessage = false }
                                    }
                                },
                                enabled = draft.isNotBlank() && !sendingMessage,
                                modifier = Modifier.size(48.dp).background(if (draft.isNotBlank()) Accent else Surface2, CircleShape)
                            ) { if (sendingMessage) CircularProgressIndicator(Modifier.size(19.dp), color = Bg, strokeWidth = 2.dp) else Icon(Icons.Default.Send, "Отправить", tint = if (draft.isNotBlank()) Bg else SecondaryText) }
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
    Box(Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Accent, Accent2))), contentAlignment = Alignment.Center) {
        when {
            imageUrl != null -> AsyncImage(model = imageUrl, contentDescription = "Аватар $name", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            !emoji.isNullOrBlank() -> Text(emoji, fontSize = 23.sp)
            else -> Text(name.trim().firstOrNull()?.uppercase() ?: "N", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun GroupAvatar() {
    Box(Modifier.size(48.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
        Text("👥", fontSize = 22.sp)
    }
}
