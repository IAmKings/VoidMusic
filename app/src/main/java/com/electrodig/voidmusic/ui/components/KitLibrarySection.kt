package com.electrodig.voidmusic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.electrodig.voidmusic.audio.KitSummary
import com.electrodig.voidmusic.detection.color.DrumPad
import com.electrodig.voidmusic.session.KitLibraryUiState

@Composable
fun KitLibrarySection(
    state: KitLibraryUiState,
    activeKitId: String?,
    onSelectKit: (String) -> Unit,
    onCopyActiveKit: () -> Unit,
    onRenameKit: (KitSummary) -> Unit,
    onDeleteKit: (KitSummary) -> Unit,
    onImportPad: (String, DrumPad) -> Unit,
    showProgress: Boolean = true,
    modifier: Modifier = Modifier
) {
    val activeKit = state.kits.firstOrNull { it.id == activeKitId }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "选择内置音色，或复制一套后导入自己的 WAV 声音。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.isBusy && showProgress) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "音色操作处理中" }
            )
        }

        state.kits.forEach { kit ->
            val selected = kit.id == activeKitId
            Card(
                onClick = { if (!selected) onSelectKit(kit.id) },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = if (selected) {
                            "${kit.name}，当前音色"
                        } else {
                            "选择音色 ${kit.name}"
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        enabled = !state.isBusy
                    )
                    Column(Modifier.weight(1f)) {
                        Text(kit.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (kit.isBuiltIn) "内置音色" else "自定义音色",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!kit.isBuiltIn) {
                        IconButton(
                            onClick = { onRenameKit(kit) },
                            enabled = !state.isBusy
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "重命名 ${kit.name}")
                        }
                        IconButton(
                            onClick = { onDeleteKit(kit) },
                            enabled = !state.isBusy
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "删除 ${kit.name}")
                        }
                    }
                }
            }
        }

        Button(
            onClick = onCopyActiveKit,
            enabled = !state.isBusy && activeKit != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("复制当前音色")
        }

        if (activeKit != null && !activeKit.isBuiltIn) {
            Text(
                "替换 ${activeKit.name} 的声音",
                style = MaterialTheme.typography.titleSmall
            )
            DrumPad.entries.forEach { pad ->
                OutlinedButton(
                    onClick = { onImportPad(activeKit.id, pad) },
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.LibraryMusic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("导入 ${pad.displayName} WAV")
                }
            }
            Text(
                "支持 PCM WAV；单个文件不超过 10 MiB，最长 5 秒。导入只保存在本机。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
