package com.gios.lightpods.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected values were produced by running LibrePods' own BLEManager decode over the
 * same four state bytes, so these cases pin our port to the reference implementation
 * rather than to our reading of it.
 */
class ProximityPayloadTest {

    private fun payload(
        status: Int,
        pods: Int,
        flagsCase: Int,
        lid: Int,
        modelId: Int = 0x1420,
        paired: Int = 1,
        color: Int = 0x01,
        connection: Int = 0x05,
    ): ByteArray = ByteArray(25).also {
        it[0] = 0x07
        it[1] = 25
        it[2] = paired.toByte()
        it[3] = ((modelId shr 8) and 0xFF).toByte()
        it[4] = (modelId and 0xFF).toByte()
        it[5] = status.toByte()
        it[6] = pods.toByte()
        it[7] = flagsCase.toByte()
        it[8] = lid.toByte()
        it[9] = color.toByte()
        it[10] = connection.toByte()
    }

    @Test
    fun `the raw status byte is kept for inspection`() {
        val s = ProximityPayload.parse("AA:BB", payload(0x2B, 0x87, 0x59, 0x31), now = 1L)!!
        assertEquals(0x2B, s.statusByte)
    }

    @Test
    fun `primary right swaps the battery nibbles back`() {
        val s = ProximityPayload.parse("AA:BB", payload(0x0B, 0x87, 0x59, 0x31), now = 1L)!!

        assertEquals(80, s.leftBattery)
        assertEquals(70, s.rightBattery)
        assertEquals(90, s.caseBattery)
        assertFalse(s.leftCharging)
        assertTrue(s.rightCharging)
        assertTrue(s.caseCharging)
        assertTrue(s.lidOpen)
        assertEquals("AirPods Pro 2", s.model)
    }

    @Test
    fun `primary left with both buds stowed`() {
        val s = ProximityPayload.parse("AA:BB", payload(0x60, 0x99, 0x2A, 0x39), now = 1L)!!

        assertEquals(90, s.leftBattery)
        assertEquals(90, s.rightBattery)
        assertEquals(100, s.caseBattery)
        assertFalse(s.lidOpen)
        assertTrue(s.rightCharging)
    }

    @Test
    fun `charging flags follow the same flip as the batteries`() {
        val s = ProximityPayload.parse("AA:BB", payload(0x20, 0x68, 0x17, 0x31), now = 1L)!!

        assertEquals(80, s.leftBattery)
        assertEquals(60, s.rightBattery)
        assertEquals(70, s.caseBattery)
        assertTrue(s.leftCharging)
        assertFalse(s.rightCharging)
        assertTrue(s.lidOpen)
    }

    @Test
    fun `an all-0xF payload reports nothing rather than zero`() {
        val s = ProximityPayload.parse("AA:BB", payload(0x00, 0xFF, 0x0F, 0x39), now = 1L)!!

        assertNull(s.leftBattery)
        assertNull(s.rightBattery)
        assertNull(s.caseBattery)
        assertTrue(s.isBlank)
    }

    @Test
    fun `nibble decode covers the full range`() {
        assertEquals(0, ProximityPayload.decodeBattery(0x0))
        assertEquals(50, ProximityPayload.decodeBattery(0x5))
        assertEquals(90, ProximityPayload.decodeBattery(0x9))
        assertEquals(100, ProximityPayload.decodeBattery(0xA))
        assertEquals(100, ProximityPayload.decodeBattery(0xE))
        assertNull(ProximityPayload.decodeBattery(0xF))
    }

    @Test
    fun `an unrecognised model is reported as unknown rather than mislabelled`() {
        val s = ProximityPayload.parse("AA:BB", payload(0x20, 0x88, 0x18, 0x31, modelId = 0x9999))!!
        assertNull(s.model)
        assertEquals(0x9999, s.modelId)
        assertEquals("Earbuds", s.modelLabel)
    }

    @Test
    fun `non-proximity and truncated payloads are rejected`() {
        val wrongType = payload(0x20, 0x88, 0x18, 0x31).also { it[0] = 0x12 }
        assertNull(ProximityPayload.parse("AA:BB", wrongType))
        assertNull(ProximityPayload.parse("AA:BB", ByteArray(6) { 0x07 }))
    }

    @Test
    fun `worst battery ignores the case`() {
        val s = ProximityPayload.parse("AA:BB", payload(0x20, 0x68, 0x1F, 0x31), now = 1L)!!
        assertEquals(60, s.worstBattery)
    }
}
