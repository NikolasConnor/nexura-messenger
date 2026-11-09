package com.nexura.messenger.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nexura.messenger.MainActivity
import java.util.UUID

class NexuraMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        ).launch {
            try {
                val store = com.nexura.messenger.data.SessionStore(applicationContext)
                if (store.accessToken.isNullOrBlank()) return@launch
                val api = com.nexura.messenger.data.NexuraApi(store)
                if (store.userId.isNullOrBlank()) api.refreshSession()
                if (!store.userId.isNullOrBlank()) api.registerPushToken(token)
            } catch (_: Exception) {
                // MainActivity retries registration after the next successful sign-in.
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val incomingCall = data["type"] == "incoming_call"
        val conversationId = data["conversation_id"]?.let { raw ->
            runCatching { UUID.fromString(raw).toString() }.getOrNull()
        }
        val callId = data["call_id"]?.takeIf { it.length <= 64 }
        val video = data["video"] == "true"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(manager)

        val notificationId = if (incomingCall && conversationId != null) {
            conversationId.hashCode()
        } else {
            conversationId?.hashCode() ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        }

        val baseUri = if (conversationId != null) {
            "nexura://chat/$conversationId?call=1"
        } else {
            "nexura://"
        }
        val contentIntent = if (incomingCall && conversationId != null && callId != null) {
            callPendingIntent(baseUri, "show", notificationId)
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (conversationId != null) {
                    action = "com.nexura.messenger.OPEN_CHAT"
                    data = Uri.parse("nexura://chat/$conversationId")
                }
            }
            PendingIntent.getActivity(
                this, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val title = if (incomingCall) {
            if (video) "Входящий видеозвонок" else "Входящий аудиозвонок"
        } else {
            data["title"] ?: message.notification?.title ?: "Nexura"
        }
        val body = if (incomingCall) {
            "Вам звонят в Nexura"
        } else {
            data["body"] ?: message.notification?.body ?: "Новое сообщение"
        }

        val builder = NotificationCompat.Builder(
            this, if (incomingCall) CALL_CHANNEL_ID else MESSAGE_CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(if (incomingCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(!incomingCall)
            .setPriority(if (incomingCall) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)

        if (incomingCall && conversationId != null && callId != null) {
            val fullScreenIntent = callPendingIntent(baseUri, "show", notificationId + 1)
            val acceptIntent = callPendingIntent(baseUri, "accept", notificationId + 2)
            val declineIntent = callPendingIntent(baseUri, "decline", notificationId + 3)
            val caller = Person.Builder().setName("Входящий звонок").setImportant(true).build()
            builder
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declineIntent, acceptIntent))
                .setFullScreenIntent(fullScreenIntent, true)
                .setOngoing(true)
                .setTimeoutAfter(CALL_RING_TIMEOUT_MS)
                .addAction(
                    android.R.drawable.sym_action_call,
                    "Принять",
                    acceptIntent
                )
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Отклонить",
                    declineIntent
                )
        }

        try {
            NotificationManagerCompat.from(this).notify(notificationId, builder.build())
            if (incomingCall && conversationId != null && callId != null &&
                getSharedPreferences("nexura_runtime", Context.MODE_PRIVATE)
                    .getBoolean("activity_visible", false)
            ) {
                val launch = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    action = "com.nexura.messenger.INCOMING_CALL"
                    data = Uri.parse("nexura://chat/$conversationId?call=1&action=show")
                }
                startActivity(launch)
            }
        } catch (_: SecurityException) {
            // Notifications are disabled by the user; call signaling remains available in-app.
        }
    }

    private fun callPendingIntent(baseUri: String, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "com.nexura.messenger.INCOMING_CALL"
            data = Uri.parse("$baseUri&action=$action")
        }
        return PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    MESSAGE_CHANNEL_ID,
                    "Сообщения",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CALL_CHANNEL_ID,
                    "Входящие звонки",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Входящие аудио- и видеозвонки Nexura"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setBypassDnd(false)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    vibrationPattern = longArrayOf(0, 900, 500, 900, 500, 900)
                }
            )
        }
    }

    companion object {
        fun cancelIncomingCall(context: Context, conversationId: String) {
            val id = runCatching { UUID.fromString(conversationId).toString().hashCode() }.getOrNull() ?: return
            NotificationManagerCompat.from(context).cancel(id)
        }

        const val CALL_CHANNEL_ID = "nexura_incoming_calls_v2"
        private const val MESSAGE_CHANNEL_ID = "nexura_messages"
        private const val CALL_RING_TIMEOUT_MS = 35_000L
    }
}
