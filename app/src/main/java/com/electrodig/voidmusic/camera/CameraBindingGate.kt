package com.electrodig.voidmusic.camera

/**
 * Owns the generation of asynchronous CameraX binding requests.
 *
 * A newer request, a preview stop, or a final close invalidates every older
 * callback. Closing is terminal: no later request can become current.
 */
internal class CameraBindingGate {
    private var generation = 0L
    private var closed = false

    @Synchronized
    fun beginRequest(): Long? {
        if (closed) return null
        generation += 1
        return generation
    }

    @Synchronized
    fun isCurrent(request: Long): Boolean = !closed && request == generation

    @Synchronized
    fun cancelPending() {
        generation += 1
    }

    /** Returns true only for the first transition to the terminal state. */
    @Synchronized
    fun close(): Boolean {
        if (closed) return false
        closed = true
        generation += 1
        return true
    }
}
