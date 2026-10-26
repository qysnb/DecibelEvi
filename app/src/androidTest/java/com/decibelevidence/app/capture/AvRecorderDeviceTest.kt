package com.decibelevidence.app.capture

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AvRecorderDeviceTest {
    @Test fun lastVideoFrameDoesNotRepeatADroppedFrameInterval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.cacheDir, "codec-tail-test.mp4")
        val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { candidate ->
            candidate.isEncoder && candidate.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                candidate.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats
                    .contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        val gl = EglRenderer(320, 240)
        val start = SystemClock.elapsedRealtimeNanos()
        val recorder = AvRecorder(output, start, 320, 240, codec.name)
        try {
            gl.attachEncoder(recorder.inputSurface)
            assertFalse("No video should precede valid PCM", recorder.canEncodeFrame(start))
            recorder.addAudio(ShortArray(24_000), start) // Exactly 500ms of valid PCM.
            assertTrue(recorder.canEncodeFrame(start + 500_000_000L))
            assertFalse("An interrupted microphone must bound the video tail", recorder.canEncodeFrame(start + 600_000_000L))
            // A late camera frame after dropped frames must not inherit the preceding 333ms gap.
            for (pts in listOf(0L, 33_333_333L, 66_666_666L, 400_000_000L)) {
                recorder.pump(); gl.encode(pts); recorder.pump()
            }
            gl.detachEncoder()
            recorder.finish().getOrThrow()
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                val video = formats.single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
                val audio = formats.single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
                val videoUs = video.getLong(MediaFormat.KEY_DURATION)
                val audioUs = audio.getLong(MediaFormat.KEY_DURATION)
                assertTrue("Video tail was extended: video=$videoUs audio=$audioUs", abs(videoUs - audioUs) <= 50_000)
            } finally { extractor.release() }
        } finally { recorder.abort(); gl.close(); output.delete() }
    }
}
