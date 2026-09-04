package com.electrodig.voidmusic.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.electrodig.voidmusic.audio.BuiltInKits
import com.electrodig.voidmusic.persistence.PerformanceLevel
import com.electrodig.voidmusic.session.SessionViewModel

/**
 * Settings (PRD F8.3). Performance level, haptics, master volume, kit selection
 * and reset-to-default — all persisted via the [SessionViewModel] / DataStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    var resetConfirmationVisible by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ---- Performance level (PRD §4.4 / F8.3) ----
            Section("性能档位") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PerformanceLevel.entries.forEach { level ->
                        FilterChip(
                            selected = settings.performanceLevel == level,
                            onClick = { viewModel.setPerformanceLevel(level) },
                            label = { Text(levelLabel(level)) }
                        )
                    }
                }
            }

            HorizontalDivider()

            // ---- Haptics (PRD F2.5) ----
            SettingRow(
                title = "击打振动反馈",
                subtitle = "命中鼓区时触发轻微振动"
            ) {
                Switch(
                    checked = settings.hapticEnabled,
                    onCheckedChange = viewModel::setHapticEnabled
                )
            }

            HorizontalDivider()

            // ---- Master volume (PRD F6.5) ----
            Section("主音量") {
                Slider(
                    value = settings.masterVolume,
                    onValueChange = viewModel::setMasterVolume,
                    valueRange = 0f..1f
                )
            }

            HorizontalDivider()

            // ---- Kit selection (PRD F6.6 / F7) ----
            Section("音色") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BuiltInKits.all.forEachIndexed { i, kit ->
                        FilterChip(
                            selected = settings.activeKitIndex == i,
                            onClick = { viewModel.setActiveKit(i) },
                            label = { Text(kit.name) }
                        )
                    }
                }
            }

            HorizontalDivider()

            // ---- Hit response (PRD F8.3) ----
            Section("击打识别") {
                Text("击打灵敏度 ${"%.2f".format(settings.hitVelocityThreshold)}")
                Slider(
                    value = settings.hitVelocityThreshold,
                    onValueChange = viewModel::setHitVelocityThreshold,
                    valueRange = 0.2f..2.0f
                )
                Text("击打复位 ${settings.hitCooldownMs} ms")
                Slider(
                    value = settings.hitCooldownMs.toFloat(),
                    onValueChange = { viewModel.setHitCooldownMs(it.toLong()) },
                    valueRange = 50f..500f
                )
                Text("手部平滑 ${"%.1f".format(settings.smoothingMinCutoff)} / ${"%.2f".format(settings.smoothingBeta)}")
                Slider(
                    value = settings.smoothingMinCutoff,
                    onValueChange = { viewModel.setSmoothing(it, settings.smoothingBeta) },
                    valueRange = 1.5f..4.0f
                )
                Slider(
                    value = settings.smoothingBeta,
                    onValueChange = { viewModel.setSmoothing(settings.smoothingMinCutoff, it) },
                    valueRange = 0.02f..0.1f
                )
            }

            HorizontalDivider()

            OutlinedButton(
                onClick = onOpenGuide,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("查看使用指南")
            }

            HorizontalDivider()

            // ---- Reset (PRD F8.2) ----
            OutlinedButton(
                onClick = { resetConfirmationVisible = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("恢复默认设置")
            }

            Spacer(Modifier.padding(8.dp))
            Text(
                "Void Music · 本地优先 · 无网络上传",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (resetConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { resetConfirmationVisible = false },
            title = { Text("恢复默认设置？") },
            text = { Text("这会清除当前的颜色预设、性能、音量、振动和音色选择。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetSettings()
                        resetConfirmationVisible = false
                    }
                ) { Text("恢复默认") }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirmationVisible = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

private fun levelLabel(level: PerformanceLevel): String = when (level) {
    PerformanceLevel.LOW -> "省电"
    PerformanceLevel.MEDIUM -> "平衡"
    PerformanceLevel.HIGH -> "高性能"
}
