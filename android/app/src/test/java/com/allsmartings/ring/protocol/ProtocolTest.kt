package com.allsmartings.ring.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun colmiBatteryPacket() {
        val p = Colmi.batteryRequest()
        assertEquals(16, p.size)
        assertEquals(0x03, p[0].toInt())
        assertEquals(0x03, p[15].toInt())
    }

    @Test fun colmiStepsPacketChecksum() {
        val p = Colmi.stepsRequest(0)
        assertArrayEquals(bytes(0x43, 0x00, 0x0F, 0x00, 0x5F, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xB2), p)
    }

    @Test fun colmiClockIsBcd() {
        val p = Colmi.clockRequest(LocalDateTime.of(2026, 11, 24, 13, 45, 9))
        assertArrayEquals(bytes(0x01, 0x26, 0x11, 0x24, 0x13, 0x45, 0x09, 0x01), p.copyOfRange(0, 8))
    }

    @Test fun colmiStepsParse() {
        fun data(slot: Int, idx: Int, total: Int, steps: Int) = Colmi.build(
            0x43,
            bytes(0x26, 0x11, 0x24, slot, idx, total, 10, 0, steps and 0xFF, steps shr 8, 50, 0),
        )
        val header = Colmi.build(0x43, bytes(0xF0, 2, 1))
        val resps = listOf(header, data(32, 0, 2, 300), data(33, 1, 2, 120))
        assertTrue(Colmi.stepsDone(resps))
        val slots = Colmi.parseSteps(resps)
        assertEquals(2, slots.size)
        assertEquals(LocalDateTime.of(2026, 11, 24, 8, 0), slots[0].start)
        assertEquals(420, slots.sumOf { it.steps })
        assertEquals(1.0, slots[0].calories, 0.001) // calorias em décimos (header byte3 == 1)
    }

    @Test fun colmiNoData() {
        val resps = listOf(Colmi.build(0x43, bytes(0xFF)))
        assertTrue(Colmi.stepsDone(resps))
        assertTrue(Colmi.parseSteps(resps).isEmpty())
    }

    @Test fun colmiHr() {
        assertEquals(Colmi.HrEvent.Bpm(72), Colmi.parseHr(Colmi.build(0x69, bytes(0x01, 0x00, 72))))
        assertEquals(Colmi.HrEvent.Error(1), Colmi.parseHr(Colmi.build(0x69, bytes(0x01, 0x01, 0))))
        assertNull(Colmi.parseHr(Colmi.build(0x69, bytes(0x01, 0x00, 0))))
    }

    @Test fun yuchengFrame() {
        val p = YuCheng.stepsRequest()
        assertEquals(6, p.size)
        assertArrayEquals(bytes(0x02, 0x0C, 0x06, 0x00), p.copyOfRange(0, 4))
        val crc = YuCheng.crc16(p.copyOfRange(0, 4))
        assertEquals(crc and 0xFF, p[4].toInt() and 0xFF)
        assertEquals(crc shr 8, p[5].toInt() and 0xFF)
    }

    @Test fun yuchengClock() {
        val p = YuCheng.clockRequest(LocalDateTime.of(2026, 11, 23, 7, 8, 9)) // segunda-feira
        assertArrayEquals(bytes(0x01, 0x00, 14, 0, 0xEA, 0x07, 11, 23, 7, 8, 9, 0), p.copyOfRange(0, 12))
    }

    @Test fun yuchengParse() {
        val info = bytes(0x02, 0x00, 16, 0, 0x34, 0x12, 1, 2, 0, 87, 1, 0, 0, 0)
        assertEquals(87, YuCheng.parseBattery(listOf(info))?.percent)
        val steps = bytes(0x02, 0x0C, 14, 0, 0x10, 0x02, 0, 0x20, 0x00, 0xD2, 0x04, 0, 0)
        val t = YuCheng.parseSteps(listOf(steps))!!
        assertEquals(1234, t.steps)
        assertEquals(0x210, t.distanceM)
        assertEquals(0x20, t.calories)
    }

    @Test fun detectProtocol() {
        assertEquals(RingProtocol.YUCHENG, RingProtocol.detect(listOf("BE940000-7333-BE46-B7AE-689E71722BD5")))
        assertEquals(RingProtocol.COLMI, RingProtocol.detect(listOf("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")))
        assertNull(RingProtocol.detect(listOf("0000180a-0000-1000-8000-00805f9b34fb")))
    }
}
