package dev.cipherkey.mobile.data

import android.content.Context
import androidx.work.*
import dev.cipherkey.core.sync.SyncEngine
import java.util.concurrent.TimeUnit

/** Programación de WorkManager: ciclo periódico con red + reintento en cuanto vuelva la conexión tras un fallo. */
object SyncScheduler {
    private const val PERIODIC = "cipherkey-sync-periodic"
    private const val RETRY = "cipherkey-sync-retry"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun retryWhenConnected(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(RETRY, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(ctx).cancelUniqueWork(RETRY)
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as dev.cipherkey.mobile.CipherKeyApp
        return try {
            if (app.session.isUnlocked()) {
                app.session.syncNow()               // sincronización completa (la bóveda está abierta en este proceso)
            } else {
                runLocked(app)                      // bóveda bloqueada: solo sobres cifrados, sin descifrar nada
            }
            Result.success()
        } catch (e: java.io.IOException) {
            Result.retry()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun runLocked(app: dev.cipherkey.mobile.CipherKeyApp) {
        val store = BackgroundStore(app.session.files)
        val client = store.client() ?: return
        val spool = store.loadSpool() ?: return
        spool.run(client)
        store.saveSpool(spool)
    }
}
