package com.allsmartings.ring.protocol

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Protocolo Colmi/QRing (serviço 6e40fff0), usado pelo R02/R03/R06.
 *
 * Pacote fixo de 16 bytes: byte0 = comando, bytes 1..14 = dados, byte15 = soma(0..14) & 0xFF.
 * Escrita sem resposta em 6e400002; respostas por notificação em 6e400003.
 * byte0 com o bit 0x80 ligado indica que o anel recusou o comando.
 *
 * Porte de `src/smartring/protocols/colmi.py` limitado a bateria, passos, FC ao vivo e relógio.
 * Fonte: engenharia reversa da comunidade (tahnok/colmi_r02_client, colmi.puxtril.com).
 */
object Colmi {
    const val CMD_CLOCK = 0x01
    const val CMD_BATTERY = 0x03
    const val CMD_STEPS = 0x43
    const val CMD_HR_LIVE = 0x69
    const val CMD_HR_STOP = 0x6A

    fun build(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val pkt = ByteArray(16)
        pkt[0] = cmd.toByte()
        payload.copyInto(pkt, 1, 0, minOf(payload.size, 14))
        var sum = 0
        for (i in 0 until 15) sum += pkt.u(i)
        pkt[15] = (sum and 0xFF).toByte()
        return pkt
    }

    fun ofCmd(resps: List<ByteArray>, cmd: Int): List<ByteArray> =
        resps.filter { it.size == 16 && (it.u(0) and 0x7F) == cmd }

    fun isError(p: ByteArray): Boolean = (p.u(0) and 0x80) != 0
    fun fromBcd(b: Int): Int = (b shr 4) * 10 + (b and 0x0F)
    fun toBcd(v: Int): Int = ((v / 10) shl 4) or (v % 10)

    fun batteryRequest(): ByteArray = build(CMD_BATTERY)

    /** Resposta: 03 <nível %> <carregando 0/1>. */
    fun parseBattery(resps: List<ByteArray>): Battery? =
        ofCmd(resps, CMD_BATTERY).lastOrNull { !isError(it) }?.let { Battery(it.u(1), it.u(2) == 1) }

    /** 0x43 <dia> 0F 00 5F 01 (dia 0 = hoje, 1 = ontem...). */
    fun stepsRequest(dayOffset: Int = 0): ByteArray =
        build(CMD_STEPS, byteArrayOf(dayOffset.toByte(), 0x0F, 0x00, 0x5F, 0x01))

    /** Fim da resposta de passos: FF = sem dados, ou último pacote (índice >= total - 1). */
    fun stepsDone(resps: List<ByteArray>): Boolean = ofCmd(resps, CMD_STEPS).any { p ->
        p.u(1) == 0xFF || (p.u(1) != 0xF0 && p.u(6) > 0 && p.u(5) >= p.u(6) - 1)
    }

    /**
     * Respostas: F0 = cabeçalho (byte3 == 1: calorias em décimos), FF = sem dados;
     * dados = ano/mês/dia BCD, slot de 15 min, índice, total, calorias LE, passos LE, distância LE (m).
     */
    fun parseSteps(resps: List<ByteArray>): List<StepSlot> {
        var newCal = false
        val slots = mutableListOf<StepSlot>()
        for (p in ofCmd(resps, CMD_STEPS)) {
            if (p.u(1) == 0xFF) break
            if (p.u(1) == 0xF0) {
                newCal = p.u(3) == 1
                continue
            }
            val date = runCatching {
                LocalDate.of(2000 + fromBcd(p.u(1)), fromBcd(p.u(2)), fromBcd(p.u(3)))
            }.getOrNull() ?: continue
            val idx = p.u(4)
            if (idx > 95) continue
            val cal = p.le16(7)
            slots += StepSlot(
                start = date.atStartOfDay().plusMinutes(idx * 15L),
                minutes = 15,
                steps = p.le16(9),
                distanceM = p.le16(11),
                calories = if (newCal) cal / 10.0 else cal.toDouble(),
            )
        }
        return slots
    }

    /** 0x01: ano/mês/dia/hora/min/seg em BCD + 0x01 (idioma). */
    fun clockRequest(now: LocalDateTime): ByteArray = build(
        CMD_CLOCK,
        byteArrayOf(
            toBcd(now.year % 100).toByte(), toBcd(now.monthValue).toByte(), toBcd(now.dayOfMonth).toByte(),
            toBcd(now.hour).toByte(), toBcd(now.minute).toByte(), toBcd(now.second).toByte(), 0x01,
        ),
    )

    /** 69 01 01 inicia; 69 01 03 mantém (a cada ~5 s); 6A 01 00 00 para e libera o LED. */
    fun hrStartRequest(): ByteArray = build(CMD_HR_LIVE, byteArrayOf(0x01, 0x01))
    fun hrContinueRequest(): ByteArray = build(CMD_HR_LIVE, byteArrayOf(0x01, 0x03))
    fun hrStopRequest(): ByteArray = build(CMD_HR_STOP, byteArrayOf(0x01, 0x00, 0x00))

    sealed interface HrEvent {
        data class Bpm(val bpm: Int) : HrEvent

        /** Código != 0: em geral fora do dedo ou no carregador. */
        data class Error(val code: Int) : HrEvent
    }

    /** Resposta: 69 <tipo> <erro> <bpm>. bpm 0 = ainda medindo. */
    fun parseHr(p: ByteArray): HrEvent? {
        if (p.size != 16 || (p.u(0) and 0x7F) != CMD_HR_LIVE) return null
        return when {
            p.u(2) != 0 -> HrEvent.Error(p.u(2))
            p.u(3) > 0 -> HrEvent.Bpm(p.u(3))
            else -> null
        }
    }
}
