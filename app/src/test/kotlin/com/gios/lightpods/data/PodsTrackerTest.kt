package com.gios.lightpods.data

import com.gios.lightpods.bt.PodsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PodsTrackerTest {

    private var clock = 1_000L
    private val tracker = PodsTracker { clock }

    private fun advert(
        address: String = "AA:01",
        modelId: Int = 0x1420,
        left: Int? = null,
        right: Int? = null,
        case: Int? = null,
        rssi: Int = -50,
        connection: String = "Music",
    ) = PodsStatus(
        address = address,
        modelId = modelId,
        model = null,
        color = "Black",
        connection = connection,
        paired = true,
        leftBattery = left,
        rightBattery = right,
        caseBattery = case,
        leftCharging = false,
        rightCharging = false,
        caseCharging = false,
        leftInEar = false,
        rightInEar = false,
        lidOpen = false,
        statusByte = 0x20,
        seenAt = clock,
        rssi = rssi,
    )

    @Test
    fun `both buds stay on screen when they take turns broadcasting`() {
        tracker.accept(advert(left = 80))
        clock += 1_000
        val view = tracker.accept(advert(right = 70))!!

        assertEquals(80, view.left?.percent)
        assertEquals(70, view.right?.percent)
    }

    @Test
    fun `a fresh figure replaces the remembered one`() {
        tracker.accept(advert(left = 80))
        clock += 1_000
        val view = tracker.accept(advert(left = 70))!!

        assertEquals(70, view.left?.percent)
    }

    @Test
    fun `a remembered figure is dropped once it goes properly stale`() {
        tracker.accept(advert(left = 80))
        clock += 21_000
        val view = tracker.accept(advert(right = 70))!!

        assertNull(view.left)
        assertEquals(70, view.right?.percent)
    }

    @Test
    fun `a bud that stops broadcasting ages out without any new advertisement`() {
        tracker.accept(advert(left = 80, right = 70))
        clock += 21_000

        val view = tracker.expire()!!
        assertNull(view.left)
        assertNull(view.right)
    }

    @Test
    fun `expiry leaves a reading that is still current alone`() {
        tracker.accept(advert(left = 80))
        clock += 5_000

        assertEquals(80, tracker.expire()?.left?.percent)
    }

    @Test
    fun `a pair in use beats a louder idle pair`() {
        tracker.accept(advert(address = "AA:01", rssi = -80, connection = "Music"))
        val view = tracker.accept(
            advert(address = "BB:02", modelId = 0x1920, rssi = -40, connection = "Disconnected"),
        )!!

        assertEquals("AA:01", view.address)
    }

    @Test
    fun `the incumbent survives ordinary signal jitter`() {
        tracker.accept(advert(address = "AA:01", rssi = -60))
        val view = tracker.accept(advert(address = "BB:02", modelId = 0x1920, rssi = -55))!!

        assertEquals("AA:01", view.address)
    }

    @Test
    fun `a clearly closer pair does take over`() {
        tracker.accept(advert(address = "AA:01", rssi = -70))
        val view = tracker.accept(advert(address = "BB:02", modelId = 0x1920, rssi = -40))!!

        assertEquals("BB:02", view.address)
    }

    @Test
    fun `switching devices does not carry readings across`() {
        tracker.accept(advert(address = "AA:01", left = 80, rssi = -70))
        val view = tracker.accept(
            advert(address = "BB:02", modelId = 0x1920, right = 60, rssi = -40),
        )!!

        assertNull(view.left)
        assertEquals(60, view.right?.percent)
    }

    @Test
    fun `a candidate that stops broadcasting leaves the running`() {
        tracker.accept(advert(address = "AA:01", rssi = -40))
        clock += 16_000
        tracker.accept(advert(address = "BB:02", modelId = 0x1920, rssi = -80))

        assertEquals(listOf("BB:02"), tracker.candidates().map { it.address })
    }
}
