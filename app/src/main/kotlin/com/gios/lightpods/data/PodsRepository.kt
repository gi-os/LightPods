package com.gios.lightpods.data

import com.gios.lightpods.bt.PodsStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth shared by the service and the UI. Process-wide because the
 * foreground service owns the radio and the activity only observes it.
 */
object PodsRepository {

    /** A broadcast older than this means the pods are out of range or in a shut case. */
    const val STALE_AFTER_MS = 30_000L

    private val _status = MutableStateFlow<PodsStatus?>(null)
    val status: StateFlow<PodsStatus?> = _status.asStateFlow()

    /**
     * True while the activity is on screen. The service watches this instead of the
     * activity restarting it: on Android 12+ a service start issued from onStop lands
     * after the process is already considered background, which throws.
     */
    private val _uiActive = MutableStateFlow(false)
    val uiActive: StateFlow<Boolean> = _uiActive.asStateFlow()

    private val _connectResult = MutableStateFlow<String?>(null)
    val connectResult: StateFlow<String?> = _connectResult.asStateFlow()

    fun publish(status: PodsStatus) {
        val current = _status.value
        // A blank payload (every nibble 0xF) shows up when the case is shut. Keep the
        // last real reading on screen instead of flashing "--" at the user.
        if (status.isBlank && current != null && !current.isStale()) return
        _status.value = status
    }

    fun setUiActive(active: Boolean) {
        _uiActive.value = active
    }

    fun setConnectResult(message: String?) {
        _connectResult.value = message
    }

    fun clear() {
        _status.value = null
    }
}

/** A reading nobody has refreshed in half a minute is history, not status. */
fun PodsStatus.isStale(now: Long = System.currentTimeMillis()): Boolean =
    now - seenAt > PodsRepository.STALE_AFTER_MS
