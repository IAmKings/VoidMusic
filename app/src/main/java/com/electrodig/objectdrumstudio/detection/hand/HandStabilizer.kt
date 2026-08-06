package com.electrodig.objectdrumstudio.detection.hand

/**
 * Smoothing interface for fingertip trajectories (PRD §9.4 HandStabilizer).
 *
 * M1 ships an identity pass-through so the pipeline is wired end-to-end; M3
 * will swap in an EMA / One-Euro filter once hit detection lands and jitter
 * becomes observable in the trigger path.
 */
fun interface HandStabilizer {
    /** Returns a (possibly smoothed) copy of the incoming hands. */
    fun smooth(hands: List<Hand>): List<Hand>
}

/** Default M1 stabiliser: no-op pass-through. */
object IdentityHandStabilizer : HandStabilizer {
    override fun smooth(hands: List<Hand>): List<Hand> = hands
}
