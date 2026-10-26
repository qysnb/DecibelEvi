package com.decibelevidence.app

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SessionFailureDeviceTest {
    @Test fun failedFinalCsvWriteStillReturnsToIdle() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[EvidenceViewModel::class.java]
        }
        var id: String? = null
        var csv: File? = null
        var saved = ""
        try {
            withContext(Dispatchers.Main) { vm.resumeCamera().join(); vm.startSession().join() }
            id = vm.ui.value.session?.id
            assertNotNull("Test needs a running microphone session: ${vm.ui.value.message}", id)
            delay(1300)
            csv = File(vm.repository.directory(id!!), "measurements.csv")
            saved = csv.readText()
            // Make the next persisted complete second fail deterministically.
            assertTrue(csv.delete()); assertTrue(csv.mkdir())
            delay(1200)
            withContext(Dispatchers.Main) { vm.endSession().join() }
            assertNull("A storage failure must release the active session", vm.ui.value.session)
            assertFalse(vm.ui.value.busy)
        } finally {
            csv?.let { if (it.isDirectory) it.delete(); it.writeText(saved) }
            withContext(Dispatchers.Main) { vm.endSession().join() }
            id?.let { key -> vm.repository.dao.session(key)?.let { vm.repository.finish(it.copy(status = "中断")) }; vm.repository.delete(key) }
            withContext(Dispatchers.Main) { store.clear() }
        }
    }
}
