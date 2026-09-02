package com.electrodig.voidmusic.session

/**
 * The two top-level performance modes (PRD F2.1 / F3.1).
 * - [TAP]: real-time hit mode — tap an object to trigger its drum sound.
 * - [STEP]: AR step sequencer — turn a sheet of paper into a 4×16 loop.
 */
enum class StudioMode(val label: String) {
    TAP("实时击打"),
    STEP("步进序列")
}

/**
 * Top-level session HUD state, mirroring the web app's status bar
 * (模式 / 物件数 / 手部数 / 信号强度). Counts are populated as detection
 * modules come online in later milestones.
 */
data class SessionUiState(
    val mode: StudioMode = StudioMode.TAP,
    val objectCount: Int = 0,
    val handCount: Int = 0,
    val signalStrength: Float = 0f,
    val isCameraReady: Boolean = false,
    /** Analysis pipeline FPS (camera frame processing rate). -1 if not measured. */
    val analysisFps: Float = -1f
)
