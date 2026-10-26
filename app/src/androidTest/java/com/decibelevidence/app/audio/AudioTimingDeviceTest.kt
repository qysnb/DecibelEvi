package com.decibelevidence.app.audio

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.decibelevidence.core.MeasurementEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class AudioTimingDeviceTest {
    @Test fun rawInputRateAndDspBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val errors = mutableListOf<String>()
        val audio = AudioSampler(context) { synchronized(errors) { errors.add(it) } }
        val ready = CountDownLatch(1)
        var total = 0L
        val input = audio.prepare()
        val began = SystemClock.elapsedRealtimeNanos()
        audio.start { _, count, _ -> total += count; if (total >= 96000) ready.countDown() }
        try {
            assertTrue("Capture did not deliver two seconds within 10s", ready.await(10, TimeUnit.SECONDS))
            val elapsed = (SystemClock.elapsedRealtimeNanos() - began) / 1e9
            Log.i("EvidenceTimingTest", "rawCaptureFrames=$total wallSeconds=$elapsed input=${input.key}")
            assertTrue("Capture clock mismatch: two seconds required $elapsed seconds", elapsed < 3.0)
        } finally { audio.stop() }
        val engine = MeasurementEngine(startNs = 0, startWallMs = 0)
        val block = ShortArray(2400) { (sin(2 * PI * 1000 * it / 48000) * 10000).toInt().toShort() }
        val analysisStart = SystemClock.elapsedRealtimeNanos()
        repeat(100) { index -> engine.process(block, block.size, index * 50_000_000L) }
        val analysisSeconds = (SystemClock.elapsedRealtimeNanos() - analysisStart) / 1e9
        Log.i("EvidenceTimingTest", "fiveSecondsDspWall=$analysisSeconds")
        assertTrue("DSP cannot keep up: five seconds took $analysisSeconds", analysisSeconds < 2.5)
    }
}
