/*
 * LightPods — AirPods status for the Light Phone III
 * Copyright (C) 2026 Giovanni Lupo
 *
 * The proximity-pairing decode below is a port of LibrePods' BLEManager
 * (https://github.com/kavishdevar/librepods, GPL-3.0-or-later). This file and
 * this project are therefore GPL-3.0-or-later. See LICENSE.
 */

package com.gios.lightpods.bt

/** Everything a single proximity-pairing broadcast tells us. */
data class PodsStatus(
    val address: String,
    /** Raw advertised model, kept so an unrecognised one can still be reported. */
    val modelId: Int,
    /** null when the id is not one we recognise — better than naming it wrongly. */
    val model: String?,
    val color: String,
    val connection: String,
    val paired: Boolean,
    val leftBattery: Int?,
    val rightBattery: Int?,
    val caseBattery: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
    val leftInEar: Boolean,
    val rightInEar: Boolean,
    val lidOpen: Boolean,
    /** Raw status byte, so the in-ear bits can be inspected rather than trusted. */
    val statusByte: Int,
    val seenAt: Long,
    /** Signal strength of the advertisement that produced this, in dBm. */
    val rssi: Int = 0,
    /** The advertisement exactly as received, for the debug screen. */
    val raw: ByteArray = ByteArray(0),
) {
    /** True when the advertisement carried no battery figure at all (all nibbles 0xF). */
    val isBlank: Boolean get() = leftBattery == null && rightBattery == null && caseBattery == null

    val worstBattery: Int? get() = listOfNotNull(leftBattery, rightBattery).minOrNull()

    /** What to put on screen when the model id is not in our table. */
    val modelLabel: String get() = model ?: "Earbuds"

    /** Anything other than Disconnected means these are talking to some phone. */
    val inUse: Boolean get() = connection != "Disconnected" && connection != "Unknown"

    // A ByteArray member kills the generated equals/hashCode, and equality drives the
    // repository's change detection, so both are written out by hand.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PodsStatus) return false
        return address == other.address &&
            modelId == other.modelId &&
            leftBattery == other.leftBattery &&
            rightBattery == other.rightBattery &&
            caseBattery == other.caseBattery &&
            leftCharging == other.leftCharging &&
            rightCharging == other.rightCharging &&
            caseCharging == other.caseCharging &&
            leftInEar == other.leftInEar &&
            rightInEar == other.rightInEar &&
            lidOpen == other.lidOpen &&
            connection == other.connection
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + modelId
        result = 31 * result + (leftBattery ?: -1)
        result = 31 * result + (rightBattery ?: -1)
        result = 31 * result + (caseBattery ?: -1)
        result = 31 * result + connection.hashCode()
        return result
    }
}

/**
 * Decoder for Apple's "proximity pairing" BLE advertisement — manufacturer ID 0x004C,
 * type 0x07, 25 bytes of payload. It is broadcast continuously and unencrypted, so it
 * can be read passively without pairing, without root, and without an L2CAP channel.
 *
 * Caveat: recent AirPods firmware also ships an AES-encrypted copy of the battery
 * figures in the tail of the same payload. Decrypting it needs a key that only comes
 * out of the AAP handshake over L2CAP, which the Light Phone III's Android 14
 * Bluetooth stack will not open. We read the legacy nibbles instead. On firmware that
 * has stopped populating them the nibbles read 0xF and we surface "--" rather than a
 * wrong number.
 */
object ProximityPayload {

    const val APPLE_COMPANY_ID = 76 // 0x004C
    const val TYPE_PROXIMITY_PAIRING = 0x07.toByte()
    const val PAYLOAD_LENGTH = 25.toByte()

    /** Byte 0 is the type, byte 1 the length; both are fixed and both are filtered on. */
    private const val MIN_USABLE_SIZE = 11

    private val MODELS = mapOf(
        0x0220 to "AirPods",
        0x0F20 to "AirPods 2",
        0x1320 to "AirPods 3",
        0x1920 to "AirPods 4",
        0x1B20 to "AirPods 4 ANC",
        // AirPods Pro 3 is absent on purpose: LibrePods identifies it by the model
        // number it reads over AAP (A3063/A3064/A3065), not by an advertised id, so
        // there is no verified id to put here. Guessing one produces exactly the bug
        // this comment exists because of — a Pro 3 labelled "AirPods 4".
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro 2",
        0x2420 to "AirPods Pro 2 USB-C",
        0x0A20 to "AirPods Max",
        0x1F20 to "AirPods Max USB-C",
    )

