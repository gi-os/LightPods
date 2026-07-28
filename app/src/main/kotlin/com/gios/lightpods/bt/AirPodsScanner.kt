package com.gios.lightpods.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log

/**
 * Passive listener for Apple proximity-pairing broadcasts.
 *
 * The scan is filtered in the Bluetooth controller rather than in our callback: the
 * filter pins manufacturer 0x004C and the first two payload bytes (type 0x07, length
 * 25), so the radio wakes the app only for AirPods traffic. That matters more here
 * than on a normal phone — the LPIII has a 1800 mAh battery.
 */
class AirPodsScanner(private val context: Context) {

    /** Scan duty cycle. Continuous low-latency scanning costs roughly 4%/hour. */
    enum class Mode(val scanMode: Int, val reportDelayMs: Long) {
        /** Screen on, user is looking at the app. */
        ACTIVE(ScanSettings.SCAN_MODE_LOW_LATENCY, 0L),

        /** Background: batch results and let the controller coalesce them. */
        IDLE(ScanSettings.SCAN_MODE_LOW_POWER, 4_000L),
    }

    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var mode: Mode? = null

    var onStatus: ((PodsStatus) -> Unit)? = null

    val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun start(newMode: Mode) {
        if (mode == newMode && callback != null) return
        stop()

        val adapter = adapter ?: return
        if (!adapter.isEnabled) {
            Log.d(TAG, "bluetooth off, not scanning")
            return
        }
        scanner = adapter.bluetoothLeScanner ?: return

        // Match on the two fixed header bytes only; everything after them is state
        // that changes constantly, so the mask stops there.
        val prefix = byteArrayOf(ProximityPayload.TYPE_PROXIMITY_PAIRING, ProximityPayload.PAYLOAD_LENGTH)
        val mask = byteArrayOf(0xFF.toByte(), 0xFF.toByte())

        val filter = ScanFilter.Builder()
            .setManufacturerData(ProximityPayload.APPLE_COMPANY_ID, prefix, mask)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(newMode.scanMode)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(newMode.reportDelayMs)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                // Keep only the freshest broadcast per address; a batch can hold several.
                results.groupBy { it.device.address }
                    .forEach { (_, group) -> handle(group.last()) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "scan failed: $errorCode")
            }
        }

        runCatching { scanner?.startScan(listOf(filter), settings, cb) }
            .onSuccess {
                callback = cb
                mode = newMode
                Log.d(TAG, "scanning (${newMode.name})")
            }
            .onFailure { Log.e(TAG, "startScan threw", it) }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        runCatching { scanner?.stopScan(cb) }
        callback = null
        mode = null
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(ProximityPayload.APPLE_COMPANY_ID) ?: return
        // AirPods rotate their advertising address every ~15 minutes. Pinning to one
        // address would need the identity resolving key, which only the AAP handshake
        // hands over, so we accept any Apple proximity broadcast in range instead.
        val status = ProximityPayload.parse(result.device.address, data) ?: return
        onStatus?.invoke(status)
    }

    private companion object {
        const val TAG = "LightPods/Scan"
    }
}
