package com.electrodig.objectdrumstudio.ui.screens

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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.electrodig.objectdrumstudio.audio.BuiltInKits
import com.electrodig.objectdrumstudio.persistence.PerformanceLevel
import com.electrodig.objectdrumstudio.session.SessionViewModel

/**
 * Settings (PRD F8.3). Performance level, haptics, master volume, kit selection
 * and reset-to-default — all persisted via the [SessionViewModel] / DataStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()

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

            // ---- Reset (PRD F8.2) ----
            OutlinedButton(
                onClick = { viewModel.resetSettings() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("恢复默认设置")
            }

            Spacer(Modifier.padding(8.dp))
            Text(
                "Object Drum Studio · 本地优先 · 无网络上传",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
