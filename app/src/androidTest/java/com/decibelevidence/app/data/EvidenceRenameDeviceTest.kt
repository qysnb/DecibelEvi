package com.decibelevidence.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class EvidenceRenameDeviceTest {
    @Test fun metadataWriteFailureLeavesStoredNameAndCapturedMetadataUnchanged() = runBlocking {
        val repo = EvidenceRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val row = EvidenceSession("rename_failure_${UUID.randomUUID()}", 100, 200, status = "完成")
        val blockedPart = File(repo.directory(row.id), "metadata.json.part")
        try {
            repo.create(row)
            val target = File(repo.directory(row.id), "metadata.json")
            val original = target.readBytes()
            assertTrue(blockedPart.mkdir())
            assertTrue(runCatching { repo.rename(row.id, "未保存的名称") }.isFailure)
            assertNull(repo.dao.session(row.id)!!.displayName)
            assertArrayEquals(original, target.readBytes())
        } finally {
            blockedPart.delete()
            repo.delete(row.id)
        }
    }

    @Test fun renamePersistsAndExportsWithoutChangingCapturedEvidence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = EvidenceRepository(context)
        val row = EvidenceSession("rename_${UUID.randomUUID()}", 100, 200, endedAtMs = 300,
            status = "完成", averageDb = 45.5, maximumDb = 60.5, durationMs = 200)
        try {
            repo.create(row)
            val dir = repo.directory(row.id)
            val photo = File(dir, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            repo.addMedia(MediaArtifact("photo_${row.id}", row.id, "photo", photo.path, 100, 300,
                "完成", EvidenceArchive.sha256(photo)))
            repo.export(row.id)
            val metadataFile = File(dir, "metadata.json")
            val original = JSONObject(metadataFile.readText()).put("futureCaptureField", "keep me")
            metadataFile.writeText(original.toString())
            val capturedFiles = dir.listFiles()!!.filter { it.name != "metadata.json" }.associate { it.name to it.readBytes() }

            repo.rename(row.id, "  楼上夜间噪声  ")

            val reopened = EvidenceRepository(context)
            assertEquals(row.copy(displayName = "楼上夜间噪声"), reopened.dao.session(row.id))
            val renamed = JSONObject(metadataFile.readText())
            assertEquals("楼上夜间噪声", renamed.getString("displayName"))
            original.keys().forEach { key ->
                if (key != "displayName") assertEquals(key, original.get(key).toString(), renamed.get(key).toString())
            }
            capturedFiles.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(dir, name).readBytes()) }
            ZipFile(reopened.export(row.id)).use { zip ->
                val exported = JSONObject(zip.getInputStream(zip.getEntry("metadata.json")).bufferedReader().readText())
                assertEquals("楼上夜间噪声", exported.getString("displayName"))
            }
            repo.rename(row.id, " \n\t ")
            assertEquals(row, reopened.dao.session(row.id))
            assertTrue(JSONObject(metadataFile.readText()).isNull("displayName"))
        } finally { repo.delete(row.id) }
    }

    @Test fun rejectsLongNamesAndUnfinishedRecordsWithoutChangingName() = runBlocking {
        val repo = EvidenceRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val row = EvidenceSession("rename_guard_${UUID.randomUUID()}", 100, 200, status = "完成")
        try {
            repo.create(row)
            repo.rename(row.id, "a".repeat(80))
            assertTrue(runCatching { repo.rename(row.id, "a".repeat(81)) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals("a".repeat(80), repo.dao.session(row.id)!!.displayName)
            repo.dao.saveSession(row.copy(status = "进行中"))
            assertTrue(runCatching { repo.rename(row.id, "active") }.exceptionOrNull() is IllegalStateException)
            assertNull(repo.dao.session(row.id)!!.displayName)
            repo.dao.saveSession(row)
            val media = MediaArtifact("pending_${row.id}", row.id, "photo", File(repo.directory(row.id), "pending.jpg").path, 100)
            repo.addMedia(media)
            assertTrue(runCatching { repo.rename(row.id, "pending") }.exceptionOrNull() is IllegalStateException)
            assertNull(repo.dao.session(row.id)!!.displayName)
            repo.dao.saveMedia(media.copy(status = "失败"))
        } finally {
            repo.dao.session(row.id)?.let { repo.dao.saveSession(it.copy(status = "完成")) }
            repo.dao.media(row.id).forEach { repo.dao.saveMedia(it.copy(status = "失败")) }
            repo.delete(row.id)
        }
    }
}
