package com.nexura.messenger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexura.messenger.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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
    var users by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var searching by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var sendingMessage by remember { mutableStateOf(false) }

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
        scope.launch {
            try { messages = api.messages(chat.id) }
            catch (e: Exception) { error = e.message ?: "Не удалось загрузить сообщения" }
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
                                Row(verticalAlignment = Alignment.CenterVertically) { Avatar("N"); Column(Modifier.padding(start = 12.dp)) { Text("Ваш аккаунт Nexura", color = MainText, fontWeight = FontWeight.SemiBold); Text("Вы вошли в систему", color = Success, fontSize = 12.sp) } }
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
                            IconButton(onClick = { settingsOpen = true; error = ""; notice = "" }) {
                                Icon(Icons.Default.Settings, "Настройки", tint = SecondaryText)
                            }
                        }
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
                                                val id = api.createDirect(user.first)
                                                val chat = ChatItem(id, user.second, "direct", null)
                                                selected = chat
                                                messages = api.messages(id)
                                                loadChats()
                                            } catch (e: Exception) { error = e.message ?: "Не удалось открыть чат" }
                                        }
                                    }.padding(vertical = 9.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Avatar(user.second)
                                    Text(user.second, color = MainText, modifier = Modifier.padding(start = 12.dp).weight(1f))
                                    Text("Написать", color = Accent, fontSize = 12.sp)
                                }
                            }
                        }
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
                                        if (chat.kind == "group") GroupAvatar() else Avatar(chat.title)
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                            Text(chat.title, color = MainText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                            Text(if (chat.kind == "group") "Групповой чат" else "Личная переписка", color = SecondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                                        }
                                        Text("›", color = SecondaryText, fontSize = 24.sp)
                                    }
                                    HorizontalDivider(color = Surface1)
                                }
                            }
                        }
                    }
                }
                else -> {
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
                                            Text(msg.content, color = MainText, fontSize = 14.sp)
                                            Text(msg.createdAt.replace("T", " ").take(16), color = Color(0xFFB7C4D9), fontSize = 9.sp, modifier = Modifier.align(Alignment.End).padding(top = 5.dp))
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
                                        try { api.sendMessage(chat.id, text); draft = ""; messages = api.messages(chat.id) }
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
private fun Avatar(name: String) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Accent, Accent2))), contentAlignment = Alignment.Center) {
        Text(name.trim().firstOrNull()?.uppercase() ?: "N", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun GroupAvatar() {
    Box(Modifier.size(48.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
        Text("👥", fontSize = 22.sp)
    }
}
