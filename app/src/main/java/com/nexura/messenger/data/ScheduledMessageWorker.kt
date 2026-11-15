package com.nexura.messenger.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException

/**
 * Delivers locally scheduled text messages after the app UI is gone.
 * The queue itself is stored in EncryptedSharedPreferences; messages use the
 * same cloud/E2EE send paths as immediate messages.
 */
class ScheduledMessageWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    companion object {
        const val KEY_SCHEDULED_ID = "scheduled_message_id"
    }

    override suspend fun doWork(): Result {
        val store = SessionStore(applicationContext)
        val scheduledId = inputData.getString(KEY_SCHEDULED_ID) ?: return Result.failure()
        val item = store.scheduledTexts().firstOrNull { it.id == scheduledId } ?: return Result.success()
        if (item.sendAtMillis > System.currentTimeMillis()) return Result.retry()
        if (store.userId.isNullOrBlank() || store.accessToken.isNullOrBlank()) return Result.retry()

        return try {
            val api = NexuraApi(store)
            val chat = api.chats().firstOrNull { it.id == item.chatId }
                ?: run {
                    store.removeScheduledText(item.id)
                    return Result.failure()
                }
            when (chat.chatMode) {
                "cloud" -> api.sendCloudMessage(chat.id, item.id, item.text, item.replyToId)
                "secret" -> {
                    val client = E2eeClient(applicationContext, store.userId!!)
                    if (chat.kind == "direct") {
                        api.sendEncryptedDirectMessage(chat.id, client, item.id, item.text, item.replyToId)
                    } else {
                        api.sendEncryptedGroupMessage(
                            chat.id, client, item.id, item.text, replyToId = item.replyToId
                        )
                    }
                }
                else -> throw IOException("Тип чата пока не поддерживает отложенную отправку")
            }
            store.removeScheduledText(item.id)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