    private val COLORS = mapOf(
        0x00 to "White", 0x01 to "Black", 0x02 to "Red", 0x03 to "Blue",
        0x04 to "Pink", 0x05 to "Gray", 0x06 to "Silver", 0x07 to "Gold",
        0x08 to "Rose Gold", 0x09 to "Space Gray", 0x0A to "Dark Blue",
        0x0B to "Light Blue", 0x0C to "Yellow",
    )

    private val CONNECTION = mapOf(
        0x00 to "Disconnected", 0x04 to "Idle", 0x05 to "Music",
        0x06 to "Call", 0x07 to "Ringing", 0x09 to "Hanging up", 0xFF to "Unknown",
    )

    /**
     * @param data the manufacturer-specific bytes for company 0x004C, type byte included.
     * @return null when the payload is not a proximity message or is too short to read.
     */
    fun parse(
        address: String,
        data: ByteArray,
        now: Long = System.currentTimeMillis(),
        rssi: Int = 0,
    ): PodsStatus? {
        if (data.size < MIN_USABLE_SIZE) return null
        if (data[0] != TYPE_PROXIMITY_PAIRING) return null

        val paired = data[2].toInt() == 1
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val status = data[5].toInt() and 0xFF
        val podsBattery = data[6].toInt() and 0xFF
        val flagsCase = data[7].toInt() and 0xFF
        val lid = data[8].toInt() and 0xFF
        val color = COLORS[data[9].toInt() and 0xFF] ?: "Unknown"
        val connection = CONNECTION[data[10].toInt() and 0xFF] ?: "Unknown"

        // Whichever bud is currently primary broadcasts, so left and right swap places
        // in the payload depending on which one that is. Bit 5 says the primary is the
        // left bud; bit 6 says the broadcasting bud is sitting in the case. In-ear bits
        // are additionally reversed when exactly one of those is true.
        val primaryLeft = ((status shr 5) and 0x01) == 1
        val thisInCase = ((status shr 6) and 0x01) == 1
        val earSwapped = primaryLeft xor thisInCase
        val flipped = !primaryLeft

        // These two are decoded for the debug screen only. The bits are not reliable
        // in practice — they read "in ear" with the buds sitting in a shut case — and
        // LibrePods does not trust them either: its UI takes ear detection from the
        // AAP channel, not from here. Nothing user-facing reads them.
        val leftInEar = if (earSwapped) (status and 0x08) != 0 else (status and 0x02) != 0
        val rightInEar = if (earSwapped) (status and 0x02) != 0 else (status and 0x08) != 0

        val leftNibble = if (flipped) (podsBattery shr 4) and 0x0F else podsBattery and 0x0F
        val rightNibble = if (flipped) podsBattery and 0x0F else (podsBattery shr 4) and 0x0F
        val caseNibble = flagsCase and 0x0F

        val chargeFlags = (flagsCase shr 4) and 0x0F
        val leftCharging = if (flipped) (chargeFlags and 0x02) != 0 else (chargeFlags and 0x01) != 0
        val rightCharging = if (flipped) (chargeFlags and 0x01) != 0 else (chargeFlags and 0x02) != 0
        val caseCharging = (chargeFlags and 0x04) != 0

        // The lid bit is inverted: 0 means open.
        val lidOpen = ((lid shr 3) and 0x01) == 0

        return PodsStatus(
            address = address,
            modelId = modelId,
            model = MODELS[modelId],
            color = color,
            connection = connection,
            paired = paired,
            leftBattery = decodeBattery(leftNibble),
            rightBattery = decodeBattery(rightNibble),
            caseBattery = decodeBattery(caseNibble),
            leftCharging = leftCharging,
            rightCharging = rightCharging,
            caseCharging = caseCharging,
            leftInEar = leftInEar,
            rightInEar = rightInEar,
            lidOpen = lidOpen,
            statusByte = status,
            seenAt = now,
            rssi = rssi,
            raw = data.copyOf(),
        )
    }

    /** Battery arrives as one nibble: 0-9 is decile, 0xA-0xE reads full, 0xF is unknown. */
    internal fun decodeBattery(nibble: Int): Int? = when (nibble) {
        in 0x0..0x9 -> nibble * 10
        in 0xA..0xE -> 100
        else -> null
    }
}
