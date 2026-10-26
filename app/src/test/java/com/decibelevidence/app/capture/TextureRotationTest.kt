package com.decibelevidence.app.capture
import org.junit.Assert.assertEquals
import org.junit.Test

class TextureRotationTest {
    @Test fun ordinaryFlipStillNeedsSensorRotation() {
        val matrix = floatArrayOf(1f,0f,0f,0f, 0f,-1f,0f,0f, 0f,0f,1f,0f, 0f,1f,0f,1f)
        assertEquals(90, additionalTextureRotation(matrix,90))
    }
    @Test fun producerRotationIsNotAppliedTwice() {
        // Actual first SurfaceTexture matrix logged on the API35 virtual scene camera.
        val matrix = floatArrayOf(0f,-1f,0f,0f, -1f,0f,0f,0f, 0f,0f,1f,0f, 1f,1f,0f,1f)
        assertEquals(0, additionalTextureRotation(matrix,90))
        assertEquals(270, additionalTextureRotation(matrix,0))
    }
}
