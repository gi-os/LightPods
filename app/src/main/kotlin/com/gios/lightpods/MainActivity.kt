package com.gios.lightpods

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import com.gios.lightpods.bt.PodsConnector
import com.gios.lightpods.data.PodsRepository
import com.gios.lightpods.data.isStale
import com.gios.lightpods.service.PodsService
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
        permissionsGranted = hasPermissions()

        setContent {
            LightPodsTheme {
                val scope = rememberCoroutineScope()
                val status by PodsRepository.status.collectAsState()
                val connectMessage by PodsRepository.connectResult.collectAsState()
                var connecting by remember { mutableStateOf(false) }
                var tick by remember { mutableStateOf(System.currentTimeMillis()) }

                // Staleness is a function of wall-clock time, not of new data, so the
                // "out of range" line needs its own heartbeat to appear.
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(5_000)
                        tick = System.currentTimeMillis()
                    }
                }

                // Both of these are read off the heartbeat rather than off new data:
                // going out of range and switching Bluetooth off both produce silence,
                // and silence does not recompose anything on its own.
                val stale = remember(status, tick) { status?.isStale(tick) == true }
                val bluetoothOn = remember(tick) {
                    BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
                }

                HomeScreen(
                    status = status,
                    stale = stale,
                    bluetoothOn = bluetoothOn,
                    permissionsGranted = permissionsGranted,
                    connecting = connecting,
                    connectMessage = connectMessage,
                    onConnect = {
                        connecting = true
                        PodsRepository.setConnectResult(null)
                        scope.launch {
                            val result = PodsConnector(this@MainActivity).connect()
                            connecting = false
                            when (result) {
                                is PodsConnector.Result.Connected ->
                                    PodsRepository.setConnectResult("${result.device} — ${result.via}")

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
                    onGrantPermissions = { requestPermissions.launch(requiredPermissions()) },
                    onOpenSettings = ::openBluetoothSettings,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
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
