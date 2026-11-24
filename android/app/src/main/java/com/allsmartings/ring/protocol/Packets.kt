package com.allsmartings.ring.protocol

import java.time.LocalDateTime

internal fun Byte.u(): Int = toInt() and 0xFF
internal fun ByteArray.u(i: Int): Int = this[i].u()
internal fun ByteArray.le16(i: Int): Int = u(i) or (u(i + 1) shl 8)

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

/** Bloco de passos de um intervalo (Colmi: 15 min). Horário local do anel. */
data class StepSlot(
    val start: LocalDateTime,
    val minutes: Int,
    val steps: Int,
    val distanceM: Int,
    val calories: Double,
)

data class Battery(val percent: Int, val charging: Boolean?)

/** Totais do dia (YuCheng só expõe o acumulado do dia). */
data class DayTotals(val steps: Int, val distanceM: Int, val calories: Int)
