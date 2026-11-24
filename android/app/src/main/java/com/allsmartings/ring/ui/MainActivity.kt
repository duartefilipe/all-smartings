package com.allsmartings.ring.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.allsmartings.ring.data.RingPrefs
import com.allsmartings.ring.health.HealthConnectWriter
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                RingScreen()
            }
        }
    }
}

private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingScreen(vm: RingViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    val hcLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { vm.refreshHealthConnect() }

    val bleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) vm.startScan() else vm.showError("Permissões de Bluetooth negadas")
    }

    LaunchedEffect(Unit) { vm.refreshHealthConnect() }

    Scaffold(topBar = { TopAppBar(title = { Text("Smart Ring Sync") }) }) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HealthConnectCard(s, onGrant = { hcLauncher.launch(HealthConnectWriter.PERMISSIONS) }) }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Anel", style = MaterialTheme.typography.titleMedium)
                        val sel = s.selected
                        if (sel == null) {
                            Text("Nenhum anel selecionado. Procure e toque em Conectar.")
                        } else {
                            Text("${sel.name} · ${sel.protocol?.label ?: "protocolo detectado ao conectar"}")
                        }
                        Text(
                            "Feche o app oficial (QRing ou app do R11M) antes: o anel só aceita uma conexão por vez.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { if (s.scanning) vm.stopScan() else bleLauncher.launch(blePermissions()) },
                            ) { Text(if (s.scanning) "Parar busca" else "Procurar anéis") }
                            if (sel != null) OutlinedButton(onClick = vm::forget) { Text("Esquecer") }
                        }
                        if (s.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }

            items(s.found, key = { it.address }) { ring ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ring.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${ring.protocolHint?.label ?: "protocolo a confirmar"} · ${ring.rssi} dBm",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { vm.connect(ring) }) { Text("Conectar") }
                    }
                }
            }

            item { ReadingCard(s, onSync = vm::syncNow) }
            item { ScheduleCard(s, vm) }
            s.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Spacer(Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun HealthConnectCard(s: UiState, onGrant: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Health Connect", style = MaterialTheme.typography.titleMedium)
            when (s.hcStatus) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    Text(if (s.hcGranted) "Permissões de escrita concedidas (passos e FC)." else "Falta conceder permissão de escrita.")
                    if (!s.hcGranted) Button(onClick = onGrant) { Text("Conceder permissões") }
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    Text("O Health Connect precisa ser instalado ou atualizado.")
                    Button(onClick = {
                        val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }) { Text("Abrir Play Store") }
                }
                else -> Text("Health Connect indisponível neste aparelho.")
            }
        }
    }
}

@Composable
private fun ReadingCard(s: UiState, onSync: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Última leitura", style = MaterialTheme.typography.titleMedium)
            val r = s.lastReading
            if (r == null) {
                Text("Ainda não sincronizado.")
            } else {
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.atEpochMs)))
                Text("Bateria: ${r.battery?.let { "$it%" + if (r.charging == true) " (carregando)" else "" } ?: "-"}")
                Text("Passos hoje: ${r.steps ?: "-"}")
                Text("FC: ${r.heartRate?.let { "$it bpm" } ?: "-"}")
                Text(
                    "Health Connect: ${r.stepsRecordsWritten} registro(s) de passos, ${r.hrSamplesWritten} amostra(s) de FC",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(r.message, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onSync, enabled = s.selected != null && !s.syncing) {
                Text(if (s.syncing) "Sincronizando..." else "Sincronizar agora")
            }
            Text(
                "Sincronizar agora também mede a FC por ${com.allsmartings.ring.sync.RingSync.HR_SECONDS} s (Colmi): use o anel no dedo.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (s.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ScheduleCard(s: UiState, vm: RingViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sincronização automática", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = s.periodicEnabled, onCheckedChange = vm::setPeriodic)
            }
            Text("Intervalo (intervalos maiores poupam a bateria do anel):", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RingPrefs.INTERVAL_OPTIONS.forEach { m ->
                    FilterChip(
                        selected = s.intervalMinutes == m,
                        onClick = { vm.setInterval(m) },
                        enabled = s.periodicEnabled,
                        label = { Text(if (m < 60) "${m}m" else "${m / 60}h") },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Medir FC na sincronização automática")
                    Text("Acende o LED a cada ciclo; gasta mais bateria.", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = s.hrOnPeriodic, onCheckedChange = vm::setHrOnPeriodic, enabled = s.periodicEnabled)
            }
        }
    }
}
