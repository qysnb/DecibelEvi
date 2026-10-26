package com.decibelevidence.app

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class CaptureExitDeviceTest {
    @Test fun leavingStopsMicrophoneBeforePendingOperationFinishes() = runBlocking {
        withViewModel { vm ->
            withContext(Dispatchers.Main) { vm.resumeCamera().join() }
            assertTrue("Fixture must have an active microphone", microphoneRunning(vm))
            val operation = operation(vm)
            operation.lock()
            var ending: Job? = null
            try {
                withContext(Dispatchers.Main) { ending = vm.leaveCapturePage("测试撤回同意") }
                assertFalse("Leaving must stop input without waiting for photo/video I/O", microphoneRunning(vm))
                assertFalse("Fixture must still hold finalization behind the operation", ending!!.isCompleted)
            } finally {
                operation.unlock()
                ending?.join()
            }
        }
    }

    @Test fun queuedSessionCannotStartAfterLeaving() = runBlocking {
        withViewModel { vm ->
            withContext(Dispatchers.Main) { vm.resumeCamera().join() }
            val before = vm.repository.sessions.first().map { it.id }
            val operation = operation(vm)
            operation.lock()
            lateinit var starting: Job
            lateinit var ending: Job
            try {
                withContext(Dispatchers.Main) {
                    starting = vm.startSession()
                    ending = vm.leaveCapturePage()
                }
            } finally { operation.unlock() }
            starting.join(); ending.join()
            assertEquals("A stale queued start must not create a record", before, vm.repository.sessions.first().map { it.id })
            assertFalse(microphoneRunning(vm))
        }
    }

    @Test fun queuedSessionDoesNotBecomeValidWhenCapturePageReopens() = runBlocking {
        withViewModel { vm ->
            withContext(Dispatchers.Main) { vm.resumeCamera().join() }
            val before = vm.repository.sessions.first().map { it.id }
            val operation = operation(vm)
            operation.lock()
            lateinit var starting: Job
            lateinit var ending: Job
            lateinit var reopening: Job
            try {
                withContext(Dispatchers.Main) {
                    starting = vm.startSession()
                    ending = vm.leaveCapturePage()
                    reopening = vm.resumeCamera()
                }
            } finally { operation.unlock() }
            starting.join(); ending.join(); reopening.join()
            assertEquals("Reopening must not revive an earlier queued shutter action", before, vm.repository.sessions.first().map { it.id })
            assertEquals(CaptureMode.PREVIEW, vm.ui.value.mode)
            assertTrue(microphoneRunning(vm))
        }
    }

    @Test fun leavingStopsInputAndStillFinalizesRecordedVideo() = runBlocking {
        withViewModel { vm ->
            withContext(Dispatchers.Main) { vm.resumeCamera().join() }
            delay(2500)
            withContext(Dispatchers.Main) { vm.toggleVideo().join() }
            val session = checkNotNull(vm.ui.value.session)
            val id = session.id
            assertNotNull(vm.ui.value.videoStartNs)
            delay(1300)
            val operation = operation(vm)
            operation.lock()
            lateinit var ending: Job
            lateinit var repeatedEnding: Job
            val stoppedAtNs = android.os.SystemClock.elapsedRealtimeNanos()
            val stoppedAtMs = System.currentTimeMillis()
            try {
                withContext(Dispatchers.Main) { ending = vm.leaveCapturePage("测试撤回同意") }
                assertFalse("Stopping input must not wait for the recording finalizer", microphoneRunning(vm))
                delay(1200)
                withContext(Dispatchers.Main) { repeatedEnding = vm.leaveCapturePage("重复离开") }
            } finally { operation.unlock() }
            ending.join(); repeatedEnding.join()
            val saved = checkNotNull(vm.repository.dao.session(id))
            assertTrue("Finalization wait must not extend recording duration", saved.durationMs <= (stoppedAtNs - session.startNs) / 1_000_000 + 250)
            assertTrue("Repeated leave must preserve the first wall-clock stop boundary", checkNotNull(saved.endedAtMs) <= stoppedAtMs + 250)
            val media = vm.repository.dao.media(id).single()
            assertEquals("The existing muxer must finish after input is closed", "完成", media.status)
            assertTrue(java.io.File(media.path).length() > 0)
            assertNull(vm.ui.value.session)
            assertEquals(CaptureMode.IDLE, vm.ui.value.mode)
        }
    }
    @Test fun previewAttachmentWaitsForPendingFinalizationBeforeReopeningCamera() = runBlocking {
        withViewModel { vm ->
            withContext(Dispatchers.Main) { vm.resumeCamera().join() }
            val operation = operation(vm)
            val texture = android.graphics.SurfaceTexture(false)
            val surface = android.view.Surface(texture)
            operation.lock()
            lateinit var ending: Job
            lateinit var reopening: Job
            try {
                withContext(Dispatchers.Main) {
                    ending = vm.leaveCapturePage()
                    reopening = vm.resumeCamera()
                    vm.attachPreview(surface, 64, 64, 0)
                }
                val wanted = vm.capture.javaClass.getDeclaredField("cameraWanted").apply { isAccessible = true }.getBoolean(vm.capture)
                assertFalse("Attaching a surface must not reopen input before old finalization finishes", wanted)
            } finally {
                operation.unlock()
                withContext(Dispatchers.Main) { vm.leaveCapturePage().join(); vm.detachPreview() }
                surface.release(); texture.release()
            }
            ending.join(); reopening.join()
        }
    }

    private fun operation(vm: EvidenceViewModel): Mutex = EvidenceViewModel::class.java
        .getDeclaredField("operation").apply { isAccessible = true }.get(vm) as Mutex

    private fun microphoneRunning(vm: EvidenceViewModel): Boolean {
        val audio = EvidenceViewModel::class.java.getDeclaredField("audio").apply { isAccessible = true }.get(vm)
        val running = audio.javaClass.getDeclaredField("running").apply { isAccessible = true }.get(audio) as AtomicBoolean
        return running.get()
    }

    private suspend fun withViewModel(block: suspend (EvidenceViewModel) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[EvidenceViewModel::class.java]
        }
        val before = vm.repository.sessions.first().map { it.id }.toSet()
        try { block(vm) }
        finally {
            withContext(Dispatchers.Main) { vm.leaveCapturePage().join() }
            vm.repository.sessions.first().filter { it.id !in before }.forEach { vm.repository.delete(it.id) }
            withContext(Dispatchers.Main) { store.clear() }
        }
    }
}