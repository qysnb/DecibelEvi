package com.decibelevidence.app.capture

import android.opengl.EGL14
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EglCleanupDeviceTest {
    @Test fun closeRemainsSafeAfterContextLossAndCanBeRepeated() {
        val renderer = EglRenderer(320, 240)
        val display = EGL14.eglGetCurrentDisplay()
        val context = EGL14.eglGetCurrentContext()
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        renderer.close()
        renderer.close()
        EglRenderer(320, 240).close() // A failed capture must permit another capture attempt.
    }
}
