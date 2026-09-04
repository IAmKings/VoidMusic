package com.electrodig.voidmusic.persistence

import com.electrodig.voidmusic.detection.color.DetectionConfig
import kotlinx.serialization.Serializable

const val CURRENT_HIT_TUNING_VERSION = 1

/**
 * Performance tiers (PRD §4.4 / F8.3). Lower tiers drop the analysis resolution
 * / frame rate to keep low-end devices smooth and cool.
 */
@Serializable
enum class PerformanceLevel { LOW, MEDIUM, HIGH }

/**
 * All persisted user settings (PRD F8). Stored as a single JSON blob in DataStore
 * Preferences — small enough that coarse-grained persistence is simplest and
 * avoids a large key surface.
 */
@Serializable
data class Settings(
    val detectionConfig: DetectionConfig = DetectionConfig.DEFAULT,
    val performanceLevel: PerformanceLevel = PerformanceLevel.MEDIUM,
    val hapticEnabled: Boolean = true,
    val masterVolume: Float = 0.9f,
    /** Last-used BPM so the sequencer resumes where the user left it. */
    val lastBpm: Int = 110,
    /** Index of the active built-in kit (PRD F6.6 / F7). */
    val activeKitIndex: Int = 0,
    /** Minimum downward fingertip speed in normalized display units per second. */
    val hitVelocityThreshold: Float = 0.5f,
    /** Minimum delay between candidates from one fingertip. */
    val hitCooldownMs: Long = 60L,
    /** Missing in legacy JSON; lets old defaults migrate without repeating. */
    val hitTuningVersion: Int = 0,
    /** One-Euro filter parameters used by the overlay only. */
    val smoothingMinCutoff: Float = 3.0f,
    val smoothingBeta: Float = 0.07f,
    /** Session snapshot fields use DTOs so persistence does not depend on UI objects. */
    val lastMode: String = "TAP",
    val sequenceGrid: List<List<Boolean>> = defaultSequenceGrid(),
    val calibration: List<CalibrationPoint> = emptyList()
) {
    fun withCurrentHitTuning(): Settings =
        if (hitTuningVersion >= CURRENT_HIT_TUNING_VERSION) this
        else {
            val usesLegacyDefaults = hitVelocityThreshold == 0.6f &&
                (hitCooldownMs == 110L || hitCooldownMs == 250L)
            copy(
                hitVelocityThreshold = if (usesLegacyDefaults) 0.5f else hitVelocityThreshold,
                hitCooldownMs = if (usesLegacyDefaults) 60L else hitCooldownMs,
                hitTuningVersion = CURRENT_HIT_TUNING_VERSION
            )
        }

    companion object {
        val DEFAULT = Settings(hitTuningVersion = CURRENT_HIT_TUNING_VERSION)

        private fun defaultSequenceGrid(): List<List<Boolean>> =
            List(4) { List(16) { false } }
    }
}

@Serializable
data class CalibrationPoint(val x: Float, val y: Float)
