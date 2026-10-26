package com.decibelevidence.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class GallerySaveResult(val uri: Uri, val alreadySaved: Boolean)

/** Called under the repository storage mutex, including recovery and journal writes. */
internal class GalleryExporter(context: Context) {
    private val resolver = context.contentResolver
    private val journal = AtomicFile(File(context.filesDir, "gallery-pending.json"))

    fun isPublished(uri: Uri): Boolean = pendingState(uri) == false

    private fun pendingState(uri: Uri): Boolean? = resolver.query(uri,
        arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
        if (it.moveToFirst()) it.getInt(0) != 0 else null
    }

    suspend fun save(media: MediaArtifact, commit: suspend (Uri) -> Unit): GallerySaveResult {
        media.galleryUri?.let { Uri.parse(it) }?.takeIf { isPublished(it) }?.let {
            return GallerySaveResult(it, alreadySaved = true)
        }
        check(media.status == "完成" && !media.sha256.isNullOrBlank()) { "媒体尚未完成或缺少校验值" }
        val source = File(media.path)
        check(source.isFile) { "原始媒体文件不存在" }
        val collection = when (media.kind) {
            "photo" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            "video" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> error("不支持的媒体类型：${media.kind}")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "DecibelEvi_${media.id}.${if (media.kind == "photo") "jpg" else "mp4"}")
            put(MediaStore.MediaColumns.MIME_TYPE, if (media.kind == "photo") "image/jpeg" else "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/DecibelEvi")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("无法创建相册媒体")
        var published = false
        try {
            // Persist the exact owned URI before opening the source or copying a single byte.
            trackPending(media.id, uri)
            val digest = MessageDigest.getInstance("SHA-256")
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
                output.flush()
            } ?: error("无法写入相册媒体")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(media.sha256, ignoreCase = true)) { "原始媒体校验不一致，已取消保存到相册" }
            coroutineContext.ensureActive()
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) { "相册媒体发布失败" }
            published = true
            commit(uri)
            untrack(media.id)
            return GallerySaveResult(uri, alreadySaved = false)
        } catch (error: Throwable) {
            // A published item belongs to the user; recovery can finish its DB commit.
            if (!published) {
                try {
                    resolver.delete(uri, null, null)
                    untrack(media.id)
                } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }

    suspend fun recoverPending(commit: suspend (String, Uri) -> Unit) {
        val entries = readJournal()
        entries.keys().asSequence().toList().forEach { id ->
            val uri = Uri.parse(entries.getString(id))
            when (pendingState(uri)) {
                true -> resolver.delete(uri, null, null)
                false -> commit(id, uri)
                null -> Unit
            }
            untrack(id)
        }
    }

    internal fun trackPending(id: String, uri: Uri) = writeJournal(readJournal().put(id, uri.toString()))
    private fun untrack(id: String) = writeJournal(readJournal().apply { remove(id) })
    private fun readJournal(): JSONObject = try {
        journal.openRead().use { JSONObject(it.reader().readText()) }
    } catch (_: java.io.FileNotFoundException) { JSONObject() }

    private fun writeJournal(value: JSONObject) {
        val output = journal.startWrite()
        try {
            output.write(value.toString().toByteArray(Charsets.UTF_8))
            journal.finishWrite(output)
        } catch (error: Throwable) {
            journal.failWrite(output)
            throw error
        }
    }
}
