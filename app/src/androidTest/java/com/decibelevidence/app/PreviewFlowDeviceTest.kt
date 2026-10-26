package com.decibelevidence.app

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.decibelevidence.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import android.net.Uri

@RunWith(AndroidJUnit4::class)
class PreviewFlowDeviceTest {
    @Test fun galleryCopyAndFormalCaptureCannotOverlap() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[EvidenceViewModel::class.java]
        }
        val row = EvidenceSession("gallery_admission_${UUID.randomUUID()}", 1L, 1L, status = "完成")
        val mutex = EvidenceRepository::class.java.getDeclaredField("storageMutex").apply { isAccessible = true }.get(null) as Mutex
        var locked = false
        var copying: Job? = null
        var galleryUri: String? = null
        try {
            withContext(Dispatchers.Main) { vm.resumeCamera().join() }
            vm.repository.create(row)
            val file = File(vm.repository.directory(row.id), "test.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val media = MediaArtifact(UUID.randomUUID().toString(), row.id, "photo", file.path, 1L)
            vm.repository.addMedia(media); vm.repository.completeMedia(media, true)
            // Hold the same I/O mutex as a slow large copy, without creating a huge fixture.
            mutex.lock(); locked = true
            withContext(Dispatchers.Main) { copying = vm.saveGallery(row.id) }
            withTimeout(2000) { vm.galleryUi.first { it.running && it.total == 1 } }
            withTimeout(2000) { withContext(Dispatchers.Main) { vm.startSession().join() } }
            assertNull("Capture must reject admission instead of queuing behind gallery I/O", vm.ui.value.session)
            assertEquals(CaptureMode.PREVIEW, vm.ui.value.mode)
            mutex.unlock(); locked = false
            copying!!.join()
            galleryUri = vm.repository.dao.media(row.id).single().galleryUri
            assertNotNull(galleryUri)
        } finally {
            if (locked) mutex.unlock()
            copying?.join()
            galleryUri = galleryUri ?: vm.repository.dao.media(row.id).firstOrNull()?.galleryUri
            galleryUri?.let { app.contentResolver.delete(Uri.parse(it), null, null) }
            withContext(Dispatchers.Main) { vm.leaveCapturePage().join() }
            vm.repository.delete(row.id)
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    @Test fun previewMeasuresWithoutRecordAndSessionStartsFresh() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[EvidenceViewModel::class.java]
        }
        var id: String? = null
        try {
            val before = vm.repository.sessions.first().map { it.id }
            withContext(Dispatchers.Main) { vm.resumeCamera().join() }
            delay(2500)
            assertNotNull("Entering capture must measure before starting evidence", vm.ui.value.measurement?.currentDb)
            assertNull(vm.ui.value.session)
            assertEquals(before, vm.repository.sessions.first().map { it.id })
            withContext(Dispatchers.Main) { vm.startSession().join() }
            id = vm.ui.value.session?.id
            assertNotNull(id)
            assertNull("Preview values must not enter new session", vm.ui.value.measurement)
            withContext(Dispatchers.Main) { vm.saveGallery(id!!).join() }
            assertFalse("Gallery copy must be refused during evidence", vm.galleryUi.value.running)
            assertTrue(vm.ui.value.message!!.contains("当前取证结束"))
            delay(1300)
            assertTrue(vm.ui.value.measurement!!.curve.size <= 2)
            withContext(Dispatchers.Main) { vm.endSession().join() }
            assertEquals(CaptureMode.PREVIEW, vm.ui.value.mode)
            assertNull(vm.ui.value.session)
            assertNull("Preview waits for its next measurement refresh", vm.ui.value.measurement)
            delay(1300)
            assertNotNull(vm.ui.value.measurement?.currentDb)
            assertTrue("Photo preview retains the recent window across video boundaries", vm.ui.value.measurement!!.curve.size > 2)
            withContext(Dispatchers.Main) { vm.leaveCapturePage().join() }
            assertEquals(CaptureMode.IDLE, vm.ui.value.mode)
            assertNull(vm.ui.value.measurement)
            assertFalse(vm.ui.value.busy)
            val saved = vm.repository.sessions.first().map { it.id }
            withContext(Dispatchers.Main) { vm.resumeCamera().join() }
            delay(1300)
            assertNotNull(vm.ui.value.measurement?.currentDb)
            assertEquals(saved, vm.repository.sessions.first().map { it.id })
        } finally {
            withContext(Dispatchers.Main) { vm.endSession(stopCamera = true).join() }
            id?.let { vm.repository.delete(it) }
            withContext(Dispatchers.Main) { store.clear() }
        }
    }
}
