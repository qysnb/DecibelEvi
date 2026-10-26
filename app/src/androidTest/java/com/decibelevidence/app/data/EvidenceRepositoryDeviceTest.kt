package com.decibelevidence.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.TimeZone
import java.util.zip.ZipFile

/** Runs against actual Android SQLite/Room, not the host JVM's Android stubs. */
@RunWith(AndroidJUnit4::class)
class EvidenceRepositoryDeviceTest {
    private suspend fun withRecord(block: suspend (EvidenceRepository, EvidenceSession) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = EvidenceRepository(context)
        val row = EvidenceSession("review_test_${UUID.randomUUID()}", 10_000, 1_000_000)
        repo.create(row)
        try { block(repo, row) } finally {
            if (repo.dao.session(row.id) != null) {
            File(repo.directory(row.id), "events.jsonl").writeText("")
            File(repo.directory(row.id), "locations.jsonl").writeText("")
            repo.finish(row.copy(status = "中断"))
            repo.dao.media(row.id).filter { it.status == "写入中" }.forEach { repo.dao.saveMedia(it.copy(status = "中断")) }
            repo.delete(row.id)
            }
        }
    }

    @Test fun truncatedCsvTailIsPreservedAndRemovedFromRecoveredTable() = runBlocking {
        withRecord { repo, row ->
            val csv = File(repo.directory(row.id), "measurements.csv")
            csv.appendText("0,1970-01-01T00:00:10Z,-83,-82,-83,-82,false,true\n1,1970-01-01T00:")
            repo.recover()
            assertEquals(2, csv.readLines().size)
            assertTrue(File(csv.path + ".interrupted").readText().endsWith("1,1970-01-01T00:"))
            ZipFile(repo.export(row.id)).use { assertNotNull(it.getEntry("measurements.csv.interrupted")) }
        }
    }

    @Test fun concurrentExportsRemainCompleteAndMediaChangesAreRejected() = runBlocking {
        withRecord { repo, row ->
            val file = File(repo.directory(row.id), "committed.jpg").apply { writeText("committed") }
            val media = MediaArtifact(UUID.randomUUID().toString(), row.id, "photo", file.path, row.startedAtMs)
            repo.addMedia(media); repo.completeMedia(media, true); repo.finish(row.copy(status = "完成"))
            val outputs = coroutineScope { List(4) { async { repo.export(row.id) } }.awaitAll() }
            outputs.forEach { output -> ZipFile(output).use { assertNotNull(it.getEntry("SHA256SUMS.txt")); assertEquals("committed", it.getInputStream(it.getEntry(file.name)).reader().readText()) } }
            file.writeText("changed after commit")
            assertTrue(runCatching { repo.export(row.id) }.isFailure)
        }
    }

    @Test fun failedRepairPreservesSourceAndExistingOriginalBackup() = runBlocking {
        withRecord { repo, row ->
            val journal = File(repo.directory(row.id), "events.jsonl")
            val original = "{\"message\":\"saved\"}\n{\"message\":"
            journal.writeText(original)
            val backup = File(journal.path + ".interrupted").apply { writeText("earliest original backup") }
            val blockedPart = File(journal.path + ".repair.part").apply { mkdir() }
            assertTrue(runCatching { repo.recover() }.isFailure)
            assertEquals(original, journal.readText())
            assertEquals("earliest original backup", backup.readText())
            assertTrue(blockedPart.delete())
            repo.recover()
            assertEquals("中断", repo.dao.session(row.id)!!.status)
            assertEquals("earliest original backup", backup.readText())
            assertTrue(journal.readLines().first().contains("saved"))
        }
    }

