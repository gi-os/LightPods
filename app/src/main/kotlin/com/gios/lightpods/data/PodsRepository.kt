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

    /** A view older than this means the pair is out of range or shut in its case. */
    const val STALE_AFTER_MS = 30_000L

    private val tracker = PodsTracker()

    private val _view = MutableStateFlow<PodsView?>(null)
    val view: StateFlow<PodsView?> = _view.asStateFlow()

    /** Everything in radio range, loudest first. Debug screen only. */
    private val _candidates = MutableStateFlow<List<PodsStatus>>(emptyList())
    val candidates: StateFlow<List<PodsStatus>> = _candidates.asStateFlow()

    /** True while an audio profile actually has the earbuds attached. */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

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
        _view.value = tracker.accept(status)
        _candidates.value = tracker.candidates()
    }

    fun setConnected(connected: Boolean) {
        _connected.value = connected
    }

    fun setUiActive(active: Boolean) {
        _uiActive.value = active
    }

    fun setConnectResult(message: String?) {
        _connectResult.value = message
    }

    fun clear() {
        tracker.clear()
        _view.value = null
        _candidates.value = emptyList()
    }
}

/** A view nobody has refreshed in half a minute is history, not status. */
fun PodsView.isStale(now: Long = System.currentTimeMillis()): Boolean =
    now - seenAt > PodsRepository.STALE_AFTER_MS
