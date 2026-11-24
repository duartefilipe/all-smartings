package com.allsmartings.ring.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.allsmartings.ring.ble.RingClient
import com.allsmartings.ring.ble.RingConnection
import com.allsmartings.ring.ble.StepsToday
import com.allsmartings.ring.data.LastReading
import com.allsmartings.ring.data.RingPrefs
import com.allsmartings.ring.health.HealthConnectWriter

/**
 * Uma sincronização completa: conecta, acerta o relógio, lê bateria e passos, mede FC (opcional),
 * desconecta e grava no Health Connect. Usada pelo Worker periódico e pelo "Sincronizar agora".
 */
object RingSync {

    const val HR_SECONDS = 20

    fun hasBluetoothPermission(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            return true // até o Android 11 a conexão GATT não exige permissão em tempo de execução
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun run(context: Context, measureHr: Boolean): LastReading {
        val prefs = RingPrefs(context)
        val ring = prefs.ring ?: throw IllegalStateException("Nenhum anel selecionado")
        if (!hasBluetoothPermission(context)) throw IllegalStateException("Permissão BLUETOOTH_CONNECT não concedida")

        val hc = HealthConnectWriter(context)
        val hcReady = HealthConnectWriter.sdkStatus(context) == androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE &&
            hc.hasAllPermissions()

        val conn = RingConnection.open(context, ring.address)
        val client = RingClient(conn)
        val detected = ring.copy(protocol = conn.protocol)
        if (ring.protocol != conn.protocol) prefs.ring = detected

        try {
            client.syncClock()
            val battery = client.battery()
            val steps = client.stepsToday()
            val hr = if (measureHr) client.measureHeartRate(HR_SECONDS) else null
            conn.close()

            var stepRecs = 0
            var hrSamples = 0
            val notes = mutableListOf<String>()
            if (hcReady) {
                when (steps) {
                    is StepsToday.Slots -> stepRecs = hc.writeStepSlots(detected, steps.slots)
                    is StepsToday.Total -> stepRecs = hc.writeDayTotal(detected, steps.totals)
                    null -> notes += "sem resposta de passos"
                }
                if (hr != null) hrSamples = hc.writeHeartRate(detected, hr.samples)
            } else {
                notes += "Health Connect sem permissão: nada gravado"
            }
            if (hr != null && hr.samples.isEmpty()) {
                notes += if (hr.errorCode != null) "FC: erro ${hr.errorCode} (anel fora do dedo ou no carregador?)" else "FC: sem leitura"
            }
            if (measureHr && conn.protocol != com.allsmartings.ring.protocol.RingProtocol.COLMI) {
                notes += "FC ao vivo ainda não implementada para ${conn.protocol.label}"
            }

            val reading = LastReading(
                atEpochMs = System.currentTimeMillis(),
                battery = battery?.percent,
                charging = battery?.charging,
                steps = steps?.steps,
                heartRate = hr?.samples?.lastOrNull()?.bpm,
                stepsRecordsWritten = stepRecs,
                hrSamplesWritten = hrSamples,
                message = (listOf("OK (${conn.protocol.label})") + notes).joinToString("; "),
            )
            prefs.lastReading = reading
            return reading
        } finally {
            conn.close()
        }
    }
}
