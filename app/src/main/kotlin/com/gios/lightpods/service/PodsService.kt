package com.gios.lightpods.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.gios.lightpods.MainActivity
import com.gios.lightpods.R
import com.gios.lightpods.bt.AirPodsScanner
import com.gios.lightpods.bt.PodsStatus
import com.gios.lightpods.data.PodsRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Owns the radio for the whole process. It runs in the foreground because a plain
 * background service is frozen by Doze within minutes, and a battery readout that is
 * twenty minutes stale is worse than no readout.
 */
class PodsService : LifecycleService() {

    private lateinit var scanner: AirPodsScanner
    private var lastNotified: String? = null

    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> scanner.start(currentMode)
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    scanner.stop()
                    PodsRepository.clear()
                }
            }
        }
    }

    private var currentMode = AirPodsScanner.Mode.IDLE

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // API 34 wants the type restated at start time, not just in the manifest.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        scanner = AirPodsScanner(this).apply { onStatus = PodsRepository::publish }
        // targetSdk 34 makes the export flag mandatory; this one is system-only.
        ContextCompat.registerReceiver(
            this,
            bluetoothState,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        lifecycleScope.launch {
            PodsRepository.uiActive.distinctUntilChanged().collectLatest { active ->
                currentMode = if (active) AirPodsScanner.Mode.ACTIVE else AirPodsScanner.Mode.IDLE
                scanner.start(currentMode)
            }
        }

        lifecycleScope.launch {
            PodsRepository.status.collectLatest { status ->
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
        return START_STICKY
    }

    override fun onDestroy() {
        scanner.stop()
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Earbud battery",
            // MIN keeps it silent and collapsed; it is a status line, not an alert.
            NotificationManager.IMPORTANCE_MIN,
        ).apply { setShowBadge(false) }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: PodsStatus?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(status?.model ?: "Earbuds")
            .setContentText(status?.let(::summarise) ?: "Searching")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun summarise(s: PodsStatus): String {
        fun pct(v: Int?) = v?.let { "$it%" } ?: "--"
        val case = s.caseBattery?.let { "  Case $it%" } ?: ""
        return "L ${pct(s.leftBattery)}   R ${pct(s.rightBattery)}$case"
    }

    companion object {
        private const val CHANNEL_ID = "pods_status"
        private const val NOTIFICATION_ID = 1
        /** Only ever call this while the app is on screen; see PodsRepository.uiActive. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, PodsService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PodsService::class.java))
        }
    }
}
