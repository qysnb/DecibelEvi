package com.decibelevidence.app.audio
import org.junit.Assert.*
import org.junit.Test
class AudioTimingTest {
    @Test fun ordinaryBufferLatencyIsAccepted() { assertNull(audioTimingError(1_000_000_000L,1_050_000_000L)) }
    @Test fun oldCaptureCannotSilentlyContinueAsLiveAudio() { assertNotNull(audioTimingError(1_000_000_000L,2_100_000_000L)) }
    @Test fun invalidFutureClockIsRejected() { assertNotNull(audioTimingError(2_000_000_000L,1_000_000_000L)) }
}
