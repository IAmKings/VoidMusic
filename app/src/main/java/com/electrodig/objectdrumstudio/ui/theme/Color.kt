package com.electrodig.objectdrumstudio.ui.theme

import androidx.compose.ui.graphics.Color
import com.electrodig.objectdrumstudio.detection.color.DrumPad

// Brand palette — a dark, high-contrast scheme suited to a camera viewfinder overlay
// (PRD §4.5 accessibility: high contrast, colour-blind friendly accents).
val Amber = Color(0xFFFF6B35)
val Cyan = Color(0xFF00D9FF)
val Lime = Color(0xFFA8FF60)

val BackgroundDark = Color(0xFF101018)
val SurfaceDark = Color(0xFF1A1A2E)
val SurfaceVariantDark = Color(0xFF262640)
val OnSurfaceDark = Color(0xFFF2F2F7)
val OnSurfaceMuted = Color(0xFF9A9AB0)

// Accent per drum pad (maps to PRD §8.1 DrumPad), used for zone tinting.
// Colours are chosen so each pad's accent MATCHES the real-world object colour
// that maps to it (Red→KICK, Blue→SNARE, Green→CLAP, Yellow→TOM). This keeps
// the overlay intuitive: a green box means a green object was detected.
val PadKick = Color(0xFFFF4D6D)   // red
val PadSnare = Cyan               // blue
val PadClap = Lime                 // green
val PadTom = Color(0xFFFFD23F)     // yellow
val PadHihat = Color(0xFFFF6B35)  // amber

/** Resolve a [DrumPad] to its accent colour for overlay rendering. */
fun DrumPad.color(): Color = when (this) {
    DrumPad.KICK -> PadKick
    DrumPad.SNARE -> PadSnare
    DrumPad.CLAP -> PadClap
    DrumPad.TOM -> PadTom
    DrumPad.HIHAT -> PadHihat
}
