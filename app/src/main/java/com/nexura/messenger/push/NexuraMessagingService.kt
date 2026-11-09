package com.nexura.messenger.push

import com.nexura.messenger.data.NexuraApi
import com.nexura.messenger.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import java.util.UUID
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nexura.messenger.MainActivity

class NexuraMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = SessionStore(applicationContext)
                if (store.accessToken.isNullOrBlank()) return@launch
                val api = NexuraApi(store)
                if (store.userId.isNullOrBlank()) api.refreshSession()
                if (!store.userId.isNullOrBlank()) api.registerPushToken(token)
            } catch (_: Exception) {
                // Registration is retried by MainActivity after the next sign-in.
            }
        }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        val incomingCall = message.data["type"] == "incoming_call"
        val title = message.data["title"] ?: message.notification?.title ?: "Nexura"
        val body = message.data["body"] ?: message.notification?.body ?: if (incomingCall) "Входящий звонок" else "Новое сообщение"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Сообщения и звонки", NotificationManager.IMPORTANCE_HIGH))
        }
        val conversationId = message.data["conversation_id"]?.let { raw ->
            runCatching { UUID.fromString(raw).toString() }.getOrNull()
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (conversationId != null) {
                action = "com.nexura.messenger.OPEN_CHAT"
                data = Uri.parse("nexura://chat/$conversationId" + if (incomingCall) "?call=1" else "")
            }
        }
        val notificationId = conversationId?.hashCode() ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val pending = PendingIntent.getActivity(this, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(pending)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(if (incomingCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_MAX).build()
        NotificationManagerCompat.from(this).notify(notificationId, notification)
    }
    companion object { private const val CHANNEL_ID = "nexura_messages" }
}
