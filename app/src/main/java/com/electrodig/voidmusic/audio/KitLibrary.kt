package com.electrodig.voidmusic.audio

import kotlinx.coroutines.flow.Flow

/** Stable, storage-agnostic item rendered by kit selection UI. */
data class KitSummary(
    val id: String,
    val name: String,
    val isBuiltIn: Boolean
)

/** Business boundary for the merged built-in and imported kit catalogue. */
interface KitLibrary {
    val kits: Flow<List<KitSummary>>
}
