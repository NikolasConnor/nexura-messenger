package com.nexura.messenger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexura.messenger.data.*
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0B1018)
private val Panel = Color(0xFF151E2B)
private val Accent = Color(0xFF7C9CFF)
private val TextMain = Color(0xFFF3F5FA)
private val Muted = Color(0xFF98A5B8)

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
private fun NexuraApp(api: NexuraApi, store: SessionStore, authCallback: android.net.Uri?) {
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(!store.accessToken.isNullOrBlank()) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var chats by remember { mutableStateOf(emptyList<ChatItem>()) }
    var selected by remember { mutableStateOf<ChatItem?>(null) }
    var messages by remember { mutableStateOf(emptyList<MessageItem>()) }
    var draft by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var users by remember { mutableStateOf(emptyList<Pair<String,String>>()) }

    fun loadChats() { scope.launch { busy=true; try { chats=api.chats(); error="" } catch(e:Exception){ error=e.message ?: "Не удалось загрузить чаты" } finally { busy=false } } }
    fun loadMessages(chat: ChatItem) { selected=chat; scope.launch { try { messages=api.messages(chat.id) } catch(e:Exception){ error=e.message ?: "Ошибка сообщений" } } }

    LaunchedEffect(authCallback) {
        if (authCallback?.scheme == "nexura" && authCallback.host == "auth" && authCallback.path == "/callback") {
            busy = true
            error = ""
            try {
                api.consumeAuthCallback(authCallback)
                signedIn = true
                notice = "Почта подтверждена. Добро пожаловать в Nexura!"
                loadChats()
            } catch (e: Exception) {
                error = e.message ?: "Не удалось подтвердить почту"
            } finally {
                busy = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary=Accent, background=Bg, surface=Panel, onBackground=TextMain, onSurface=TextMain)) {
        Surface(Modifier.fillMaxSize(), color=Bg) {
            if (!signedIn) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement=Arrangement.Center) {
                    Text("nexura", fontSize=42.sp, fontWeight=FontWeight.Bold, color=TextMain)
                    Text("Общение без границ", color=Muted, modifier=Modifier.padding(bottom=28.dp))
                    if (register) OutlinedTextField(displayName,{displayName=it},label={Text("Ваше имя")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(email,{email=it},label={Text("Email")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(password,{password=it},label={Text("Пароль")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
                    if (error.isNotBlank()) Text(error,color=Color(0xFFFF8585),modifier=Modifier.padding(vertical=8.dp))
                    if (notice.isNotBlank()) Text(notice,color=Color(0xFF8FE0B0),modifier=Modifier.padding(vertical=8.dp))
                    if (!register) TextButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                error = ""
                                notice = ""
                                try {
                                    api.resendConfirmation(email.trim())
                                    notice = "Запрос на повторную отправку принят. Проверьте входящие и папку «Спам»."
                                } catch (e: Exception) {
                                    error = e.message ?: "Не удалось запросить письмо"
                                } finally { busy = false }
                            }
                        },
                        enabled = !busy && email.contains("@")
                    ) { Text("Отправить письмо подтверждения повторно") }
                    Button(onClick={
                        scope.launch { busy=true; error=""; try {
                            if(register) api.signUp(email.trim(),password,displayName.trim()) else api.signIn(email.trim(),password)
                            signedIn=!store.accessToken.isNullOrBlank()
                            if(signedIn) loadChats()
                        } catch(e:Exception){error=e.message ?: "Ошибка авторизации"} finally {busy=false} }
                    }, enabled=!busy && email.isNotBlank() && password.length>=6, modifier=Modifier.fillMaxWidth().padding(top=16.dp)) {
                        Text(if(busy) "Подождите…" else if(register) "Создать аккаунт" else "Войти")
                    }
                    TextButton(onClick={register=!register;error=""}) { Text(if(register) "Уже есть аккаунт? Войти" else "Создать аккаунт") }
                }
            } else if (selected == null) {
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("nexura",fontSize=34.sp,fontWeight=FontWeight.Bold,color=TextMain)
                            Text("Ваши сообщения",color=Muted)
                        }
                        IconButton(onClick={loadChats()}) { Icon(Icons.Default.Refresh,contentDescription="Обновить",tint=TextMain) }
                        TextButton(onClick={scope.launch { api.signOut(); signedIn=false; chats= emptyList() }}) { Text("Выйти") }
                    }
                    OutlinedTextField(search,{search=it},modifier=Modifier.fillMaxWidth().padding(top=18.dp),placeholder={Text("Найти пользователя")},singleLine=true)
                    Button(onClick={scope.launch { try { users=api.findUsers(search) } catch(e:Exception){error=e.message ?: "Поиск недоступен"} }},enabled=search.length>=2,modifier=Modifier.padding(top=6.dp)) { Text("Найти") }
                    users.forEach { user -> Row(Modifier.fillMaxWidth().clickable {
                        scope.launch { try { val id=api.createDirect(user.first); loadChats(); selected=ChatItem(id,user.second,"direct",null); loadMessages(selected!!) } catch(e:Exception){error=e.message ?: "Не удалось открыть чат"} }
                    }.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(Accent,CircleShape),contentAlignment=Alignment.Center){Text(user.second.take(1).uppercase(),color=Bg)}
                        Text(user.second,color=TextMain,modifier=Modifier.padding(start=12.dp))
                    } }
                    if(error.isNotBlank()) Text(error,color=Color(0xFFFF8585),modifier=Modifier.padding(vertical=8.dp))
                    Text("Чаты",fontWeight=FontWeight.SemiBold,fontSize=20.sp,color=TextMain,modifier=Modifier.padding(top=18.dp,bottom=8.dp))
                    if(chats.isEmpty()) Text(if(busy) "Загружаем…" else "Пока нет чатов. Найдите пользователя, чтобы начать общение.",color=Muted)
                    LazyColumn { items(chats,key={it.id}) { chat ->
                        Row(Modifier.fillMaxWidth().clickable { loadMessages(chat) }.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.size(46.dp).background(Panel,CircleShape),contentAlignment=Alignment.Center){Text(if(chat.kind=="group") "👥" else chat.title.take(1).uppercase(),color=TextMain)}
                            Column(Modifier.padding(start=12.dp)) { Text(chat.title,color=TextMain,fontWeight=FontWeight.Medium); Text(if(chat.kind=="group") "Групповой чат" else "Личная переписка",color=Muted,fontSize=12.sp) }
                        }
                        HorizontalDivider(color=Panel)
                    } }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick={selected=null;messages= emptyList();loadChats()}) { Icon(Icons.Default.ArrowBack,contentDescription="Назад",tint=TextMain) }
                        Column(Modifier.weight(1f)) { Text(selected!!.title,color=TextMain,fontWeight=FontWeight.SemiBold); Text("Nexura",color=Muted,fontSize=12.sp) }
                        IconButton(onClick={scope.launch { try { messages=api.messages(selected!!.id) } catch(e:Exception){error=e.message ?: "Ошибка"} }}) { Icon(Icons.Default.Refresh,contentDescription="Обновить",tint=TextMain) }
                    }
                    HorizontalDivider(color=Panel)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(12.dp),reverseLayout=false) {
                        items(messages,key={it.id}) { msg ->
                            val mine=msg.senderId==store.userId
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=if(mine) Arrangement.End else Arrangement.Start) {
                                Column(Modifier.widthIn(max=300.dp).background(if(mine) Color(0xFF263A66) else Panel,RoundedCornerShape(16.dp)).padding(12.dp)) {
                                    Text(msg.content,color=TextMain)
                                    Text(msg.createdAt.replace("T"," ").take(16),color=Muted,fontSize=10.sp,modifier=Modifier.align(Alignment.End))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        OutlinedTextField(draft,{draft=it},modifier=Modifier.weight(1f),placeholder={Text("Сообщение…")},maxLines=4)
                        IconButton(onClick={ val value=draft.trim(); val chat=selected; if(value.isNotEmpty() && chat!=null) scope.launch { try { api.sendMessage(chat.id,value); draft=""; messages=api.messages(chat.id) } catch(e:Exception){error=e.message ?: "Не отправлено"} } },enabled=draft.isNotBlank()) { Icon(Icons.Default.Send,contentDescription="Отправить",tint=Accent) }
                    }
                    if(error.isNotBlank()) Text(error,color=Color(0xFFFF8585),modifier=Modifier.padding(horizontal=16.dp))
                }
            }
        }
    }
}
