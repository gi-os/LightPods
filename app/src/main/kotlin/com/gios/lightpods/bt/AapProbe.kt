package com.gios.lightpods.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Diagnostic only: tries to open Apple's Accessory Protocol channel and reports
 * exactly how it fails.
 *
 * AAP is classic L2CAP on PSM 0x1001, and everything interesting — noise control,
 * transparency, gesture remapping, ear-detection toggles — is behind it. Android's
 * Bluetooth stack rejected third-party sockets on that channel until the fix in
 * Android 16 QPR3, and there is no public constructor for a classic L2CAP socket
 * either, so this goes in through the hidden ones.
 *
 * It is wired to a button in the debug screen rather than assumed, because the
 * blanket claim "it cannot work" is worth checking against the actual handset once,
 * and the failure text tells us which wall we hit: a reflection error means the
 * non-SDK interface restrictions blocked us, a connect error means the stack did.
 */
object AapProbe {

    private const val AAP_PSM = 0x1001
    private val AAP_UUID = ParcelUuid(UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"))

    data class Outcome(val ok: Boolean, val detail: String)

    suspend fun run(adapter: BluetoothAdapter, device: BluetoothDevice): Outcome =
        withContext(Dispatchers.IO) {
            val constructors = runCatching {
                BluetoothSocket::class.java.declaredConstructors.size
            }.getOrNull()
                ?: return@withContext Outcome(
                    false,
                    "reflection blocked: BluetoothSocket constructors are not visible",
                )

            val socket = runCatching { open(adapter, device) }
                .getOrElse { return@withContext Outcome(false, "no usable constructor of $constructors: ${it.message}") }

            try {
                socket.connect()
                Outcome(true, "L2CAP 0x1001 open — AAP is reachable on this build")
            } catch (t: Throwable) {
                Outcome(false, "connect refused: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runCatching { socket.close() }
            }
        }

    /**
     * The hidden constructor's signature has changed across releases, so try the
     * known shapes in order. Lifted from LibrePods, which found them the hard way.
     */
    private fun open(adapter: BluetoothAdapter, device: BluetoothDevice): BluetoothSocket {
        val l2cap = 3
        val specs = listOf(
            arrayOf<Any>(adapter, device, l2cap, true, true, AAP_PSM, AAP_UUID),
            arrayOf<Any>(device, l2cap, true, true, AAP_PSM, AAP_UUID),
            arrayOf<Any>(device, l2cap, 1, true, true, AAP_PSM, AAP_UUID),
            arrayOf<Any>(l2cap, 1, true, true, device, AAP_PSM, AAP_UUID),
            arrayOf<Any>(l2cap, true, true, device, AAP_PSM, AAP_UUID),
        )
        var last: Throwable? = null
        for (params in specs) {
            try {
                val types = params.map { it::class.javaPrimitiveType ?: it::class.java }
                val constructor = BluetoothSocket::class.java.getDeclaredConstructor(*types.toTypedArray())
                constructor.isAccessible = true
                return constructor.newInstance(*params) as BluetoothSocket
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("no constructor matched")
    }
}
