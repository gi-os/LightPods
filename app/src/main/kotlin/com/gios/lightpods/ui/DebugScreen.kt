package com.gios.lightpods.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gios.lightpods.bt.PodsStatus
import com.gios.lightpods.data.PodsView
import com.gios.lightpods.ui.theme.Dim

/**
 * Reached by long-pressing the model name. Exists because the advertisement is the
 * only thing we can see and reasoning about it second hand is how a pair of AirPods
 * Pro 3 ends up labelled "AirPods 4".
 */
@Composable
fun DebugScreen(
    view: PodsView?,
    candidates: List<PodsStatus>,
    probeResult: String?,
    probing: Boolean,
    onProbe: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Caption("Advertisement")
        Spacer(Modifier.height(12.dp))

        if (view == null) {
            Text("Nothing heard yet.", style = MaterialTheme.typography.bodyMedium, color = Dim)
        } else {
            Mono("model id  0x%04X".format(view.modelId))
            Mono("named     ${view.model ?: "not in table"}")
            Mono("address   ${view.address}")
            Mono("rssi      ${view.rssi} dBm")
            Mono("state     ${view.connection}")
            Mono("left      ${view.left?.percent ?: "--"}  right ${view.right?.percent ?: "--"}  case ${view.case?.percent ?: "--"}")
            Spacer(Modifier.height(8.dp))
            Mono(view.raw.joinToString(" ") { "%02x".format(it) })
        }

        Spacer(Modifier.height(24.dp))
        Rule()
        Spacer(Modifier.height(16.dp))

        Caption("In range (${candidates.size})")
        Spacer(Modifier.height(8.dp))
        candidates.forEach {
            val mark = if (it.address == view?.address) "*" else " "
            Mono("$mark 0x%04X  %4d dBm  %s".format(it.modelId, it.rssi, it.connection))
        }

        Spacer(Modifier.height(24.dp))
        Rule()
        Spacer(Modifier.height(16.dp))

        Caption("AAP probe")
        Spacer(Modifier.height(8.dp))
        Text(
            "Tries to open L2CAP 0x1001, the channel the listening modes live behind. " +
                "Expected to fail on Android 14; the wording says which wall we hit.",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
        )
        Spacer(Modifier.height(12.dp))
        ActionButton(if (probing) "Probing" else "Probe AAP", enabled = !probing, onClick = onProbe)
        probeResult?.let {
            Spacer(Modifier.height(12.dp))
            Mono(it)
        }

        Spacer(Modifier.height(24.dp))
        ActionButton("Back", onClick = onBack)
    }
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        color = Color.White,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
