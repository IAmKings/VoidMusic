package com.electrodig.voidmusic.audio

import com.electrodig.voidmusic.detection.color.DrumPad
import com.electrodig.voidmusic.detection.grid.SequenceState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportTest {

    @Test
    fun `toggle clear and restore publish isolated snapshots`() {
        val harness = Harness()
        try {
            val before = harness.transport.state.value
            harness.transport.toggleStep(1, 3)
            val toggled = harness.transport.state.value

            assertFalse(before.isOn(1, 3))
            assertTrue(toggled.isOn(1, 3))

            val persisted = List(4) { MutableList(16) { false } }
            persisted[2][5] = true
            harness.transport.restore(999, persisted)
            persisted[2][5] = false
            assertEquals(Transport.MAX_BPM, harness.transport.state.value.bpm)
            assertTrue(harness.transport.state.value.isOn(2, 5))

            harness.transport.clear()
            assertFalse(harness.transport.state.value.isOn(2, 5))
            assertEquals(0, harness.transport.state.value.currentStep)
        } finally {
            harness.transport.release()
        }
    }

    @Test
    fun `absolute deadlines do not accumulate tick work`() {
        val harness = Harness(bpm = 137)
        try {
            harness.transport.play()
            repeat(32) {
                val request = harness.sleeper.take()
                assertEquals(Transport.stepDurationNanos(137), request.durationNanos)
                harness.sleeper.resumeAtDeadline(request)
            }
        } finally {
            harness.transport.release()
        }
    }

    @Test
    fun `late wake skips expired steps instead of bursting them`() {
        val triggered = CopyOnWriteArrayList<DrumPad>()
        val grid = List(4) { row ->
            List(16) { step ->
                (row == 0 && step == 0) || (row == 3 && step == 3)
            }
        }
        val harness = Harness(triggered = triggered)
        try {
            harness.transport.restore(120, grid)
            harness.transport.play()
            val firstWait = harness.sleeper.take()
            assertEquals(listOf(DrumPad.KICK), triggered.toList())

            harness.clock.advance(firstWait.durationNanos * 3)
            firstWait.continuation.resume(Unit)
            harness.sleeper.take()

            assertEquals(listOf(DrumPad.KICK, DrumPad.HIHAT), triggered.toList())
        } finally {
            harness.transport.release()
        }
    }

    @Test
    fun `stop resets playhead and released transport cannot restart`() {
        val harness = Harness()
        harness.transport.play()
        harness.sleeper.take()
        harness.transport.stop()

        assertFalse(harness.transport.state.value.isPlaying)
        assertEquals(0, harness.transport.state.value.currentStep)

        harness.transport.release()
        harness.transport.play()
        assertFalse(harness.transport.state.value.isPlaying)
    }

    private class Harness(
        bpm: Int = SequenceState.DEFAULT_BPM,
        triggered: MutableList<DrumPad> = CopyOnWriteArrayList()
    ) {
        val clock = FakeClock()
        val sleeper = ManualSleeper(clock)
        val transport = Transport(
            audioTrigger = AudioTrigger { pad, _ -> triggered += pad },
            clock = clock,
            sleeper = sleeper,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ).also { it.setBpm(bpm) }
    }

    private class FakeClock : MonotonicClock {
        private val now = AtomicLong(0L)
        override fun nowNanos(): Long = now.get()
        fun advance(durationNanos: Long) {
            now.addAndGet(durationNanos)
        }
    }

    private class ManualSleeper(private val clock: FakeClock) : TransportSleeper {
        data class Request(
            val durationNanos: Long,
            val continuation: CancellableContinuation<Unit>
        )

        private val requests = LinkedBlockingQueue<Request>()

        override suspend fun sleepNanos(durationNanos: Long) {
            suspendCancellableCoroutine { continuation ->
                requests.put(Request(durationNanos, continuation))
            }
        }

        fun take(): Request = requests.poll(2, TimeUnit.SECONDS).also {
            assertNotNull("transport did not reach its next deadline", it)
        }!!

        fun resumeAtDeadline(request: Request) {
            clock.advance(request.durationNanos)
            request.continuation.resume(Unit)
        }
    }
}
