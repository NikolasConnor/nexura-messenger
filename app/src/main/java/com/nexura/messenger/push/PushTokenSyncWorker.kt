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
import com.google.firebase.messaging.FirebaseMessaging
import com.nexura.messenger.data.NexuraApi
import com.nexura.messenger.data.SessionStore
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Registers this installation's current FCM token with the currently signed-in
 * Nexura account. WorkManager persists retries across app/process restarts.
 */
class PushTokenSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = SessionStore(applicationContext)
        if (store.accessToken.isNullOrBlank() && store.refreshToken.isNullOrBlank()) {
            // The user is signed out. Sign-in schedules this work again.
            return Result.success()
        }

        return try {
            val api = NexuraApi(store)
            api.restoreSession()
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isBlank()) return Result.retry()
            api.registerPushToken(token)
            Result.success()
        } catch (_: Exception) {
            if (store.accessToken.isNullOrBlank() && store.refreshToken.isNullOrBlank()) {
                Result.success()
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "nexura-push-token-registration"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<PushTokenSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
