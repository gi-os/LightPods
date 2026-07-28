package com.gios.lightpods.data

import com.gios.lightpods.bt.PodsStatus

/** One side's charge, remembered with the moment it was last actually broadcast. */
data class Reading(val percent: Int, val charging: Boolean, val at: Long)

/** The merged picture of the one device we have decided is the user's. */
data class PodsView(
    val address: String,
    val modelId: Int,
    val model: String?,
    val left: Reading?,
    val right: Reading?,
    val case: Reading?,
    val leftInEar: Boolean,
    val rightInEar: Boolean,
    val lidOpen: Boolean,
    val connection: String,
    val rssi: Int,
    val seenAt: Long,
    val raw: ByteArray = ByteArray(0),
) {
    val modelLabel: String get() = model ?: "Earbuds"

    override fun equals(other: Any?): Boolean = other is PodsView &&
        address == other.address && modelId == other.modelId &&
        left == other.left && right == other.right && case == other.case &&
        leftInEar == other.leftInEar && rightInEar == other.rightInEar &&
        lidOpen == other.lidOpen && connection == other.connection

    override fun hashCode(): Int = address.hashCode() * 31 + modelId
}

/**
 * Turns a stream of proximity advertisements into a picture of one pair of earbuds.
 *
 * Two problems make this less trivial than it sounds.
 *
 * Every pair of AirPods within radio range broadcasts the same message, and without
 * the identity resolving key there is no cryptographic way to tell which pair is the
 * user's. Taking whatever arrived last means a colleague's AirPods overwrite yours,
 * which shows up as a model name that is not your model and battery figures that jump.
 * So candidates are ranked: a pair that reports itself in use with some phone beats an
 * idle one, and after that the nearest wins. The choice is sticky, because RSSI swings
 * several dB between advertisements and a naive maximum would flap.
 *
 * Second, a single advertisement often carries only the broadcasting bud's charge; the
 * other nibble reads 0xF. Which bud broadcasts alternates. Rendering one advertisement
 * at a time therefore shows one bud at a time. Readings are merged instead, each side
 * keeping its last real value until it goes properly stale.
 */
class PodsTracker(private val now: () -> Long = System::currentTimeMillis) {

    private val candidates = LinkedHashMap<String, PodsStatus>()
    private var view: PodsView? = null

    /** @return the merged view, or null while nothing has been heard. */
    fun accept(status: PodsStatus): PodsView? {
        candidates[status.address] = status
        prune()

        val chosen = select() ?: return view
        if (chosen.address != view?.address || chosen.modelId != view?.modelId) {
            // A different pair, or the same pair after its address rotated. Either way
            // the merged readings belong to something else now.
            view = fresh(chosen)
            return view
        }
        view = merge(view!!, chosen)
        return view
    }

    /** Everything currently in range, loudest first. For the debug screen. */
    fun candidates(): List<PodsStatus> {
        prune()
        return candidates.values.sortedByDescending { it.rssi }
    }

    fun selectedAddress(): String? = view?.address

    fun clear() {
        candidates.clear()
        view = null
    }

    private fun prune() {
        val cutoff = now() - CANDIDATE_TTL_MS
        candidates.entries.removeAll { it.value.seenAt < cutoff }
    }

    /**
     * In use beats idle; after that, loudest wins. The incumbent keeps its place
     * unless a rival is clearly better, so ordinary RSSI jitter cannot dislodge it.
     */
    private fun select(): PodsStatus? {
        val best = candidates.values.maxWithOrNull(
            compareBy<PodsStatus> { it.inUse }.thenBy { it.rssi },
        ) ?: return null

        val incumbent = view?.address?.let { candidates[it] } ?: return best
        if (best.address == incumbent.address) return best
        if (best.inUse && !incumbent.inUse) return best
        if (!best.inUse && incumbent.inUse) return incumbent
        return if (best.rssi > incumbent.rssi + SWITCH_MARGIN_DB) best else incumbent
    }

    private fun fresh(s: PodsStatus) = PodsView(
        address = s.address,
        modelId = s.modelId,
        model = s.model,
        left = s.leftBattery?.let { Reading(it, s.leftCharging, s.seenAt) },
        right = s.rightBattery?.let { Reading(it, s.rightCharging, s.seenAt) },
        case = s.caseBattery?.let { Reading(it, s.caseCharging, s.seenAt) },
        leftInEar = s.leftInEar,
        rightInEar = s.rightInEar,
        lidOpen = s.lidOpen,
        connection = s.connection,
        rssi = s.rssi,
        seenAt = s.seenAt,
        raw = s.raw,
    )

    private fun merge(old: PodsView, s: PodsStatus) = old.copy(
        model = s.model ?: old.model,
        left = keep(old.left, s.leftBattery, s.leftCharging, s.seenAt),
        right = keep(old.right, s.rightBattery, s.rightCharging, s.seenAt),
        case = keep(old.case, s.caseBattery, s.caseCharging, s.seenAt),
        leftInEar = s.leftInEar,
        rightInEar = s.rightInEar,
        lidOpen = s.lidOpen,
        connection = s.connection,
        rssi = s.rssi,
        seenAt = s.seenAt,
        raw = s.raw,
    )

    /** A fresh figure always wins; otherwise hold the last one until it ages out. */
    private fun keep(previous: Reading?, percent: Int?, charging: Boolean, at: Long): Reading? =
        when {
            percent != null -> Reading(percent, charging, at)
            previous != null && at - previous.at <= READING_TTL_MS -> previous
            else -> null
        }

    private companion object {
        /** How long a pair stays in the running after its last advertisement. */
        const val CANDIDATE_TTL_MS = 30_000L

        /** How long one side's charge stays on screen without being re-broadcast. */
        const val READING_TTL_MS = 120_000L

        /** A rival has to be this much louder before it takes over, in dB. */
        const val SWITCH_MARGIN_DB = 12
    }
}
