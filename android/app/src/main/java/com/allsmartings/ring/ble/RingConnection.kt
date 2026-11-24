package com.allsmartings.ring.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.allsmartings.ring.protocol.RingProtocol
import com.allsmartings.ring.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class RingBleException(message: String) : Exception(message)

/**
 * Conexão GATT com um anel, em estilo coroutine. Uma operação GATT por vez (o Android não
 * enfileira). Notificações/indicações recebidas ficam em [received] para [exchange].
 *
 * Quem chama precisa já ter BLUETOOTH_CONNECT (Android 12+).
 */
@SuppressLint("MissingPermission")
class RingConnection private constructor(private val context: Context) {

    lateinit var protocol: RingProtocol
        private set
    var serviceUuids: List<String> = emptyList()
        private set

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val opMutex = Mutex()
    private var pendingOp: CompletableDeferred<Int>? = null
    private val connected = CompletableDeferred<Unit>()
    private val discovered = CompletableDeferred<Int>()
    @Volatile private var disconnected = false

    private val received = mutableListOf<ByteArray>()
    @Volatile private var lastRxAt = 0L

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnected = true
                val err = RingBleException("Desconectado (status GATT $status)")
                connected.completeExceptionally(err)
                discovered.completeExceptionally(err)
                pendingOp?.completeExceptionally(err)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            discovered.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status)
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onRx(value)
        }

        // Android 12 e anteriores
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                c.value?.let { onRx(it.copyOf()) }
            }
        }
    }

    private fun onRx(value: ByteArray) {
        synchronized(received) { received += value }
        lastRxAt = System.currentTimeMillis()
        Log.d(TAG, "<< ${value.toHex()}")
    }

    private suspend fun open(address: String, timeoutMs: Long) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: throw RingBleException("Bluetooth indisponível")
        if (!adapter.isEnabled) throw RingBleException("Bluetooth desligado")
        val device = adapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw RingBleException("connectGatt falhou")
        try {
            withTimeout(timeoutMs) { connected.await() }
            val g = gatt!!
            if (!g.discoverServices()) throw RingBleException("discoverServices falhou")
            val st = withTimeout(timeoutMs) { discovered.await() }
            if (st != BluetoothGatt.GATT_SUCCESS) throw RingBleException("Descoberta de serviços falhou ($st)")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw RingBleException("Tempo esgotado ao conectar. O anel está por perto e fora do app oficial?")
        }

        val g = gatt!!
        serviceUuids = g.services.map { it.uuid.toString() }
        protocol = RingProtocol.detect(serviceUuids)
            ?: run { close(); throw RingBleException("Protocolo não reconhecido. Serviços: ${serviceUuids.joinToString()}") }

        val chars = g.services.flatMap { it.characteristics }
        writeChar = chars.firstOrNull { RingProtocol.matches(it.uuid.toString(), protocol.writePrefix) }
            ?: run { close(); throw RingBleException("Característica de escrita ${protocol.writePrefix} não encontrada") }
        for (prefix in protocol.notifyPrefixes) {
            chars.firstOrNull { RingProtocol.matches(it.uuid.toString(), prefix) }?.let { enableNotify(it) }
        }
    }

    private suspend fun enableNotify(c: BluetoothGattCharacteristic) {
        val g = gatt ?: return
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: return
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        runOp {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
    }

    /** Executa uma operação GATT e espera o callback correspondente. */
    private suspend fun runOp(timeoutMs: Long = 3000, requireCallback: Boolean = true, start: () -> Boolean) {
        opMutex.withLock {
            if (disconnected) throw RingBleException("Anel desconectado")
            val op = CompletableDeferred<Int>()
            pendingOp = op
            try {
                if (!start()) throw RingBleException("Operação GATT recusada pela pilha Bluetooth")
                val status = withTimeoutOrNull(timeoutMs) { op.await() }
                if (status == null && requireCallback) throw RingBleException("Sem confirmação da operação GATT")
                if (status != null && status != BluetoothGatt.GATT_SUCCESS) {
                    throw RingBleException("Operação GATT falhou ($status)")
                }
            } finally {
                pendingOp = null
            }
        }
    }

    suspend fun write(packet: ByteArray) {
        val g = gatt ?: throw RingBleException("Sem conexão")
        val c = writeChar ?: throw RingBleException("Sem característica de escrita")
        val type = if (protocol.writeWithResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        Log.d(TAG, ">> ${packet.toHex()}")
        runOp(requireCallback = protocol.writeWithResponse) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, packet, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = packet
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    /** Pacotes recebidos a partir de [from] (índice em [received]). */
    fun receivedSince(from: Int): List<ByteArray> = synchronized(received) { received.drop(from) }

    fun receivedCount(): Int = synchronized(received) { received.size }

    /**
     * Envia [packet] e coleta as respostas até [done] ser verdadeiro, até [timeoutMs], ou até
     * [settleMs] sem novos pacotes depois do primeiro (equivalente a `wait_responses` do Python).
     */
    suspend fun exchange(
        packet: ByteArray,
        timeoutMs: Long = 1500,
        settleMs: Long = 0,
        done: ((List<ByteArray>) -> Boolean)? = null,
    ): List<ByteArray> {
        val start = receivedCount()
        write(packet)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !disconnected) {
            delay(50)
            val got = receivedSince(start)
            if (done != null && done(got)) return got
            if (settleMs > 0 && got.isNotEmpty() && System.currentTimeMillis() - lastRxAt >= settleMs) return got
        }
        return receivedSince(start)
    }

    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        disconnected = true
    }

    companion object {
        private const val TAG = "RingConnection"
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        suspend fun open(context: Context, address: String, timeoutMs: Long = 15_000): RingConnection {
            val conn = RingConnection(context.applicationContext)
            try {
                conn.open(address, timeoutMs)
            } catch (e: Throwable) {
                conn.close()
                throw e
            }
            return conn
        }
    }
}
