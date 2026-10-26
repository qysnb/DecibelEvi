package com.decibelevidence.app.data

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GalleryExportDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    @Test fun savesOriginalBytesReusesExistingAndRecopiesDeletedGalleryItem() = runBlocking {
        for (kind in listOf("photo", "video")) withMedia(kind) { repo, media ->
            val first = repo.saveToGallery(media.id)
            var last = first.uri
            try {
                assertFalse(first.alreadySaved)
                assertArrayEquals(File(media.path).readBytes(), resolver.openInputStream(first.uri)!!.use { it.readBytes() })
                resolver.query(first.uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                    assertEquals("DCIM/DecibelEvi/", it.getString(1))
                }
                val repeat = repo.saveToGallery(media.id)
                assertTrue(repeat.alreadySaved)
                assertEquals(first.uri, repeat.uri)
                resolver.delete(first.uri, null, null)
                val replacement = repo.saveToGallery(media.id)
                last = replacement.uri
                assertFalse(replacement.alreadySaved)
                assertNotEquals(first.uri, replacement.uri)
                assertEquals(last.toString(), repo.dao.media(media.sessionId).single().galleryUri)
                repo.delete(media.sessionId)
                assertArrayEquals("Deleting the record must preserve the published gallery copy", byteArrayOf(1, 2, 3, 4), resolver.openInputStream(last)!!.use { it.readBytes() })
            } finally { resolver.delete(last, null, null) }
        }
    }

    @Test fun hashMismatchRemovesPendingItemAndDoesNotMarkMediaSaved() = runBlocking {
        withMedia("photo") { repo, media ->
            File(media.path).appendText("tampered")
            val before = galleryIds()
            assertTrue(runCatching { repo.saveToGallery(media.id) }.isFailure)
            assertEquals(before, galleryIds())
            assertNull(repo.dao.media(media.sessionId).single().galleryUri)
        }
    }

    @Test fun recoveryDeletesOnlyJournaledPendingItemsAndPreservesPublishedItems() = runBlocking {
        val exporter = GalleryExporter(context)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "DecibelEvi_recovery_${UUID.randomUUID()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/DecibelEvi")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val own = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "DecibelEvi_untracked_${UUID.randomUUID()}.jpg")
        val untracked = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            assertNotEquals("Recovery fixtures must represent two independent gallery items", own, untracked)
            val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                for (uri in listOf(own, untracked)) resolver.openOutputStream(uri)!!.use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it))
                }
            } finally { bitmap.recycle() }
            exporter.trackPending("interrupted-test", own)
            exporter.recoverPending { _, _ -> fail("Incomplete item must not be committed") }
            assertFalse(exists(own))
            assertTrue(exists(untracked))
            resolver.update(untracked, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            exporter.trackPending("published-test", untracked)
            var adopted: Uri? = null
            exporter.recoverPending { id, uri -> assertEquals("published-test", id); adopted = uri }
            assertEquals(untracked, adopted)
            assertTrue(exists(untracked))
        } finally {
            if (exists(own)) resolver.delete(own, null, null)
            if (exists(untracked)) resolver.delete(untracked, null, null)
        }
    }

    private fun exists(uri: Uri) = resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)!!.use { it.moveToFirst() }
    private fun galleryIds(): Set<Long> = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("DCIM/DecibelEvi/"), null)!!.use {
        buildSet { while (it.moveToNext()) add(it.getLong(0)) }
    }
    private suspend fun withMedia(kind: String, block: suspend (EvidenceRepository, MediaArtifact) -> Unit) {
        val repo = EvidenceRepository(context)
        val row = EvidenceSession("gallery_test_${UUID.randomUUID()}", 10_000, 1_000_000, status = "完成")
        repo.create(row)
        try {
            val file = File(repo.directory(row.id), if (kind == "photo") "original.jpg" else "original.mp4")
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val media = MediaArtifact(UUID.randomUUID().toString(), row.id, kind, file.path, row.startedAtMs)
            repo.addMedia(media)
            block(repo, repo.completeMedia(media, true))
        } finally { repo.delete(row.id) }
    }
}
