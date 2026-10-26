package com.decibelevidence.app.capture

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.decibelevidence.app.model.OverlayState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayPreviewDeviceTest {
    @Test fun noSessionStillDrawsWatermarkPanelInBothOrientations() {
        listOf(720 to 1280, 1280 to 720).forEach { (width, height) ->
            val painter = OverlayPainter(width, height)
            try {
                painter.update(OverlayState(wallTimeMs = 0), 1_000_000_000)
                assertTrue("Preview needs actual composed pixels", Color.alpha(painter.bitmap.getPixel(0, 0)) > 0)
            } finally { painter.close() }
        }
    }
}
