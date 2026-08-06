package com.electrodig.objectdrumstudio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown while the CAMERA permission has not been granted (PRD F1.2).
 * If [rationaleNeeded] is true we ask again; otherwise we send the user to
 * system settings (the "don't ask again" case).
 */
@Composable
fun PermissionGate(
    rationaleNeeded: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.VideocamOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(20.dp))
        Text(
            "需要摄像头权限",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "Object Drum Studio 需要使用摄像头来识别桌面物件并追踪你的手部。所有处理都在本地完成，不会上传任何画面。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.size(28.dp))
        Button(onClick = onOpenSettings, modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(if (rationaleNeeded) "重新授权" else "前往系统设置")
        }
    }
}
