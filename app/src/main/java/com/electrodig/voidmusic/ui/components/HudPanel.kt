package com.electrodig.voidmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.electrodig.voidmusic.camera.VisionMetrics
import com.electrodig.voidmusic.session.StudioMode

/**
 * Top HUD overlay mirroring the web app's status bar:
 * 模式 / 物件数 / 手部数 / 信号强度 (PRD §7.1).
 */
@Composable
fun HudPanel(
    mode: StudioMode,
    objectCount: Int,
    handCount: Int,
    signalStrength: Float,
    onModeSelected: (StudioMode) -> Unit,
    modifier: Modifier = Modifier,
    fps: Float = -1f,
    metrics: VisionMetrics = VisionMetrics()
) {
    Box(modifier = modifier.fillMaxWidth()) {
        // Semi-transparent scrim so HUD text stays readable over the camera feed.
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Transparent
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ModeSwitcher(mode, onModeSelected)

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 16.dp)
            ) {
                item { StatChip("物件", objectCount.toString()) }
                item { StatChip("手部", handCount.toString()) }
                item { SignalMeter("触发信号", signalStrength) }
                if (fps >= 0f) {
                    item { StatChip("FPS", "%.0f".format(fps)) }
                }
                if (metrics.handResultFps >= 0f) {
                    item { StatChip("手部 FPS", "%.0f".format(metrics.handResultFps)) }
                }
                PercentileChip("采集→手部", metrics.captureToHandCallbackP50Ms, metrics.captureToHandCallbackP95Ms)
                PercentileChip("回调→消费", metrics.handCallbackToConsumeP50Ms, metrics.handCallbackToConsumeP95Ms)
                PercentileChip("分割", metrics.segmentationP50Ms, metrics.segmentationP95Ms)
                PercentileChip("缓存", metrics.zoneCacheAgeP50Ms, metrics.zoneCacheAgeP95Ms)
                PercentileChip("候选→提交", metrics.hitToAudioSubmitP50Ms, metrics.hitToAudioSubmitP95Ms)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.PercentileChip(
    label: String,
    p50: Long,
    p95: Long
) {
    if (p50 >= 0L && p95 >= 0L) {
        item { StatChip(label, "$p50/$p95 ms") }
    }
}

@Composable
private fun ModeSwitcher(
    selected: StudioMode,
    onSelect: (StudioMode) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        StudioMode.entries.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == selected,
                onClick = { onSelect(m) },
                shape = SegmentedButtonDefaults.itemShape(index, StudioMode.entries.size),
                icon = {
                    Icon(
                        imageVector = if (m == StudioMode.TAP) Icons.Default.TouchApp else Icons.Default.GridView,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                label = { Text(m.label) }
            )
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SignalMeter(label: String, value: Float) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(2.dp))
            LinearProgressIndicator(
                progress = { value },
                modifier = Modifier.width(72.dp)
            )
        }
    }
}
