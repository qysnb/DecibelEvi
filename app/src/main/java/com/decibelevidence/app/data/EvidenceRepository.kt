package com.decibelevidence.app.data

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.room.withTransaction
import com.decibelevidence.app.BuildConfig
import com.decibelevidence.app.model.LocationSnapshot
import com.decibelevidence.core.MeasurementSnapshot
import com.decibelevidence.core.LevelReferenceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.TimeZone

class EvidenceRepository(context: Context) {
    private val database = Room.databaseBuilder(context.applicationContext, EvidenceDatabase::class.java, "evidence.db")
        .addMigrations(EvidenceDatabase.MIGRATION_1_2, EvidenceDatabase.MIGRATION_2_3).build()
    private suspend fun <T> serialized(block: suspend () -> T): T = withContext(Dispatchers.IO) { storageMutex.withLock { block() } }
    val dao = database.evidence()
    val sessions = dao.observeSessions()
    private val root = File(context.filesDir, "evidence").apply { mkdirs() }
    private val exports = File(context.cacheDir, "exports").apply { mkdirs() }
    private val gallery = GalleryExporter(context.applicationContext)
    fun directory(id: String) = File(root, id)

    suspend fun recover() = serialized {
        recoverGallery()
        val failures = mutableListOf<String>()
        dao.needingRecovery().forEach { row ->
            try {
                val repairs = listOf("events.jsonl", "locations.jsonl", "measurements.csv").filter { repairJournal(File(directory(row.id), it)) }
                if (repairs.isNotEmpty()) appendEvent(row.id, "恢复时发现不完整末行，已保留原始备份并截去：${repairs.joinToString()}")
                appendEvent(row.id, "进程中断，未完成媒体已标记中断；结束时间为最后持久化的采集边界")
                dao.media(row.id).filter { it.status == "写入中" }.forEach { dao.saveMedia(it.copy(status = "中断")) }
                val ended = if (row.status in listOf("进行中", "保存失败")) row.copy(status = "中断", endedAtMs = row.endedAtMs ?: row.startedAtMs + row.durationMs) else row
                writeMetadata(ended)
                dao.saveSession(ended)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { failures.add("${row.id}: ${error.message}") }
        }
        check(failures.isEmpty()) { failures.joinToString("；") }
    }

    /** Only an incomplete final line is repairable. Middle corruption remains an explicit error. */
    private fun repairJournal(file: File): Boolean {
        if (!file.exists()) return false
        val text = file.readText()
        val lines = text.lines().filter { it.isNotBlank() }
        var incomplete = false
        lines.forEachIndexed { index, line ->
            try {
                if (file.name == "measurements.csv") {
                    if (index == 0) require(line == CSV_HEADER) else {
                        val fields = line.split(',')
                        require(fields.size == 8 && fields[0].toLong() >= 0)
                        Instant.parse(fields[1])
                        require(fields.subList(2, 6).all { it.isEmpty() || it.toDouble().isFinite() })
                        require(fields.takeLast(2).all { it == "true" || it == "false" })
                    }
                } else JSONObject(line)
            } catch (error: Exception) {
                if (index != lines.lastIndex || text.endsWith("\n")) throw error
                incomplete = true
            }
        }
        if (incomplete) {
            val backup = File(file.path + ".interrupted")
            if (!backup.exists()) replaceJournal(backup, text)
            val prefix = lines.dropLast(1).ifEmpty { if (file.name == "measurements.csv") listOf(CSV_HEADER) else emptyList() }
            replaceJournal(file, prefix.joinToString("\n", postfix = if (prefix.isNotEmpty()) "\n" else ""))
        } else if (text.isNotEmpty() && !text.endsWith("\n")) replaceJournal(file, "$text\n")
        return incomplete
    }
    private fun replaceJournal(target: File, text: String) {
        val part = File(target.path + ".repair.part")
        part.outputStream().use { stream -> stream.write(text.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
        check(part.renameTo(target)) { "日志恢复保存失败：${target.name}" }
    }
    suspend fun create(row: EvidenceSession) = serialized {
        val directory = directory(row.id).apply { mkdirs() }
        File(directory, "measurements.csv").writeText("$CSV_HEADER\n")
        File(directory, "locations.jsonl").writeText("")
        File(directory, "events.jsonl").writeText("")
        File(directory, "说明.txt").writeText("""
声迹分贝测量 / DecibelEvi
格式版本：${if (row.levelReferenceJson == null) 1 else 2}
媒体中的水印已写入像素，包含采集当时的测量、时间与位置状态。
平均值是本次取证有效采样的声能平均 LAeq,T；最大值为125毫秒Fast时间计权的最大值 LAFmax。
曲线及CSV的second_energy_db为每秒声能平均。仅完整秒记录于CSV；不足一秒的末尾采样仍计入最终平均与最大值。
${if (row.levelReferenceJson == null) "未校准的A计权数字电平标为dBFS，校准后为估算dB(A)，不等同经检定的专业声级计。" else "数字电平为A计权dBFS；Android标称灵敏度换算为估算dB(A)；用户参考校准也为估算dB(A)。参考方式、偏移和依据见metadata.json的levelReference，不等同经检定的专业声级计。CSV的calibrated仅表示用户参考校准，Android标称换算仍为false。"}
本机时间及WGS84经纬度由设备提供；无联网可信时间戳。旧定位、大致位置、无定位均有标记。
${if (row.levelReferenceJson == null) "历史记录中的地名为系统按可用性提供的可选信息。" else "本版本仅采集经纬度与定位状态，不查询具体地名。"}所有文件仅保存在本机，主动导出后由用户自行管理。
视频文件以录制时段为准，累计统计覆盖所属整次取证。设备型号、校准及事件请查看metadata.json。
SHA256SUMS.txt用于检查文件内容有无变化，不认证拍摄时间、地点或身份；不包含自身校验。
""".trimIndent())
        dao.saveSession(row)
        writeMetadata(row)
    }
    suspend fun appendMeasurements(id: String, snapshot: MeasurementSnapshot, afterSecond: Long): Long = serialized {
        val points = snapshot.curve.filter { it.elapsedSeconds > afterSecond }
        if (points.isEmpty()) return@serialized afterSecond
        val row = dao.session(id) ?: return@serialized afterSecond
        val text = points.joinToString("") { point ->
            listOf(point.elapsedSeconds.toString(), Instant.ofEpochMilli(snapshot.wallTimeMs - (snapshot.timeNs - row.startNs) / 1_000_000 + point.elapsedSeconds * 1000).toString(),
                point.db?.toString().orEmpty(), point.maximumDb?.toString().orEmpty(),
                point.cumulativeAverageDb?.toString().orEmpty(), point.cumulativeMaximumDb?.toString().orEmpty(),
                snapshot.calibrated.toString(), (point.db != null).toString()).joinToString(",") + "\n"
        }
        File(directory(id), "measurements.csv").appendText(text)
        points.last().elapsedSeconds
    }
    /** Photo CSV starts at its measurement window, not at the zero-duration shutter event. */
    suspend fun savePhotoWindow(id: String, snapshot: MeasurementSnapshot, measurementOriginNs: Long) = serialized {
        val firstSecond = snapshot.curve.firstOrNull()?.elapsedSeconds ?: 0L
        val csvOriginNs = if (snapshot.curve.isEmpty()) snapshot.timeNs else measurementOriginNs + firstSecond * 1_000_000_000L
        val lines = snapshot.curve.joinToString("") { point ->
            val binNs = measurementOriginNs + point.elapsedSeconds * 1_000_000_000L
            val wallMs = snapshot.wallTimeMs - (snapshot.timeNs - binNs) / 1_000_000L
            listOf((point.elapsedSeconds - firstSecond).toString(), Instant.ofEpochMilli(wallMs).toString(),
                point.db?.toString().orEmpty(), point.maximumDb?.toString().orEmpty(),
                point.cumulativeAverageDb?.toString().orEmpty(), point.cumulativeMaximumDb?.toString().orEmpty(),
                snapshot.calibrated.toString(), (point.db != null).toString()).joinToString(",") + "\n"
        }
        File(directory(id), "measurements.csv").writeText("$CSV_HEADER\n$lines")
        val metadataFile = File(directory(id), "metadata.json")
        val metadata = JSONObject(metadataFile.readText()).put("photoMeasurement", JSONObject()
            .put("scope", "complete valid one-second bins within 60 seconds before shutter")
            .put("capturedAtMs", snapshot.wallTimeMs).put("capturedAtNs", snapshot.timeNs)
            .put("csvOriginNs", csvOriginNs)
            .put("csvOriginWallMs", snapshot.wallTimeMs - (snapshot.timeNs - csvOriginNs) / 1_000_000L)
            .put("currentDb", snapshot.currentDb ?: JSONObject.NULL)
            .put("valid", snapshot.valid))
        val part = File(metadataFile.path + ".part")
        part.writeText(metadata.toString(2)); check(part.renameTo(metadataFile)) { "照片测量元数据保存失败" }
        File(directory(id), "说明.txt").appendText("\n本记录为单张照片，取证时长为0。AVG/MAX来自拍照前60秒内的有效完整秒，不足60秒仅使用已有数据；边界不完整秒不计入AVG/MAX。CSV从该测量窗口的首秒起计数，时间原点见metadata.json的photoMeasurement，与按快门时间不同。\n")
    }
    suspend fun appendLocation(id: String, location: LocationSnapshot) = serialized {
        File(directory(id), "locations.jsonl").appendText(locationJson(location).toString() + "\n")
    }
    suspend fun event(id: String, message: String) = serialized { appendEvent(id, message) }
    private fun appendEvent(id: String, message: String) {
        File(directory(id), "events.jsonl").appendText(JSONObject().put("wallTimeMs", System.currentTimeMillis())
            .put("message", message).toString() + "\n")
    }
    suspend fun checkpoint(row: EvidenceSession) = serialized { dao.saveSession(row.copy(endedAtMs = System.currentTimeMillis())) }
    suspend fun finish(row: EvidenceSession) = serialized {
        try { writeMetadata(row); dao.saveSession(row) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            dao.saveSession(row.copy(status = "保存失败"))
            throw error
        }
    }
    suspend fun addMedia(row: MediaArtifact) = serialized { dao.saveMedia(row) }
    suspend fun completeMedia(row: MediaArtifact, success: Boolean): MediaArtifact = withContext(Dispatchers.IO) {
        // The row stays pending while a large video is hashed. Do not block ongoing CSV appends.
        val file = File(row.path)
        val finished = row.copy(endedAtMs = System.currentTimeMillis(), status = if (success && file.isFile) "完成" else "失败",
            sha256 = if (success && file.isFile) EvidenceArchive.sha256(file) else null)
        serialized {
            check(dao.session(row.sessionId) != null) { "所属记录已不存在" }
            dao.saveMedia(finished)
            dao.session(row.sessionId)?.takeIf { it.status != "进行中" }?.let { writeMetadata(it) }
        }
        finished
    }
    suspend fun export(id: String): File = serialized {
        val row = dao.session(id) ?: error("记录不存在")
        check(row.status != "进行中") { "请先结束取证再导出" }
        val media = dao.media(id)
        check(media.none { it.status == "写入中" }) { "媒体仍在保存，请稍后导出" }
        writeMetadata(row)
        val committed = media.filter { it.status == "完成" }.map { artifact ->
            val file = File(artifact.path)
            check(file.isFile && EvidenceArchive.sha256(file) == artifact.sha256) { "媒体缺失或校验不一致：${file.name}" }
            file.name
        }
        val repairs = listOf("events.jsonl.interrupted", "locations.jsonl.interrupted", "measurements.csv.interrupted").filter { File(directory(id), it).isFile }
        val names = setOf("metadata.json", "measurements.csv", "events.jsonl", "locations.jsonl", "说明.txt") + committed + repairs
        val output = File(exports, "DecibelEvi_${id}.zip")
        EvidenceArchive.create(directory(id), output, names)
        output
    }
    suspend fun saveToGallery(mediaId: String): GallerySaveResult = serialized {
        recoverGallery()
        val media = dao.mediaArtifact(mediaId) ?: error("媒体不存在")
        gallery.save(media) { uri -> dao.setGalleryUri(mediaId, uri.toString()) }
    }
    private suspend fun recoverGallery() {
        gallery.recoverPending { mediaId, uri -> dao.setGalleryUri(mediaId, uri.toString()) }
    }
    suspend fun rename(id: String, name: String): Unit = serialized {
        val normalized = name.trim().takeIf { it.isNotEmpty() }
        require(normalized == null || normalized.length <= 80) { "名称不能超过80个字符" }
        val row = dao.session(id) ?: error("记录不存在")
        check(row.status != "进行中") { "请先结束取证再重命名" }
        check(dao.media(id).none { it.status == "写入中" }) { "媒体仍在保存，请稍后重命名" }
        database.withTransaction {
            dao.rename(id, normalized)
            val target = File(directory(id), "metadata.json")
            if (target.exists()) {
                // Renaming is a presentation edit. Preserve all captured fields, including unknown ones.
                val value = JSONObject(target.readText()).put("displayName", normalized ?: JSONObject.NULL)
                saveMetadata(target, value)
            } else writeMetadata(row.copy(displayName = normalized))
        }
    }
    suspend fun delete(id: String) = serialized {
        val row = dao.session(id) ?: return@serialized
        check(row.status != "进行中") { "进行中的取证不能删除" }
        check(dao.media(id).none { it.status == "写入中" }) { "媒体仍在保存，请稍后删除" }
        val dir = directory(id)
        check(dir.canonicalFile.parentFile == root.canonicalFile)
        check(!dir.exists() || dir.deleteRecursively()) { "无法删除媒体文件" }
        listOf(".zip", ".zip.part").forEach { suffix ->
            val archive = File(exports, "DecibelEvi_${id}$suffix")
            check(!archive.exists() || archive.delete()) { "无法删除导出文件" }
        }
        dao.delete(id)
    }
    private suspend fun writeMetadata(row: EvidenceSession) {
        val target = File(directory(row.id), "metadata.json")
        val captured = target.takeIf { it.exists() }?.let { JSONObject(it.readText()) }
        val media = dao.media(row.id)
        val events = File(directory(row.id), "events.jsonl").takeIf { it.exists() }?.readLines().orEmpty()
        val locations = File(directory(row.id), "locations.jsonl").takeIf { it.exists() }?.readLines().orEmpty()
        val reference = LevelReferenceJson.decode(row.levelReferenceJson)
        check(row.levelReferenceJson == null || reference != null) { "电平参考元数据无效" }
        val unit = when (reference?.mode) {
            LevelReferenceMode.DIGITAL -> "A-weighted dBFS"
            LevelReferenceMode.ANDROID_NOMINAL -> "estimated dB(A)"
            LevelReferenceMode.USER_REFERENCE -> "estimated dB(A)"
            null -> if (row.calibrated) "estimated dB(A)" else "A-weighted dBFS"
        }
        val value = JSONObject().put("formatVersion", if (row.levelReferenceJson == null) 1 else 2).put("sessionId", row.id)
            .put("displayName", row.displayName ?: JSONObject.NULL)
            .put("appVersion", BuildConfig.VERSION_NAME).put("manufacturer", Build.MANUFACTURER)
            .put("deviceModel", Build.MODEL).put("androidApi", Build.VERSION.SDK_INT)
            .put("timezone", TimeZone.getDefault().id).put("coordinateSystem", "WGS84")
            .put("startedAtMs", row.startedAtMs).put("endedAtMs", row.endedAtMs ?: JSONObject.NULL)
            .put("monotonicStartNs", row.startNs).put("durationMs", row.durationMs).put("status", row.status)
            .put("unit", unit).put("calibrated", row.calibrated)
            .put("averageDb", row.averageDb ?: JSONObject.NULL).put("maximumDb", row.maximumDb ?: JSONObject.NULL)
            .put("calibration", if (row.calibrationJson == "null") JSONObject.NULL else JSONObject(row.calibrationJson))
            .put("audioInput", row.inputKey).put("timeSource", "device wall clock; duration uses monotonic clock")
            .put("filter", "bilinear A-weighting, 48kHz, normalized at 1kHz; high-frequency warping applies")
            .put("events", JSONArray(events.filter { it.isNotBlank() }.map { JSONObject(it) }))
            .put("locations", JSONArray(locations.filter { it.isNotBlank() }.map { JSONObject(it) }))
            .put("media", JSONArray(media.map {
                JSONObject().put("id", it.id).put("file", File(it.path).name).put("kind", it.kind)
                    .put("startedAtMs", it.startedAtMs).put("endedAtMs", it.endedAtMs ?: JSONObject.NULL)
                    .put("status", it.status).put("sha256", it.sha256 ?: JSONObject.NULL)
            }))
        row.levelReferenceJson?.let {
            value.put("levelReference", JSONObject(it))
                .put("calibratedMeaning", "true only for user-reference calibration; Android nominal conversion is false")
        }
        // Capture context is immutable even when exported after an app upgrade or timezone change.
        listOf("appVersion", "manufacturer", "deviceModel", "androidApi", "timezone", "coordinateSystem", "timeSource", "filter", "photoMeasurement").forEach { key ->
            if (captured?.has(key) == true) value.put(key, captured.get(key))
        }
        saveMetadata(target, value)
    }
    private fun saveMetadata(target: File, value: JSONObject) {
        val part = File(target.path + ".part")
        part.writeText(value.toString(2))
        check(part.renameTo(target)) { "元数据保存失败" }
    }
    companion object {
        private const val CSV_HEADER = "elapsed_second,wall_time_iso,second_energy_db,second_fast_max_db,cumulative_energy_db,cumulative_fast_max_db,calibrated,valid"
        private val storageMutex = Mutex()
        fun locationJson(it: LocationSnapshot) = JSONObject().put("latitude", it.latitude ?: JSONObject.NULL)
            .put("longitude", it.longitude ?: JSONObject.NULL).put("accuracyMeters", it.accuracyMeters ?: JSONObject.NULL)
            .put("monotonicNs", it.timeNs).put("wallTimeMs", it.wallTimeMs)
            .put("placeName", it.placeName ?: JSONObject.NULL).put("approximate", it.approximate)
    }
}
