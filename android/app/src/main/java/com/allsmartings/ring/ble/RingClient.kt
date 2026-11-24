package com.allsmartings.ring.ble

import com.allsmartings.ring.protocol.Battery
import com.allsmartings.ring.protocol.Colmi
import com.allsmartings.ring.protocol.DayTotals
import com.allsmartings.ring.protocol.RingProtocol
import com.allsmartings.ring.protocol.StepSlot
import com.allsmartings.ring.protocol.YuCheng
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime

data class HrSample(val time: Instant, val bpm: Int)

/** Passos do dia: Colmi devolve blocos de 15 min; YuCheng só o total acumulado. */
sealed interface StepsToday {
    data class Slots(val slots: List<StepSlot>) : StepsToday
    data class Total(val totals: DayTotals) : StepsToday

    val steps: Int
        get() = when (this) {
            is Slots -> slots.sumOf { it.steps }
            is Total -> totals.steps
        }
}

class HrResult(val samples: List<HrSample>, val errorCode: Int?)

/** Operações de alto nível, independentes do protocolo. Só leitura + acerto de relógio. */
class RingClient(private val conn: RingConnection) {
    val protocol: RingProtocol get() = conn.protocol

    suspend fun battery(): Battery? = when (protocol) {
        RingProtocol.COLMI -> Colmi.parseBattery(conn.exchange(Colmi.batteryRequest(), 1500))
        RingProtocol.YUCHENG -> YuCheng.parseBattery(conn.exchange(YuCheng.deviceInfoRequest(), 1500, settleMs = 250))
    }

    suspend fun syncClock(now: LocalDateTime = LocalDateTime.now()) {
        when (protocol) {
            RingProtocol.COLMI -> conn.exchange(Colmi.clockRequest(now), 800)
            RingProtocol.YUCHENG -> conn.exchange(YuCheng.clockRequest(now), 1500, settleMs = 250)
        }
    }

    suspend fun stepsToday(): StepsToday? = when (protocol) {
        RingProtocol.COLMI -> StepsToday.Slots(
            Colmi.parseSteps(conn.exchange(Colmi.stepsRequest(0), 5000, done = Colmi::stepsDone)),
        )
        RingProtocol.YUCHENG ->
            YuCheng.parseSteps(conn.exchange(YuCheng.stepsRequest(), 1500, settleMs = 250))?.let { StepsToday.Total(it) }
    }

    /**
     * FC ao vivo (só Colmi neste corte). O anel precisa estar no dedo; no carregador devolve erro.
     * Sempre envia o comando de parada no fim para liberar o LED.
     */
    suspend fun measureHeartRate(seconds: Int, onBpm: (Int) -> Unit = {}): HrResult {
        if (protocol != RingProtocol.COLMI) return HrResult(emptyList(), null)
        val samples = mutableListOf<HrSample>()
        var error: Int? = null
        val start = conn.receivedCount()
        var seen = 0
        try {
            conn.write(Colmi.hrStartRequest())
            val t0 = System.currentTimeMillis()
            var lastContinue = t0
            while (System.currentTimeMillis() - t0 < seconds * 1000L) {
                delay(500)
                if (System.currentTimeMillis() - lastContinue > 5000) {
                    conn.write(Colmi.hrContinueRequest())
                    lastContinue = System.currentTimeMillis()
                }
                val pkts = conn.receivedSince(start)
                for (p in pkts.drop(seen)) {
                    when (val ev = Colmi.parseHr(p)) {
                        is Colmi.HrEvent.Bpm -> {
                            samples += HrSample(Instant.now(), ev.bpm)
                            onBpm(ev.bpm)
                        }
                        is Colmi.HrEvent.Error -> error = ev.code
                        null -> Unit
                    }
                }
                seen = pkts.size
            }
        } finally {
            runCatching { conn.exchange(Colmi.hrStopRequest(), 800) }
        }
        return HrResult(samples, if (samples.isEmpty()) error else null)
    }
}
