package com.decibelevidence.app

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class DirectCaptureDeviceTest {
    @Test fun eachPhotoAndVideoOwnsARecordIncludingPhotoDuringVideo() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[EvidenceViewModel::class.java]
        }
        val before = vm.repository.sessions.first().map { it.id }.toSet()
        try {
            withContext(Dispatchers.Main) { vm.resumeCamera().join() }
            delay(2500)
            withContext(Dispatchers.Main) { vm.photo().join() }
            val photo = vm.repository.sessions.first().single { it.id !in before }
            assertEquals("完成", photo.status)
            assertEquals(0L, photo.durationMs)
            assertNull(vm.ui.value.session)
            assertEquals("photo", vm.repository.dao.media(photo.id).single().kind)
            val metadata = JSONObject(File(vm.repository.directory(photo.id), "metadata.json").readText())
            assertTrue(metadata.has("photoMeasurement"))
            assertTrue(metadata.getJSONObject("photoMeasurement").getLong("csvOriginNs") < photo.startNs)
            withContext(Dispatchers.Main) { vm.toggleVideo().join() }
            val videoId = vm.ui.value.session!!.id
            assertNotNull(vm.ui.value.videoStartNs)
            delay(1500)
            withContext(Dispatchers.Main) { vm.photo().join() }
            assertEquals(videoId, vm.ui.value.session!!.id)
            assertNotNull(vm.ui.value.videoStartNs)
            withContext(Dispatchers.Main) { vm.toggleVideo().join() }
            assertNull(vm.ui.value.session)
            assertEquals(CaptureMode.PREVIEW, vm.ui.value.mode)
            withContext(Dispatchers.Main) { vm.toggleVideo().join() }
            val secondId = vm.ui.value.session!!.id
            assertNotEquals(videoId, secondId)
            delay(1300)
            withContext(Dispatchers.Main) { vm.toggleVideo().join() }
            val created = vm.repository.sessions.first().filter { it.id !in before }
            assertEquals(4, created.size)
            for (row in created) {
                assertEquals("完成", row.status)
                val media = vm.repository.dao.media(row.id)
                assertEquals(1, media.size)
                assertEquals("完成", media.single().status)
            }
        } finally {
            withContext(Dispatchers.Main) { vm.leaveCapturePage().join() }
            vm.repository.sessions.first().filter { it.id !in before }.forEach { vm.repository.delete(it.id) }
            withContext(Dispatchers.Main) { store.clear() }
        }
    }
}
