package com.electrodig.voidmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.electrodig.voidmusic.detection.color.HsvPreset
import com.electrodig.voidmusic.detection.color.HsvRange
import com.electrodig.voidmusic.ui.theme.color

/**
 * Bottom controls for tuning HSV colour segmentation (PRD F4.1/F4.2, mirrors
 * the web app's `ui/colorControls.js`). Shows the preset row (tap to make a
 * preset active) and six sliders editing the active preset's [HsvRange].
 */
@Composable
fun ColorControls(
    presets: List<HsvPreset>,
    activeIndex: Int,
    onActiveChange: (Int) -> Unit,
    onActiveRangeChange: (HsvRange) -> Unit,
    onPickColor: () -> Unit,
    isPicking: Boolean,
    modifier: Modifier = Modifier
) {
    val active = presets.getOrNull(activeIndex) ?: return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Preset chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(presets, key = { i, p -> "$i-${p.name}" }) { i, preset ->
                    AssistChip(
                        onClick = { onActiveChange(i) },
                        label = { Text("${preset.name} · ${preset.mappedPad.displayName}") },
                        colors = if (i == activeIndex) {
                            AssistChipDefaults.assistChipColors(
                                containerColor = preset.mappedPad.color().copy(alpha = 0.3f),
                                labelColor = MaterialTheme.colorScheme.onSurface
                            )
                        } else AssistChipDefaults.assistChipColors()
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            Button(onClick = onPickColor, modifier = Modifier.fillMaxWidth()) {
                Text(if (isPicking) "请点击取景器中的目标颜色" else "从取景器取色")
            }

            // Sliders for the active preset. H is 0..180, S/V are 0..255.
            HsvSliderRow("H 范围", active.range.hMin, active.range.hMax, 0f..180f) { min, max ->
                onActiveRangeChange(active.range.copy(hMin = min, hMax = max))
            }
            HsvSliderRow("S 范围", active.range.sMin, active.range.sMax, 0f..255f) { min, max ->
                onActiveRangeChange(active.range.copy(sMin = min, sMax = max))
            }
            HsvSliderRow("V 范围", active.range.vMin, active.range.vMax, 0f..255f) { min, max ->
                onActiveRangeChange(active.range.copy(vMin = min, vMax = max))
            }
        }
    }
}

@Composable
private fun HsvSliderRow(
    label: String,
    min: Int,
    max: Int,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Int, Int) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text("$min – $max", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Lower bound
            Slider(
                value = min.toFloat(),
                onValueChange = { onChange(it.toInt().coerceIn(range.start.toInt(), max), max) },
                valueRange = range,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(8.dp))
            // Upper bound
            Slider(
                value = max.toFloat(),
                onValueChange = { onChange(min, it.toInt().coerceIn(min, range.endInclusive.toInt())) },
                valueRange = range,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
