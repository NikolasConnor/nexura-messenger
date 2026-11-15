package com.nexura.messenger.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nexura.messenger.data.NexuraApi
import com.nexura.messenger.data.SessionStore
import java.util.concurrent.TimeUnit

/**
 * Durable second-stage delivery: once a message is saved, WorkManager keeps
 * trying to hand the push to FCM even if Nexura is backgrounded or process-dead.
 */
class PushDeliveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val conversationId = inputData.getString(KEY_CONVERSATION_ID)
            ?: return Result.failure()
        val store = SessionStore(applicationContext)
        if (store.accessToken.isNullOrBlank() && store.refreshToken.isNullOrBlank()) {
            return Result.success()
        }
        return try {
            val api = NexuraApi(store, applicationContext)
            api.restoreSession()
            val result = api.dispatchChatPush(conversationId)
            val failed = result.optInt("failed", 0)
            if (failed > 0) Result.retry() else Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_CONVERSATION_ID = "conversation_id"

        fun enqueue(context: Context, conversationId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<PushDeliveryWorker>()
                .setInputData(workDataOf(KEY_CONVERSATION_ID to conversationId))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("nexura-push-delivery")
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueue(request)
        }
    }
}
