package com.decibelevidence.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureClockTest {
    @Test fun realtimeCameraPreservesTheSharedAudioClock() {
        val clock = CaptureClock(true)
        assertEquals(5_000_000_000L,clock.map(5_000_000_000L,5_030_000_000L))
        assertEquals(5_033_333_333L,clock.map(5_033_333_333L,5_090_000_000L))
    }
    @Test fun unknownCameraAnchorsOnceWithoutConvertingArrivalJitterToMediaJitter() {
        val clock = CaptureClock(false)
        assertEquals(8_000_000_000L,clock.map(1_000_000_000L,8_000_000_000L))
        assertEquals(8_033_333_333L,clock.map(1_033_333_333L,8_110_000_000L))
    }
    @Test fun repeatedOrBackwardCameraTimestampsNeverReverseTheMediaTimeline() {
        val clock = CaptureClock(true)
        val first = clock.map(1000,2000)
        val second = clock.map(1000,2100)
        val third = clock.map(900,2200)
        assertTrue(first < second && second < third)
    }
    @Test fun coldStartupQueueMustNotProjectFramesIntoTheFuture() {
        val clock = CaptureClock(false)
        assertEquals(8_000_000_000L, clock.map(1_000_000_000L,8_000_000_000L))
        val caughtUp = clock.map(2_000_000_000L,8_500_000_000L)
        assertTrue(caughtUp <= 8_500_000_000L)
        assertTrue(caughtUp > 8_000_000_000L)
        assertEquals(8_533_333_333L, clock.map(2_033_333_333L,8_700_000_000L))
    }
}