    @Test fun truncatedJournalIsRecoveredAndCaptureEndIsNotRecoveryTime() = runBlocking {
        withRecord { repo, row ->
            File(repo.directory(row.id), "locations.jsonl").writeText("{\"latitude\":31.2}\n{\"latitude\":")
            File(repo.directory(row.id), "events.jsonl").writeText("{\"message\":\"saved\"}\n{\"message\":")
            repo.checkpoint(row.copy(durationMs = 5000))
            val checkpointEnd = repo.dao.session(row.id)!!.endedAtMs
            repo.recover()
            val recovered = repo.dao.session(row.id)!!
            assertEquals("中断", recovered.status)
            assertEquals("Recovery must use the persisted boundary", checkpointEnd, recovered.endedAtMs)
            val metadata = org.json.JSONObject(File(repo.directory(row.id), "metadata.json").readText())
            assertEquals(1, metadata.getJSONArray("locations").length())
            assertTrue(metadata.getJSONArray("events").length() >= 2)
            assertTrue(repo.export(row.id).isFile)
        }
    }

    @Test fun exportRejectsPendingPhotoWithoutDeletingItsFile() = runBlocking {
        withRecord { repo, row ->
            val photo = File(repo.directory(row.id), "pending.jpg").apply { writeText("pending") }
            repo.addMedia(MediaArtifact(UUID.randomUUID().toString(), row.id, "photo", photo.path, row.startedAtMs))
            repo.finish(row.copy(status = "中断"))
            assertTrue("Outstanding writes must not be exported", runCatching { repo.export(row.id) }.isFailure)
            assertEquals("pending", photo.readText())
        }
    }

    @Test fun exportIncludesOnlyCommittedMediaWithoutDeletingOtherFiles() = runBlocking {
        withRecord { repo, row ->
            val photo = File(repo.directory(row.id), "failed.jpg").apply { writeText("failed") }
            repo.addMedia(MediaArtifact(UUID.randomUUID().toString(), row.id, "photo", photo.path, row.startedAtMs, status = "失败"))
            File(repo.directory(row.id), "orphan.jpg").writeText("not committed")
            repo.finish(row.copy(status = "中断"))
            ZipFile(repo.export(row.id)).use {
                assertNull(it.getEntry("failed.jpg"))
                assertNull(it.getEntry("orphan.jpg"))
            }
            assertEquals("failed", photo.readText())
        }
    }

    @Test fun deleteAlsoRemovesInterruptedExport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withRecord { repo, row ->
            repo.finish(row.copy(status = "完成"))
            val partial = File(context.cacheDir, "exports/DecibelEvi_${row.id}.zip.part").apply { writeText("private bytes") }
            repo.delete(row.id)
            assertFalse(partial.exists())
        }
    }

    @Test fun checkpointAndFinishPreserveMediaAndExportIsSelfConsistent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = EvidenceRepository(context)
        val id = "device_test_" + UUID.randomUUID().toString()
        val row = EvidenceSession(id, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtimeNanos())
        val originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            repo.create(row)
            val file = File(repo.directory(id), "test.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val media = MediaArtifact(UUID.randomUUID().toString(), id, "photo", file.path, row.startedAtMs)
            repo.addMedia(media)
            repo.completeMedia(media, true)
            repo.checkpoint(row.copy(durationMs = 5000))
            assertEquals("Parent checkpoints must not cascade-delete media", 1, repo.dao.media(id).size)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            repo.finish(row.copy(status = "完成", endedAtMs = row.startedAtMs + 6000, durationMs = 6000))
            assertEquals(1, repo.dao.media(id).size)
            val exported = repo.export(id)
            assertTrue(EvidenceArchive.verify(repo.directory(id)).isEmpty())
            ZipFile(exported).use {
                assertNotNull(it.getEntry("metadata.json"))
                assertNotNull(it.getEntry("measurements.csv"))
                assertNotNull(it.getEntry("test.jpg"))
                assertNotNull(it.getEntry("SHA256SUMS.txt"))
            }
            val metadata = org.json.JSONObject(File(repo.directory(id), "metadata.json").readText())
            assertEquals(1, metadata.getJSONArray("media").length())
            assertEquals("完成", metadata.getString("status"))
            assertEquals("Export must preserve capture timezone", "UTC", metadata.getString("timezone"))
        } finally {
            TimeZone.setDefault(originalZone)
            repo.dao.session(id)?.let { repo.finish(it.copy(status = "完成")) }
            repo.delete(id)
        }
    }
}
