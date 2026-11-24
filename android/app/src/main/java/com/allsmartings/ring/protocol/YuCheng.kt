package com.allsmartings.ring.protocol

import java.time.LocalDateTime

/**
 * Protocolo YuCheng (serviço be940000), usado pelo R11M.
 *
 * Pacote: [tipo, id, tamanho LE (2 bytes), payload..., CRC-16 LE].
 * Escrita com resposta em be940001; respostas por indicação em be940001/be940003.
 * Porte de `src/smartring/protocols/yucheng.py` limitado a bateria, passos do dia e relógio.
 */
object YuCheng {

    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = ((crc ushr 8) and 0xFF) or ((crc shl 8) and 0xFFFF)
            crc = crc xor b.u()
            crc = crc xor ((crc and 0xFF) ushr 4)
            crc = crc xor ((crc shl 12) and 0xFFFF)
            crc = crc xor (((crc and 0xFF) shl 5) and 0xFFFF)
        }
        return crc
    }

    fun build(type: Int, id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val total = 4 + payload.size + 2
        val body = byteArrayOf(
            type.toByte(), id.toByte(), (total and 0xFF).toByte(), ((total shr 8) and 0xFF).toByte(),
        ) + payload
        val crc = crc16(body)
        return body + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    /** GetDeviceInfo "GC" (0x02, 0x00): modelo, firmware e bateria (byte 9). */
    fun deviceInfoRequest(): ByteArray = build(0x02, 0x00, "GC".toByteArray(Charsets.US_ASCII))

    /** GetNowStep (0x02, 0x0C): passos, distância e calorias acumulados do dia. */
    fun stepsRequest(): ByteArray = build(0x02, 0x0C)

    /** Sincronizar RTC (0x01, 0x00): ano LE, mês, dia, hora, min, seg, dia da semana (segunda = 0). */
    fun clockRequest(now: LocalDateTime): ByteArray {
        val week = (now.dayOfWeek.value - 1) % 7
        val payload = byteArrayOf(
            (now.year and 0xFF).toByte(), ((now.year shr 8) and 0xFF).toByte(),
            now.monthValue.toByte(), now.dayOfMonth.toByte(),
            now.hour.toByte(), now.minute.toByte(), now.second.toByte(), week.toByte(),
        )
        return build(0x01, 0x00, payload)
    }

    fun isDeviceInfo(r: ByteArray) = r.size >= 10 && r.u(0) == 0x02 && r.u(1) == 0x00
    fun isSteps(r: ByteArray) = r.size >= 12 && r.u(0) == 0x02 && r.u(1) == 0x0C

    fun parseBattery(resps: List<ByteArray>): Battery? =
        resps.lastOrNull(::isDeviceInfo)?.let { r ->
            // byte 8 = estado (0 normal, 1 carregando, 2 cheia, 3 subtensão), quando presente
            val charging = if (r.size >= 14) r.u(8) == 1 || r.u(8) == 2 else null
            Battery(r.u(9), charging)
        }

    fun parseSteps(resps: List<ByteArray>): DayTotals? =
        resps.lastOrNull(::isSteps)?.let { r ->
            DayTotals(steps = r.le16(9), distanceM = r.le16(4), calories = r.le16(7))
        }
}
