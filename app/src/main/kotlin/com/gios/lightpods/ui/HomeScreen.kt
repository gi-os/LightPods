package com.gios.lightpods.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.lightpods.bt.PodsStatus
import com.gios.lightpods.ui.theme.Dim
import com.gios.lightpods.ui.theme.Faint

@Composable
fun HomeScreen(
    status: PodsStatus?,
    stale: Boolean,
    bluetoothOn: Boolean,
    permissionsGranted: Boolean,
    connecting: Boolean,
    connectMessage: String?,
    onConnect: () -> Unit,
    onGrantPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Caption(status?.model ?: "Earbuds", color = Dim)
        Spacer(Modifier.height(20.dp))

        Box(Modifier.weight(1f)) {
            when {
                !permissionsGranted -> Notice(
                    "Bluetooth permission is needed to read battery levels.",
                    action = "Grant",
                    onAction = onGrantPermissions,
                )

                !bluetoothOn -> Notice(
                    "Bluetooth is off.",
                    action = "Open settings",
                    onAction = onOpenSettings,
                )

                status == null -> Notice("Searching for earbuds.\nOpen the case nearby.")

                else -> Readout(status, stale)
            }
        }

        connectMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }

        ActionButton(
            label = if (connecting) "Connecting" else "Connect",
            enabled = permissionsGranted && bluetoothOn && !connecting,
            filled = connecting,
            onClick = onConnect,
        )
    }
}

@Composable
private fun Readout(status: PodsStatus, stale: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth()) {
            PodColumn(
                label = "Left",
                percent = status.leftBattery,
                charging = status.leftCharging,
                note = if (status.leftInEar) "In ear" else null,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(24.dp))
            PodColumn(
                label = "Right",
                percent = status.rightBattery,
                charging = status.rightCharging,
                note = if (status.rightInEar) "In ear" else null,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(28.dp))
        Rule()
        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth()) {
            PodColumn(
                label = "Case",
                percent = status.caseBattery,
                charging = status.caseCharging,
                note = if (status.lidOpen) "Open" else "Closed",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Caption("Status")
                Text(
                    status.connection,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    if (stale) "Out of range" else "Live",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (stale) Faint else Dim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Notice(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = Dim,
            textAlign = TextAlign.Center,
        )
        if (action != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            ActionButton(action, onClick = onAction)
        }
    }
}
