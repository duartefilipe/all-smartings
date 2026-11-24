package com.allsmartings.ring.data

import android.content.Context
import androidx.core.content.edit
import com.allsmartings.ring.protocol.RingProtocol

data class SavedRing(val address: String, val name: String, val protocol: RingProtocol?)

data class LastReading(
    val atEpochMs: Long,
    val battery: Int?,
    val charging: Boolean?,
    val steps: Int?,
    val heartRate: Int?,
    val stepsRecordsWritten: Int,
    val hrSamplesWritten: Int,
    val message: String,
)

/** Configuração local (SharedPreferences). Fica só no aparelho. */
class RingPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("ring_prefs", Context.MODE_PRIVATE)

    var ring: SavedRing?
        get() {
            val address = sp.getString(K_ADDRESS, null) ?: return null
            val proto = sp.getString(K_PROTOCOL, null)?.let { runCatching { RingProtocol.valueOf(it) }.getOrNull() }
            return SavedRing(address, sp.getString(K_NAME, "") ?: "", proto)
        }
        set(value) = sp.edit {
            if (value == null) {
                remove(K_ADDRESS); remove(K_NAME); remove(K_PROTOCOL)
            } else {
                putString(K_ADDRESS, value.address)
                putString(K_NAME, value.name)
                putString(K_PROTOCOL, value.protocol?.name)
            }
        }

    /** Intervalo da sincronização periódica (mínimo do WorkManager: 15 min). */
    var intervalMinutes: Long
        get() = sp.getLong(K_INTERVAL, DEFAULT_INTERVAL)
        set(value) = sp.edit { putLong(K_INTERVAL, value.coerceAtLeast(15)) }

    var periodicEnabled: Boolean
        get() = sp.getBoolean(K_PERIODIC, true)
        set(value) = sp.edit { putBoolean(K_PERIODIC, value) }

    /** Medir FC na sincronização periódica (acende o LED; desligado por padrão para poupar bateria). */
    var hrOnPeriodicSync: Boolean
        get() = sp.getBoolean(K_HR_PERIODIC, false)
        set(value) = sp.edit { putBoolean(K_HR_PERIODIC, value) }

    var lastReading: LastReading?
        get() {
            if (!sp.contains(K_LR_AT)) return null
            fun intOrNull(k: String) = if (sp.contains(k)) sp.getInt(k, 0) else null
            return LastReading(
                atEpochMs = sp.getLong(K_LR_AT, 0),
                battery = intOrNull(K_LR_BAT),
                charging = if (sp.contains(K_LR_CHG)) sp.getBoolean(K_LR_CHG, false) else null,
                steps = intOrNull(K_LR_STEPS),
                heartRate = intOrNull(K_LR_HR),
                stepsRecordsWritten = sp.getInt(K_LR_SREC, 0),
                hrSamplesWritten = sp.getInt(K_LR_HREC, 0),
                message = sp.getString(K_LR_MSG, "") ?: "",
            )
        }
        set(v) = sp.edit {
            if (v == null) {
                listOf(K_LR_AT, K_LR_BAT, K_LR_CHG, K_LR_STEPS, K_LR_HR, K_LR_SREC, K_LR_HREC, K_LR_MSG).forEach(::remove)
                return@edit
            }
            putLong(K_LR_AT, v.atEpochMs)
            v.battery?.let { putInt(K_LR_BAT, it) } ?: remove(K_LR_BAT)
            v.charging?.let { putBoolean(K_LR_CHG, it) } ?: remove(K_LR_CHG)
            v.steps?.let { putInt(K_LR_STEPS, it) } ?: remove(K_LR_STEPS)
            v.heartRate?.let { putInt(K_LR_HR, it) } ?: remove(K_LR_HR)
            putInt(K_LR_SREC, v.stepsRecordsWritten)
            putInt(K_LR_HREC, v.hrSamplesWritten)
            putString(K_LR_MSG, v.message)
        }

    companion object {
        const val DEFAULT_INTERVAL = 60L
        val INTERVAL_OPTIONS = listOf(15L, 30L, 60L, 120L, 240L)

        private const val K_ADDRESS = "ring_address"
        private const val K_NAME = "ring_name"
        private const val K_PROTOCOL = "ring_protocol"
        private const val K_INTERVAL = "sync_interval_min"
        private const val K_PERIODIC = "sync_periodic"
        private const val K_HR_PERIODIC = "sync_hr_periodic"
        private const val K_LR_AT = "lr_at"
        private const val K_LR_BAT = "lr_battery"
        private const val K_LR_CHG = "lr_charging"
        private const val K_LR_STEPS = "lr_steps"
        private const val K_LR_HR = "lr_hr"
        private const val K_LR_SREC = "lr_steps_records"
        private const val K_LR_HREC = "lr_hr_samples"
        private const val K_LR_MSG = "lr_message"
    }
}
