package com.electrodig.voidmusic.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.electrodig.voidmusic.audio.AudioImportErrorCode
import com.electrodig.voidmusic.audio.LibraryError
import com.electrodig.voidmusic.audio.LibraryErrorCode
import com.electrodig.voidmusic.audio.WavValidationCode
import com.electrodig.voidmusic.detection.color.DrumPad
import com.electrodig.voidmusic.persistence.PerformanceLevel
import com.electrodig.voidmusic.session.KitAction
import com.electrodig.voidmusic.session.KitActionMessage
import com.electrodig.voidmusic.session.SessionViewModel
import com.electrodig.voidmusic.ui.components.KitLibrarySection

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
    viewModel: SessionViewModel
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val kitState by viewModel.kitLibraryState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var resetConfirmationVisible by remember { mutableStateOf(false) }
    var copyName by rememberSaveable { mutableStateOf("") }
    var renameKitId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameKitName by rememberSaveable { mutableStateOf("") }
    var deleteKitId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteKitName by rememberSaveable { mutableStateOf("") }
    var pendingImportKitId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImportPad by rememberSaveable { mutableStateOf<String?>(null) }
    var previewVolume by remember(settings.masterVolume) {
        mutableFloatStateOf(settings.masterVolume)
    }
    var previewVelocity by remember(settings.hitVelocityThreshold) {
        mutableFloatStateOf(settings.hitVelocityThreshold)
    }
    var previewCooldown by remember(settings.hitCooldownMs) {
        mutableFloatStateOf(settings.hitCooldownMs.toFloat())
    }
    var previewMinCutoff by remember(settings.smoothingMinCutoff) {
        mutableFloatStateOf(settings.smoothingMinCutoff)
    }
    var previewBeta by remember(settings.smoothingBeta) {
        mutableFloatStateOf(settings.smoothingBeta)
    }
    val wavPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val kitId = pendingImportKitId
        val pad = pendingImportPad?.let { name -> DrumPad.entries.firstOrNull { it.name == name } }
        pendingImportKitId = null
        pendingImportPad = null
        if (uri != null && kitId != null && pad != null) {
            viewModel.replaceKitPad(kitId, pad, uri)
        }
    }

    LaunchedEffect(kitState.message?.id) {
        val message = kitState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(kitMessageText(message))
        viewModel.clearKitMessage(message.id)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("设置") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
                if (kitState.isBusy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "音色操作处理中" }
                    )
                }
            }
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
                    value = previewVolume,
                    onValueChange = { previewVolume = it },
                    onValueChangeFinished = { viewModel.setMasterVolume(previewVolume) },
                    valueRange = 0f..1f
                )
            }

            HorizontalDivider()

            // ---- Kit selection (PRD F6.6 / F7) ----
            Section("音色") {
                KitLibrarySection(
                    state = kitState,
                    activeKitId = settings.activeKitId,
                    onSelectKit = viewModel::selectKit,
                    onCopyActiveKit = {
                        val activeName = kitState.kits
                            .firstOrNull { it.id == settings.activeKitId }
                            ?.name
                            .orEmpty()
                        copyName = if (activeName.isBlank()) "我的音色" else "$activeName 副本"
                    },
                    onRenameKit = { kit ->
                        renameKitId = kit.id
                        renameKitName = kit.name
                    },
                    onDeleteKit = { kit ->
                        deleteKitId = kit.id
                        deleteKitName = kit.name
                    },
                    onImportPad = { kitId, pad ->
                        pendingImportKitId = kitId
                        pendingImportPad = pad.name
                        wavPicker.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*"))
                    },
                    showProgress = false
                )
            }

            HorizontalDivider()

            // ---- Hit response (PRD F8.3) ----
            Section("击打识别") {
                Text("击打灵敏度 ${"%.2f".format(previewVelocity)}")
                Slider(
                    value = previewVelocity,
                    onValueChange = { previewVelocity = it },
                    onValueChangeFinished = {
                        viewModel.setHitVelocityThreshold(previewVelocity)
                    },
                    valueRange = 0.2f..2.0f
                )
                Text("击打复位 ${previewCooldown.toLong()} ms")
                Slider(
                    value = previewCooldown,
                    onValueChange = { previewCooldown = it },
                    onValueChangeFinished = {
                        viewModel.setHitCooldownMs(previewCooldown.toLong())
                    },
                    valueRange = 50f..500f
                )
                Text("手部平滑 ${"%.1f".format(previewMinCutoff)} / ${"%.2f".format(previewBeta)}")
                Slider(
                    value = previewMinCutoff,
                    onValueChange = { previewMinCutoff = it },
                    onValueChangeFinished = {
                        viewModel.setSmoothing(previewMinCutoff, previewBeta)
                    },
                    valueRange = 1.5f..4.0f
                )
                Slider(
                    value = previewBeta,
                    onValueChange = { previewBeta = it },
                    onValueChangeFinished = {
                        viewModel.setSmoothing(previewMinCutoff, previewBeta)
                    },
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

    if (copyName.isNotEmpty()) {
        NameDialog(
            title = "复制当前音色",
            confirmLabel = "复制",
            value = copyName,
            onValueChange = { copyName = it },
            onDismiss = { copyName = "" },
            onConfirm = {
                viewModel.copyActiveKit(copyName)
                copyName = ""
            }
        )
    }

    renameKitId?.let { kitId ->
        NameDialog(
            title = "重命名音色",
            confirmLabel = "保存",
            value = renameKitName,
            onValueChange = { renameKitName = it },
            onDismiss = {
                renameKitId = null
                renameKitName = ""
            },
            onConfirm = {
                viewModel.renameKit(kitId, renameKitName)
                renameKitId = null
                renameKitName = ""
            }
        )
    }

    deleteKitId?.let { kitId ->
        AlertDialog(
            onDismissRequest = {
                deleteKitId = null
                deleteKitName = ""
            },
            title = { Text("删除音色？") },
            text = { Text("将永久删除“$deleteKitName”及未被其他音色使用的本地声音文件。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteKit(kitId)
                    deleteKitId = null
                    deleteKitName = ""
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleteKitId = null
                    deleteKitName = ""
                }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                label = { Text("音色名称") }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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

private fun kitMessageText(message: KitActionMessage): String {
    if (!message.success) return message.error?.toUserMessage() ?: "音色操作失败，请重试"
    val success = when (message.action) {
        KitAction.SELECT -> "音色已切换"
        KitAction.COPY -> "音色副本已创建并选中"
        KitAction.RENAME -> "音色已重命名"
        KitAction.DELETE -> "音色已删除"
        KitAction.IMPORT -> "声音已导入并可立即演奏"
        KitAction.PLAYBACK -> "音色已加载"
    }
    return if (message.pendingCleanupCount > 0) {
        "$success；有 ${message.pendingCleanupCount} 个旧文件将在下次启动时清理"
    } else {
        success
    }
}

private fun LibraryError.toUserMessage(): String = when (code) {
    LibraryErrorCode.INVALID_NAME -> "名称不能为空，且不能超过 40 个字符"
    LibraryErrorCode.KIT_NOT_FOUND -> "找不到该音色，请刷新后重试"
    LibraryErrorCode.BUILT_IN_IMMUTABLE -> "内置音色不能修改，请先复制一套"
    LibraryErrorCode.INCOMPLETE_KIT -> "该音色文件不完整，请重新复制或删除"
    LibraryErrorCode.SOURCE_UNAVAILABLE -> "源音色文件不可用，请选择其他音色"
    LibraryErrorCode.STORAGE_FAILURE -> "本地存储失败，请检查剩余空间后重试"
    LibraryErrorCode.DATABASE_FAILURE -> "音色资料保存失败，请重试"
    LibraryErrorCode.PLAYBACK_FAILURE -> "音色播放启动失败，已恢复上一套音色"
    LibraryErrorCode.IMPORT_FAILED -> importFailureMessage()
}

private fun LibraryError.importFailureMessage(): String = when (importCode) {
    AudioImportErrorCode.TOO_LARGE -> "文件超过 10 MiB，请选择更小的 WAV"
    AudioImportErrorCode.IO_FAILURE -> "无法读取该文件，请重新选择"
    AudioImportErrorCode.INVALID_WAV -> when (wavValidationCode) {
        WavValidationCode.TOO_LONG -> "声音超过 5 秒，请裁短后重试"
        WavValidationCode.UNSUPPORTED_ENCODING -> "仅支持 PCM 编码的 WAV 文件"
        WavValidationCode.UNSUPPORTED_CHANNELS -> "仅支持单声道或双声道 WAV"
        WavValidationCode.UNSUPPORTED_BITS -> "仅支持 16 位 PCM WAV"
        WavValidationCode.UNSUPPORTED_SAMPLE_RATE -> "该 WAV 采样率不受支持"
        WavValidationCode.EMPTY_DATA -> "WAV 中没有可播放的声音数据"
        else -> "WAV 文件结构无效或已损坏"
    }
    null -> "WAV 导入失败，请检查文件后重试"
}
