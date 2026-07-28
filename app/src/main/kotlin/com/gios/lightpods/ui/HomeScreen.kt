package com.gios.lightpods.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import com.gios.lightpods.data.PodsView
import com.gios.lightpods.data.Reading
import com.gios.lightpods.ui.theme.Dim
import com.gios.lightpods.ui.theme.Faint
import com.gios.lightpods.ui.theme.RuleGrey

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    view: PodsView?,
    stale: Boolean,
    bluetoothOn: Boolean,
    permissionsGranted: Boolean,
    connected: Boolean,
    playing: Boolean,
    volume: Float,
    connecting: Boolean,
    connectMessage: String?,
    onConnect: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onGrantPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onDebug: () -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Box(
            // The debug screen is deliberately unmarked. Long-pressing the title is
            // door enough for the one person who needs it.
            Modifier.combinedClickable(onLongClick = onDebug, onClick = {}),
        ) {
            Caption(view?.modelLabel ?: "Earbuds", color = Dim)
        }
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

                view == null -> Notice("Searching for earbuds.\nOpen the case nearby.")

                else -> Readout(view, stale)
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

        // Connecting is only worth offering while disconnected. Once the earbuds are
        // attached, the useful thing is control of what they are playing — the
        // listening modes would go here if AAP were reachable, and it is not.
        if (connected) {
            Controls(
                playing = playing,
                volume = volume,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onVolumeUp = onVolumeUp,
                onVolumeDown = onVolumeDown,
            )
        } else {
            ActionButton(
                label = if (connecting) "Connecting" else "Connect",
                enabled = permissionsGranted && bluetoothOn && !connecting,
                filled = connecting,
                onClick = onConnect,
            )
        }
    }
}

@Composable
private fun Controls(
    playing: Boolean,
    volume: Float,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
) {
    Column {
        VolumeMeter(volume, Modifier.fillMaxWidth().padding(bottom = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("Prev", onClick = onPrevious)
            KeyButton(if (playing) "Pause" else "Play", onClick = onPlayPause)
            KeyButton("Next", onClick = onNext)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("Vol -", onClick = onVolumeDown)
            KeyButton("Vol +", onClick = onVolumeUp)
        }
    }
}

/** Twelve blocks, same visual language as the battery meter. */
@Composable
private fun VolumeMeter(volume: Float, modifier: Modifier = Modifier) {
    val steps = 12
    val lit = (volume * steps).toInt()
    Row(modifier.height(8.dp)) {
        repeat(steps) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(end = 2.dp)
                    .background(if (i < lit) Color.White else RuleGrey),
            )
        }
    }
}

@Composable
private fun Readout(view: PodsView, stale: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth()) {
            SideColumn("Left", view.left, if (view.leftInEar) "In ear" else null, Modifier.weight(1f))
            Spacer(Modifier.width(24.dp))
            SideColumn("Right", view.right, if (view.rightInEar) "In ear" else null, Modifier.weight(1f))
        }

        Spacer(Modifier.height(28.dp))
        Rule()
        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth()) {
            SideColumn("Case", view.case, if (view.lidOpen) "Open" else "Closed", Modifier.weight(1f))
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Caption("Status")
                Text(
                    view.connection,
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
private fun SideColumn(
    label: String,
    reading: Reading?,
    note: String?,
    modifier: Modifier = Modifier,
) = PodColumn(
    label = label,
    percent = reading?.percent,
    charging = reading?.charging == true,
    note = note,
    modifier = modifier,
)

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
