package com.allsmartings.ring.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import com.allsmartings.ring.ble.HrSample
import com.allsmartings.ring.data.SavedRing
import com.allsmartings.ring.protocol.DayTotals
import com.allsmartings.ring.protocol.RingProtocol
import com.allsmartings.ring.protocol.StepSlot
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Grava passos e FC no Health Connect, de onde Google Fit, Samsung Health etc. leem.
 *
 * Deduplicação: cada registro tem `clientRecordId` estável (anel + intervalo) e
 * `clientRecordVersion` = contagem, então resincronizar só atualiza o que cresceu.
 */
class HealthConnectWriter(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasAllPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    /** Blocos de 15 min do Colmi. O bloco atual é cortado em "agora" e atualizado depois. */
    suspend fun writeStepSlots(ring: SavedRing, slots: List<StepSlot>, now: Instant = Instant.now()): Int {
        val zone = ZoneId.systemDefault()
        val key = deviceKey(ring.address)
        val records = slots.filter { it.steps > 0 }.mapNotNull { s ->
            val start = s.start.atZone(zone).toInstant()
            val end = minOf(start.plus(s.minutes.toLong(), ChronoUnit.MINUTES), now)
            if (!end.isAfter(start)) return@mapNotNull null
            StepsRecord(
                startTime = start,
                startZoneOffset = zone.rules.getOffset(start),
                endTime = end,
                endZoneOffset = zone.rules.getOffset(end),
                count = s.steps.toLong(),
                metadata = metadata(ring, "steps-$key-${start.epochSecond}", s.steps.toLong()),
            )
        }
        if (records.isNotEmpty()) client.insertRecords(records)
        return records.size
    }

    /** YuCheng só informa o total do dia: um registro de 00:00 até agora, atualizado a cada sync. */
    suspend fun writeDayTotal(ring: SavedRing, totals: DayTotals, now: Instant = Instant.now()): Int {
        if (totals.steps <= 0) return 0
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        if (!now.isAfter(start)) return 0
        val key = deviceKey(ring.address)
        val record = StepsRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = now,
            endZoneOffset = zone.rules.getOffset(now),
            count = totals.steps.toLong(),
            metadata = metadata(ring, "steps-day-$key-${start.epochSecond}", totals.steps.toLong()),
        )
        client.insertRecords(listOf(record))
        return 1
    }

    suspend fun writeHeartRate(ring: SavedRing, samples: List<HrSample>): Int {
        val valid = samples.filter { it.bpm in 1..300 }.sortedBy { it.time }
        if (valid.isEmpty()) return 0
        val zone = ZoneId.systemDefault()
        val start = valid.first().time
        val end = valid.last().time.plusSeconds(1)
        val record = HeartRateRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            samples = valid.map { HeartRateRecord.Sample(it.time, it.bpm.toLong()) },
            metadata = metadata(ring, "hr-${deviceKey(ring.address)}-${start.epochSecond}", 1),
        )
        client.insertRecords(listOf(record))
        return valid.size
    }

    private fun metadata(ring: SavedRing, clientId: String, version: Long): Metadata =
        Metadata.autoRecorded(
            device = Device(
                type = Device.TYPE_RING,
                manufacturer = ring.protocol?.manufacturer,
                model = modelOf(ring),
            ),
            clientRecordId = clientId,
            clientRecordVersion = version,
        )

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
        )

        fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

        /** Modelo a partir do nome anunciado ("R03_xxxx" -> "R03"), sem o sufixo do aparelho. */
        fun modelOf(ring: SavedRing): String? {
            val base = ring.name.substringBefore('_').trim()
            return base.ifBlank {
                when (ring.protocol) {
                    RingProtocol.COLMI -> "R03"
                    RingProtocol.YUCHENG -> "R11M"
                    null -> null
                }
            }
        }

        /** Identificador estável e não reversível do anel (não grava o MAC no Health Connect). */
        fun deviceKey(address: String): String =
            MessageDigest.getInstance("SHA-256").digest(address.uppercase().toByteArray())
                .take(6).joinToString("") { "%02x".format(it) }
    }
}
