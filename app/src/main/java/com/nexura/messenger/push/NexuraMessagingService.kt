package com.nexura.messenger.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nexura.messenger.MainActivity

class NexuraMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // WorkManager keeps registration pending across process death and network loss.
        PushTokenSyncWorker.enqueue(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = message.data
        // Calls are intentionally disabled while the core messenger is being stabilized.
        // Ignore legacy/server-side call pushes; message notifications continue normally.
        if (payload["type"] == "incoming_call") return

        val conversationId = payload["conversation_id"]?.takeIf {
            it.matches(Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
        }
        val messageId = payload["message_id"]?.takeIf {
            it.matches(Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(manager)
        // Match Telegram-style behavior: suppress only the chat currently open on screen.
        // Other conversations and all background notifications remain enabled.
        if (conversationId != null) {
            val runtime = getSharedPreferences("nexura_runtime", Context.MODE_PRIVATE)
            val appVisible = runtime.getBoolean("activity_visible", false)
            val activeConversation = runtime.getString("active_conversation_id", null)
            if (appVisible && activeConversation == conversationId) return
        }

        val notificationId = messageId?.hashCode() ?: conversationId?.hashCode()
            ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val notificationPrefs = getSharedPreferences("nexura_notification_settings", Context.MODE_PRIVATE)
        val soundEnabled = notificationPrefs.getBoolean("sound_enabled", true)
        val vibrationEnabled = notificationPrefs.getBoolean("vibration_enabled", true)
        val channelId = messageChannelId(soundEnabled, vibrationEnabled)
        val title = payload["title"]?.takeIf { it.isNotBlank() } ?: "Nexura"
        val senderName = payload["sender_name"]?.takeIf { it.isNotBlank() } ?: title
        val body = payload["body"]?.takeIf { it.isNotBlank() } ?: "Новое сообщение"
        val isGroup = payload["conversation_kind"] == "group"

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (conversationId != null) {
                action = "com.nexura.messenger.OPEN_CHAT"
                data = Uri.parse("nexura://chat/$conversationId")
            }
        }
        val contentIntent = PendingIntent.getActivity(
            this, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Never expose sender names or message previews on the lock screen.
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(com.nexura.messenger.R.drawable.ic_stat_nexura)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(
                NotificationCompat.MessagingStyle(senderName)
                    .addMessage(body, System.currentTimeMillis(), senderName)
                    .apply { if (isGroup) setConversationTitle(title) }
            )
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(com.nexura.messenger.R.drawable.ic_stat_nexura)
                    .setContentTitle("Nexura")
                    .setContentText("Новое сообщение")
                    .build()
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setDefaults((if (soundEnabled) NotificationCompat.DEFAULT_SOUND else 0) or (if (vibrationEnabled) NotificationCompat.DEFAULT_VIBRATE else 0))

        try {
            NotificationManagerCompat.from(this).notify(conversationId ?: "nexura", notificationId, builder.build())
        } catch (_: SecurityException) {
            // Notifications may be disabled by the user or OS.
        }
    }

    private fun ensureChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val settings = getSharedPreferences("nexura_notification_settings", Context.MODE_PRIVATE)
            val soundEnabled = settings.getBoolean("sound_enabled", true)
            val vibrationEnabled = settings.getBoolean("vibration_enabled", true)
            val channelId = messageChannelId(soundEnabled, vibrationEnabled)
            val channel = NotificationChannel(
                channelId,
                "Сообщения",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Новые личные и групповые сообщения Nexura"
                enableVibration(vibrationEnabled)
                vibrationPattern = if (vibrationEnabled) longArrayOf(0, 180, 90, 180) else null
                setSound(
                    if (soundEnabled) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else null,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
                )
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private fun messageChannelId(sound: Boolean, vibration: Boolean) =
            "nexura_messages_v2_s${if (sound) 1 else 0}_v${if (vibration) 1 else 0}"

        // Legacy call UI may still reference this helper; incoming-call notifications are disabled.
        @Suppress("UNUSED_PARAMETER")
        fun cancelIncomingCall(context: Context, conversationId: String) = Unit
    }
}
