package com.gios.lightpods.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Best-effort "connect my earbuds" button.
 *
 * Android has no public API for this. `BluetoothA2dp.connect()` exists but is hidden and
 * carries `@RequiresPermission(BLUETOOTH_PRIVILEGED)`, which a sideloaded app cannot
 * hold. So we try three things in order of how clean they are and report which one
 * worked; on LightOS the answer is likely to be the third.
 *
 *  1. Reflective A2DP connect through the profile proxy.
 *  2. Reflective HFP connect through the profile proxy.
 *  3. An RFCOMM socket to the handsfree service record. Opening it forces the ACL link
 *     up, and AirPods reliably respond by completing the audio connection themselves.
 *
 * If all three fail the caller should fall back to the system Bluetooth settings page.
 */
class PodsConnector(private val context: Context) {

    sealed interface Result {
        data class Connected(val via: String, val device: String) : Result
        data object NoDevice : Result
        data object BluetoothOff : Result
        data class Failed(val detail: String) : Result
    }

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** The Apple audio device we most likely want, out of everything already bonded. */
    @SuppressLint("MissingPermission")
    fun targetDevice(): BluetoothDevice? {
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull().orEmpty()
        val audio = bonded.filter {
            it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        }
        // Prefer something that names itself; otherwise take the only audio device bonded.
        return audio.firstOrNull { d ->
            val n = runCatching { d.name }.getOrNull().orEmpty().lowercase()
            APPLE_NAMES.any { it in n }
        } ?: audio.singleOrNull()
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(): Result {
        val adapter = adapter ?: return Result.BluetoothOff
        if (!adapter.isEnabled) return Result.BluetoothOff
        val device = targetDevice() ?: return Result.NoDevice
        val label = runCatching { device.name }.getOrNull() ?: device.address

        if (isConnected(device)) return Result.Connected("already connected", label)

        reflectiveConnect(adapter, device, BluetoothProfile.A2DP)?.let {
            return Result.Connected(it, label)
        }
        reflectiveConnect(adapter, device, BluetoothProfile.HEADSET)?.let {
            return Result.Connected(it, label)
        }
        aclPoke(device)?.let { return Result.Connected(it, label) }

        return Result.Failed("open Bluetooth settings")
    }

    /** True when either audio profile already has the device attached. */
    @SuppressLint("MissingPermission")
    private suspend fun isConnected(device: BluetoothDevice): Boolean {
        val adapter = adapter ?: return false
        for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            val proxy = proxy(adapter, profile) ?: continue
            val connected = runCatching { proxy.connectedDevices }.getOrNull().orEmpty()
            adapter.closeProfileProxy(profile, proxy)
            if (connected.any { it.address == device.address }) return true
        }
        return false
    }

    /**
     * Hidden `connect(BluetoothDevice)` on the profile proxy. Expected to throw
     * SecurityException without BLUETOOTH_PRIVILEGED, and may be blocked outright by
     * the non-SDK interface restrictions — both are caught, this is the optimistic path.
     */
    private suspend fun reflectiveConnect(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        profile: Int,
    ): String? {
        val proxy = proxy(adapter, profile) ?: return null
        val name = if (profile == BluetoothProfile.A2DP) "A2DP" else "HFP"
        return try {
            val cls = if (profile == BluetoothProfile.A2DP) {
                BluetoothA2dp::class.java
            } else {
                BluetoothHeadset::class.java
            }
            val method = cls.getMethod("connect", BluetoothDevice::class.java)
            val ok = method.invoke(proxy, device) as? Boolean ?: false
            if (ok && awaitConnection(profile, device)) "$name profile" else null
        } catch (t: Throwable) {
            Log.d(TAG, "$name reflective connect unavailable: ${t.javaClass.simpleName}")
            null
        } finally {
            adapter.closeProfileProxy(profile, proxy)
        }
    }

    /**
     * Opening an RFCOMM channel to the handsfree service record brings the ACL link up.
     * The pods treat that as the phone coming back and finish the audio connection on
     * their own, so we close the socket immediately and then watch the profile state.
     */
    @SuppressLint("MissingPermission")
    private suspend fun aclPoke(device: BluetoothDevice): String? = withContext(Dispatchers.IO) {
        val socket = runCatching {
            device.createInsecureRfcommSocketToServiceRecord(HANDSFREE_UUID)
        }.getOrNull() ?: return@withContext null

        runCatching {
            adapter?.cancelDiscovery()
            socket.connect()
        }.onFailure {
            // A refused connection still raised the link, which is all we wanted.
            Log.d(TAG, "rfcomm connect: ${it.message}")
        }
        runCatching { socket.close() }

        if (awaitConnection(BluetoothProfile.A2DP, device)) "link wake-up" else null
    }

    /** Poll the profile until the device shows up, or give up. */
    @SuppressLint("MissingPermission")
    private suspend fun awaitConnection(profile: Int, device: BluetoothDevice): Boolean {
        val adapter = adapter ?: return false
        repeat(CONNECT_ATTEMPTS) {
            val proxy = proxy(adapter, profile)
            if (proxy != null) {
                val hit = runCatching { proxy.connectedDevices }.getOrNull()
                    .orEmpty().any { it.address == device.address }
                adapter.closeProfileProxy(profile, proxy)
                if (hit) return true
            }
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    /** getProfileProxy is callback based; wrap it so the chain above stays readable. */
    private suspend fun proxy(adapter: BluetoothAdapter, profile: Int): BluetoothProfile? =
        runCatching {
            withTimeout(PROXY_TIMEOUT_MS) {
                suspendCancellableCoroutine<BluetoothProfile?> { cont ->
                    val listener = object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                            if (cont.isActive) cont.resume(proxy)
                        }

                        override fun onServiceDisconnected(p: Int) = Unit
                    }
                    if (!adapter.getProfileProxy(context, listener, profile) && cont.isActive) {
                        cont.resume(null)
                    }
                }
            }
        }.getOrNull()

    private companion object {
        const val TAG = "LightPods/Connect"
        const val CONNECT_ATTEMPTS = 20 // x POLL_INTERVAL_MS = 8s
        const val PROXY_TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 400L
        val HANDSFREE_UUID: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
        val APPLE_NAMES = listOf("airpod", "pods", "beats", "powerbeats")
    }
}
