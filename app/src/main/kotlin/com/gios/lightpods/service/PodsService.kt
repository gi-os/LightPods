package com.gios.lightpods.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.gios.lightpods.MainActivity
import com.gios.lightpods.R
import com.gios.lightpods.bt.AirPodsScanner
import com.gios.lightpods.bt.PodsConnector
import com.gios.lightpods.data.PodsRepository
import com.gios.lightpods.data.PodsView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Owns the radio for the whole process. It runs in the foreground because a plain
 * background service is frozen by Doze within minutes, and a battery readout that is
 * twenty minutes stale is worse than no readout.
 */
class PodsService : LifecycleService() {

    private lateinit var scanner: AirPodsScanner
    private lateinit var connector: PodsConnector
    private var lastNotified: String? = null

    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_ON -> scanner.start(currentMode)
                        BluetoothAdapter.STATE_TURNING_OFF -> {
                            scanner.stop()
                            PodsRepository.clear()
                            PodsRepository.setConnected(false)
                        }
                    }

                // An audio device coming or going is the only cheap signal that the
                // earbuds attached or detached; polling the profile proxy on a timer
                // costs a binder round trip every few seconds for the same answer.
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                -> refreshConnected()
            }
        }
    }

    private var currentMode = AirPodsScanner.Mode.IDLE

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scanner = AirPodsScanner(this).apply { onStatus = PodsRepository::publish }
        connector = PodsConnector(this)

        // API 34 wants the type restated at start time, not just in the manifest.
        // This can also be refused outright: the system may recreate a sticky service
        // while the process is in the background, which is precisely the case
        // Android 12 forbids from starting one. Losing the service beats crashing.
        val promoted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.isSuccess
        if (!promoted) {
            stopSelf()
            return
        }
        // targetSdk 34 makes the export flag mandatory; this one is system-only.
        ContextCompat.registerReceiver(
            this,
            bluetoothState,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshConnected()

        lifecycleScope.launch {
            // No distinctUntilChanged here: StateFlow already conflates equal
            // values, and applying it is an error rather than a warning in Kotlin 2.
            PodsRepository.uiActive.collectLatest { active ->
                currentMode = if (active) AirPodsScanner.Mode.ACTIVE else AirPodsScanner.Mode.IDLE
                scanner.start(currentMode)
                if (active) refreshConnected()
            }
        }

        lifecycleScope.launch {
            PodsRepository.view.collectLatest { status ->
                val summary = status?.let(::summarise)
                // Rebuilding the notification on every advertisement would wake the
                // panel constantly, so only push when the text actually changes.
                if (summary != lastNotified) {
                    lastNotified = summary
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(status))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        scanner.start(currentMode)
        // The activity starts us again in onStart, so there is nothing to gain from a
        // sticky restart into a background process that cannot go foreground anyway.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::scanner.isInitialized) scanner.stop()
        runCatching { unregisterReceiver(bluetoothState) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private val notificationManager
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Earbud battery",
            // MIN keeps it silent and collapsed; it is a status line, not an alert.
            NotificationManager.IMPORTANCE_MIN,
        ).apply { setShowBadge(false) }
        notificationManager.createNotificationChannel(channel)
    }

    private fun refreshConnected() {
        lifecycleScope.launch { PodsRepository.setConnected(connector.isConnected()) }
    }

    private fun buildNotification(status: PodsView?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(status?.modelLabel ?: "Earbuds")
            .setContentText(status?.let(::summarise) ?: "Searching")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun summarise(s: PodsView): String {
        fun pct(r: com.gios.lightpods.data.Reading?) = r?.let { "${it.percent}%" } ?: "--"
        val case = s.case?.let { "   Case ${it.percent}%" } ?: ""
        return "L ${pct(s.left)}   R ${pct(s.right)}$case"
    }

    companion object {
        private const val CHANNEL_ID = "pods_status"
        private const val NOTIFICATION_ID = 1
        /** Only ever call this while the app is on screen; see PodsRepository.uiActive. */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, PodsService::class.java))
            }
        }
    }
}
