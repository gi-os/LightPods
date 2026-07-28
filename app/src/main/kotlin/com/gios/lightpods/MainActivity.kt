package com.gios.lightpods

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.gios.lightpods.bt.AapProbe
import com.gios.lightpods.bt.MediaControls
import com.gios.lightpods.bt.PodsConnector
import com.gios.lightpods.data.PodsRepository
import com.gios.lightpods.data.isStale
import com.gios.lightpods.service.PodsService
import com.gios.lightpods.ui.DebugScreen
import com.gios.lightpods.ui.HomeScreen
import com.gios.lightpods.ui.theme.LightPodsTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionsGranted = hasPermissions()
            if (permissionsGranted) {
                PodsRepository.setUiActive(true)
                PodsService.start(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Targeting SDK 35 forces edge to edge, so the theme's bar colours stop
        // applying and the content has to inset itself.
        enableEdgeToEdge()
        permissionsGranted = hasPermissions()

        val media = MediaControls(this)
        val connector = PodsConnector(this)

        setContent {
            LightPodsTheme {
                val scope = rememberCoroutineScope()
                val view by PodsRepository.view.collectAsState()
                val candidates by PodsRepository.candidates.collectAsState()
                val connected by PodsRepository.connected.collectAsState()
                val connectMessage by PodsRepository.connectResult.collectAsState()

                var connecting by remember { mutableStateOf(false) }
                var showDebug by remember { mutableStateOf(false) }
                var probing by remember { mutableStateOf(false) }
                var probeResult by remember { mutableStateOf<String?>(null) }
                var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
                var playing by remember { mutableStateOf(false) }
                var volume by remember { mutableFloatStateOf(0f) }

                // Going out of range, switching Bluetooth off, and pausing playback in
                // another app all produce silence rather than an event, so the screen
                // needs its own heartbeat to notice them.
                LaunchedEffect(Unit) {
                    while (true) {
                        tick = System.currentTimeMillis()
                        playing = media.isPlaying
                        volume = media.volume()
                        delay(2_000)
                    }
                }

                val stale = remember(view, tick) { view?.isStale(tick) == true }
                val bluetoothOn = remember(tick) { isBluetoothOn() }

                if (showDebug) {
                    DebugScreen(
                        view = view,
                        candidates = candidates,
                        probeResult = probeResult,
                        probing = probing,
                        onProbe = {
                            probing = true
                            probeResult = null
                            scope.launch {
                                val adapter = connector.adapter
                                val device = connector.targetDevice()
                                probeResult = when {
                                    adapter == null -> "no Bluetooth adapter"
                                    device == null -> "no bonded audio device to probe"
                                    else -> AapProbe.run(adapter, device).detail
                                }
                                probing = false
                            }
                        },
                        onBack = { showDebug = false },
                    )
                    return@LightPodsTheme
                }

                HomeScreen(
                    modifier = Modifier.safeDrawingPadding(),
                    view = view,
                    stale = stale,
                    bluetoothOn = bluetoothOn,
                    permissionsGranted = permissionsGranted,
                    connected = connected,
                    playing = playing,
                    volume = volume,
                    connecting = connecting,
                    connectMessage = connectMessage,
                    onConnect = {
                        connecting = true
                        PodsRepository.setConnectResult(null)
                        scope.launch {
                            val result = connector.connect()
                            connecting = false
                            when (result) {
                                is PodsConnector.Result.Connected -> {
                                    PodsRepository.setConnected(true)
                                    PodsRepository.setConnectResult(
                                        "${result.device} — ${result.via}",
                                    )
                                }

                                PodsConnector.Result.NoDevice ->
                                    PodsRepository.setConnectResult("Pair your earbuds first")

                                PodsConnector.Result.BluetoothOff ->
                                    PodsRepository.setConnectResult("Bluetooth is off")

                                is PodsConnector.Result.Failed -> {
                                    PodsRepository.setConnectResult("Could not connect")
                                    openBluetoothSettings()
                                }
                            }
                        }
                    },
                    onPlayPause = {
                        media.playPause()
                        playing = !playing
                    },
                    onPrevious = media::previous,
                    onNext = media::next,
                    onVolumeUp = {
                        media.volumeUp()
                        volume = media.volume()
                    },
                    onVolumeDown = {
                        media.volumeDown()
                        volume = media.volume()
                    },
                    onGrantPermissions = { requestPermissions.launch(requiredPermissions()) },
                    onOpenSettings = ::openBluetoothSettings,
                    onDebug = { showDebug = true },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Permissions can also be granted from the system settings page or over ADB,
        // neither of which comes back through the result callback.
        permissionsGranted = hasPermissions()
        PodsRepository.setUiActive(true)
        if (permissionsGranted) PodsService.start(this)
    }

    override fun onStop() {
        super.onStop()
        // Drop the scan to its low-power duty cycle. The service keeps running; it is
        // not restarted from here, because a service start issued once the process is
        // in the background is refused on Android 12 and up.
        PodsRepository.setUiActive(false)
    }

    private fun isBluetoothOn(): Boolean =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.isEnabled == true

    private fun openBluetoothSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            buildList {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermissions(): Boolean = requiredPermissions()
        // The notification permission is nice to have; losing it costs the status
        // notification, not the readout, so it must not gate the whole app.
        .filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}
