package com.electrodig.voidmusic.detection.hand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimestampedHandsQueueTest {
    @Test
    fun `drain preserves every pending frame and its source timestamp`() {
        val queue = TimestampedHandsQueue(capacity = 3)
        queue.offer(TimestampedHands(101, emptyList()))
        queue.offer(TimestampedHands(134, emptyList()))

        assertEquals(listOf(101L, 134L), queue.drain().map(TimestampedHands::timestampMs))
        assertTrue(queue.drain().isEmpty())
    }

    @Test
    fun `overflow drops the oldest result to bound latency`() {
        val queue = TimestampedHandsQueue(capacity = 2)
        queue.offer(TimestampedHands(10, emptyList()))
        queue.offer(TimestampedHands(20, emptyList()))
        queue.offer(TimestampedHands(30, emptyList()))

        assertEquals(listOf(20L, 30L), queue.drain().map(TimestampedHands::timestampMs))
    }
}
