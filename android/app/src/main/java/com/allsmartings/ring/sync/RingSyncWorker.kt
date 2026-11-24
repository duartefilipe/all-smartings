package com.allsmartings.ring.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.allsmartings.ring.ble.RingBleException
import com.allsmartings.ring.data.RingPrefs
import java.util.concurrent.TimeUnit

class RingSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = RingPrefs(applicationContext)
        if (prefs.ring == null) return Result.success()
        val measureHr = inputData.getBoolean(KEY_MEASURE_HR, prefs.hrOnPeriodicSync)
        return try {
            val r = RingSync.run(applicationContext, measureHr)
            Result.success(workDataOf(KEY_MESSAGE to r.message))
        } catch (e: RingBleException) {
            Log.w(TAG, "Falha BLE: ${e.message}")
            saveError(prefs, e.message)
            // Anel longe ou ocupado: tenta de novo com backoff, no máximo algumas vezes
            if (runAttemptCount < 2) Result.retry() else Result.failure(workDataOf(KEY_MESSAGE to e.message))
        } catch (e: Exception) {
            Log.w(TAG, "Falha na sincronização", e)
            saveError(prefs, e.message)
            Result.failure(workDataOf(KEY_MESSAGE to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun saveError(prefs: RingPrefs, msg: String?) {
        val prev = prefs.lastReading
        val text = "Erro: ${msg ?: "desconhecido"}"
        prefs.lastReading = prev?.copy(message = text)
            ?: com.allsmartings.ring.data.LastReading(System.currentTimeMillis(), null, null, null, null, 0, 0, text)
    }

    companion object {
        private const val TAG = "RingSyncWorker"
        const val KEY_MEASURE_HR = "measure_hr"
        const val KEY_MESSAGE = "message"
        const val PERIODIC_NAME = "ring-sync-periodic"
        const val NOW_NAME = "ring-sync-now"

        /** (Re)agenda a sincronização periódica com o intervalo salvo, ou cancela se desligada. */
        fun schedule(context: Context) {
            val prefs = RingPrefs(context)
            val wm = WorkManager.getInstance(context)
            if (!prefs.periodicEnabled || prefs.ring == null) {
                wm.cancelUniqueWork(PERIODIC_NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<RingSyncWorker>(prefs.intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun syncNow(context: Context, measureHr: Boolean) {
            val req = OneTimeWorkRequestBuilder<RingSyncWorker>()
                .setInputData(workDataOf(KEY_MEASURE_HR to measureHr))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.KEEP, req)
        }
    }
}
