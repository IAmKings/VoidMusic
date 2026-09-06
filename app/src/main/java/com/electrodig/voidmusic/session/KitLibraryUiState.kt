package com.electrodig.voidmusic.session

import com.electrodig.voidmusic.audio.KitSummary
import com.electrodig.voidmusic.audio.LibraryError
import com.electrodig.voidmusic.detection.color.DrumPad

enum class KitAction { SELECT, COPY, RENAME, DELETE, IMPORT, PLAYBACK }

data class KitOperation(
    val action: KitAction,
    val kitId: String? = null,
    val pad: DrumPad? = null
)

data class KitActionMessage(
    val id: Long,
    val action: KitAction,
    val success: Boolean,
    val error: LibraryError? = null,
    val pendingCleanupCount: Int = 0
)

data class KitLibraryUiState(
    val kits: List<KitSummary> = emptyList(),
    val operation: KitOperation? = null,
    val message: KitActionMessage? = null
) {
    val isBusy: Boolean get() = operation != null
}
