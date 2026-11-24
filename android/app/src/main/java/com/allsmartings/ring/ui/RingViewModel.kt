package com.allsmartings.ring.ui

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.allsmartings.ring.ble.RingScanner
import com.allsmartings.ring.ble.ScannedRing
import com.allsmartings.ring.data.LastReading
import com.allsmartings.ring.data.RingPrefs
import com.allsmartings.ring.data.SavedRing
import com.allsmartings.ring.health.HealthConnectWriter
import com.allsmartings.ring.sync.RingSyncWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class UiState(
    val hcStatus: Int = HealthConnectClient.SDK_UNAVAILABLE,
    val hcGranted: Boolean = false,
    val scanning: Boolean = false,
    val found: List<ScannedRing> = emptyList(),
    val selected: SavedRing? = null,
    val syncing: Boolean = false,
    val lastReading: LastReading? = null,
    val intervalMinutes: Long = RingPrefs.DEFAULT_INTERVAL,
    val periodicEnabled: Boolean = true,
    val hrOnPeriodic: Boolean = false,
    val error: String? = null,
)

class RingViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = RingPrefs(app)
    private val scanner = RingScanner(app)
    private val hc = HealthConnectWriter(app)
    private var scanJob: Job? = null

    private val _state = MutableStateFlow(
        UiState(
            selected = prefs.ring,
            lastReading = prefs.lastReading,
            intervalMinutes = prefs.intervalMinutes,
            periodicEnabled = prefs.periodicEnabled,
            hrOnPeriodic = prefs.hrOnPeriodicSync,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshHealthConnect()
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(RingSyncWorker.NOW_NAME).collect { infos ->
                val running = infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                _state.update { it.copy(syncing = running, lastReading = prefs.lastReading, selected = prefs.ring) }
            }
        }
    }

    fun refreshHealthConnect() {
        val status = HealthConnectWriter.sdkStatus(getApplication())
        _state.update { it.copy(hcStatus = status) }
        if (status == HealthConnectClient.SDK_AVAILABLE) {
            viewModelScope.launch {
                val granted = runCatching { hc.hasAllPermissions() }.getOrDefault(false)
                _state.update { it.copy(hcGranted = granted) }
            }
        }
    }

    fun startScan() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = true, found = emptyList(), error = null) }
        scanJob = viewModelScope.launch {
            withTimeoutOrNull(SCAN_MS) {
                scanner.scan()
                    .catch { e -> _state.update { it.copy(error = e.message) } }
                    .collect { ring ->
                        _state.update { s ->
                            val others = s.found.filterNot { it.address == ring.address }
                            s.copy(found = (others + ring).sortedByDescending { it.rssi })
                        }
                    }
            }
            _state.update { it.copy(scanning = false) }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = false) }
    }

    /** Seleciona o anel e faz a primeira conexão/leitura (detecta o protocolo pelo GATT). */
    fun connect(ring: ScannedRing) {
        stopScan()
        val saved = SavedRing(ring.address, ring.name, ring.protocolHint)
        prefs.ring = saved
        _state.update { it.copy(selected = saved) }
        RingSyncWorker.schedule(getApplication())
        syncNow()
    }

    fun forget() {
        prefs.ring = null
        prefs.lastReading = null
        RingSyncWorker.schedule(getApplication())
        _state.update { it.copy(selected = null, lastReading = null) }
    }

    fun syncNow() {
        _state.update { it.copy(error = null) }
        RingSyncWorker.syncNow(getApplication(), measureHr = true)
    }

    fun setInterval(minutes: Long) {
        prefs.intervalMinutes = minutes
        _state.update { it.copy(intervalMinutes = prefs.intervalMinutes) }
        RingSyncWorker.schedule(getApplication())
    }

    fun setPeriodic(enabled: Boolean) {
        prefs.periodicEnabled = enabled
        _state.update { it.copy(periodicEnabled = enabled) }
        RingSyncWorker.schedule(getApplication())
    }

    fun setHrOnPeriodic(enabled: Boolean) {
        prefs.hrOnPeriodicSync = enabled
        _state.update { it.copy(hrOnPeriodic = enabled) }
    }

    fun showError(msg: String) = _state.update { it.copy(error = msg) }

    companion object {
        private const val SCAN_MS = 15_000L
    }
}
