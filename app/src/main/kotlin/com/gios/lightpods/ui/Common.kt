package com.gios.lightpods.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gios.lightpods.ui.theme.Dim
import com.gios.lightpods.ui.theme.Faint
import com.gios.lightpods.ui.theme.RuleGrey

@Composable
fun Rule(modifier: Modifier = Modifier) =
    HorizontalDivider(modifier = modifier, color = RuleGrey, thickness = 1.dp)

/** Small tracked-out caption, the LightOS section-header idiom. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Faint) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = color)
}

/**
 * Primary action in the LightOS idiom: a hollow rectangle that fills solid while
 * pressed. Greyscale and matte means fills and outlines read; tints do not.
 */
@Composable
fun ActionButton(
    label: String,
    enabled: Boolean = true,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(if (filled) Color.White else Color.Black)
            .border(1.dp, if (enabled) Color.White else RuleGrey)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = when {
                filled -> Color.Black
                enabled -> Color.White
                else -> Faint
            },
        )
    }
}

/**
 * One key in a row of equal-width controls. Text rather than glyphs: the panel is
 * greyscale and matte, Akkurat has no transport symbols, and LightOS labels its own
 * controls in words.
 */
@Composable
fun RowScope.KeyButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(56.dp)
            .border(1.dp, if (enabled) Color.White else RuleGrey)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Color.White else Faint,
            maxLines = 1,
        )
    }
}

/**
 * Battery as a segmented bar. Ten discrete blocks rather than a continuous fill,
 * because the payload only carries deciles — a smooth bar would imply precision the
 * data does not have.
 */
@Composable
fun BatteryMeter(percent: Int?, charging: Boolean, modifier: Modifier = Modifier) {
    val filledBlocks = ((percent ?: 0) + 5) / 10
    Row(modifier.height(10.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(10) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(if (charging) 10.dp else 8.dp)
                    .padding(end = 2.dp)
                    .background(
                        when {
                            percent == null -> RuleGrey
                            i < filledBlocks -> Color.White
                            else -> RuleGrey
                        },
                    ),
            )
        }
    }
}

/** One earbud or the case: label, big figure, meter, state note. */
@Composable
fun PodColumn(
    label: String,
    percent: Int?,
    charging: Boolean,
    note: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Caption(label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                percent?.toString() ?: "--",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
            )
            if (percent != null) {
                Text(
                    "%",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Dim,
                    modifier = Modifier.padding(bottom = 6.dp, start = 2.dp),
                )
            }
        }
        BatteryMeter(percent, charging, Modifier.fillMaxWidth().padding(top = 2.dp))
        Text(
            note ?: if (charging) "Charging" else " ",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
