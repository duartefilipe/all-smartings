package com.allsmartings.ring.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.allsmartings.ring.protocol.RingProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class ScannedRing(
    val address: String,
    val name: String,
    val rssi: Int,
    /** Palpite pelo anúncio; a detecção definitiva é feita no GATT ao conectar. */
    val protocolHint: RingProtocol?,
)

/**
 * Varredura BLE. Mostra aparelhos que anunciam um serviço conhecido ou cujo nome parece de anel
 * (alguns anéis não anunciam o UUID de serviço).
 */
@SuppressLint("MissingPermission")
class RingScanner(private val context: Context) {

    fun scan(): Flow<ScannedRing> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            close(IllegalStateException("Bluetooth desligado ou indisponível"))
            return@callbackFlow
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                toRing(result)?.let { trySend(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r -> toRing(r)?.let { trySend(it) } }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Falha na varredura BLE ($errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    private fun toRing(r: ScanResult): ScannedRing? {
        val uuids = r.scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
        val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: ""
        val hint = RingProtocol.detect(uuids)
        if (hint == null && !looksLikeRing(name)) return null
        return ScannedRing(r.device.address, name.ifBlank { "(sem nome)" }, r.rssi, hint)
    }

    companion object {
        private val NAME_PATTERN = Regex("^(R\\d{2}|COLMI|QRING|SMART ?RING)", RegexOption.IGNORE_CASE)
        fun looksLikeRing(name: String) = NAME_PATTERN.containsMatchIn(name.trim())
    }
}
