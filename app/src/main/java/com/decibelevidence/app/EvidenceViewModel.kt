package com.decibelevidence.app

import android.app.Application
import android.os.SystemClock
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.decibelevidence.app.audio.AudioSampler
import com.decibelevidence.app.capture.CaptureEngine
import com.decibelevidence.app.data.*
import com.decibelevidence.app.location.LocationTracker
import com.decibelevidence.app.model.*
import com.decibelevidence.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import kotlin.math.abs

enum class CaptureMode { IDLE, PREVIEW, EVIDENCE, CALIBRATION, FINALIZING }

data class CaptureUiState(
    val mode: CaptureMode = CaptureMode.IDLE, val previewStartNs: Long? = null,
    val levelReference: LevelReference? = null,
    val session: EvidenceSession? = null, val measurement: MeasurementSnapshot? = null,
    val location: LocationSnapshot? = null, val videoStartNs: Long? = null,
    val calibration: Calibration? = null, val calibrating: Boolean = false,
    val calibrationSeconds: Int = 0, val rawCalibrationMean: Double? = null,
    val busy: Boolean = false, val message: String? = null, val nowNs: Long = SystemClock.elapsedRealtimeNanos()
)

data class GalleryUiState(val running: Boolean = false, val sessionId: String? = null,
    val completed: Int = 0, val total: Int = 0)

class EvidenceViewModel(application: Application) : AndroidViewModel(application) {
    val repository = EvidenceRepository(application)
    val sessions = repository.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val calibrationStore = CalibrationStore(application)
    private val _ui = MutableStateFlow(CaptureUiState(calibration = calibrationStore.read()))
    val ui = _ui.asStateFlow()
    private val _galleryUi = MutableStateFlow(GalleryUiState())
    val galleryUi = _galleryUi.asStateFlow()
    private val finalizerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operation = Mutex()
    private val engineLock = Any()
    private val history = ArrayDeque<MeasurementSnapshot>()
    private val windowHistory = ArrayDeque<MeasurementSnapshot>()
    private var windowEngine: MeasurementEngine? = null
    private var windowStartNs = 0L
    private val locations = ArrayDeque<LocationSnapshot>()
    private var engine: MeasurementEngine? = null
    @Volatile private var session: EvidenceSession? = null
    @Volatile private var videoStart: Long? = null
    private var activeVideo: MediaArtifact? = null
    private var tick: Job? = null
    private var calibrationJob: Job? = null
    private var lastWrittenSecond = -1L
    private var calibrationInput = ""
    private val photoJobs = mutableListOf<Deferred<Unit>>()
    private var lastAudioNs = 0L
    private val initialized = CompletableDeferred<Unit>()
    private var ending = false
    private val locationWrites = mutableSetOf<Job>()
    private var cameraRunning = false
    @Volatile private var capturePageVisible = false
    @Volatile private var captureGeneration = 0L
    private data class CaptureStop(val timeNs: Long, val wallTimeMs: Long)
    // First stop for the current capture lifecycle; retain through queued finalization.
    private var captureStop: CaptureStop? = null
    private var previewStartNs: Long? = null
    val capture = CaptureEngine(application, ::overlayAt) { message -> fail(message) }
    private val audio = AudioSampler(application) { fail(it) }
    private val location = LocationTracker(application) { sample ->
        synchronized(engineLock) {
            locations.addLast(sample)
            while (locations.size > 180) locations.removeFirst()
        }
        _ui.update { it.copy(location = sample) }
        session?.id?.let { id ->
            val job = finalizerScope.launch {
                try { repository.appendLocation(id, sample) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { if (session?.id == id) fail("位置记录保存失败") }
            }
            locationWrites.add(job)
            job.invokeOnCompletion { locationWrites.remove(job) }
        }
    }
    init {
        viewModelScope.launch {
            runCatching { repository.recover() }.onFailure { show("历史记录恢复失败：${it.message}") }
            initialized.complete(Unit)
        }
    }
    fun show(message: String) { _ui.update { it.copy(message = message) } }
    fun dismissMessage() { _ui.update { it.copy(message = null) } }
    private fun captureAllowed(generation: Long) = capturePageVisible && captureGeneration == generation
    fun attachPreview(surface: Surface, width: Int, height: Int, rotation: Int) {
        if (!capturePageVisible) return
        capture.attachPreview(surface, width, height, rotation)
    }
    fun detachPreview() { capture.detachPreview() }
    fun resumeCamera(): Job {
        capturePageVisible = true
        val generation = captureGeneration
        return viewModelScope.launch { operation.withLock {
            initialized.await()
            if (!captureAllowed(generation)) return@withLock
            if (!cameraRunning) { cameraRunning = true; capture.startCamera() }
            if (session == null && !_ui.value.calibrating && _ui.value.mode != CaptureMode.PREVIEW) startPreviewLocked(generation)
        } }
    }
    fun leaveCapturePage(reason: String = "已离开拍摄页"): Job {
        if (captureStop == null) captureStop = CaptureStop(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
        capturePageVisible = false
        captureGeneration++
        // Stop sensor input before waiting for photo encoding or storage finalization.
        tick?.cancel(); calibrationJob?.cancel()
        audio.stop(); location.stop(); capture.closeInput()
        cameraRunning = false
        return endSession(reason, stopCamera = true)
    }
    private suspend fun stopMeasurementLocked() {
        tick?.cancelAndJoin(); tick = null
        audio.stop(); location.stop()
        synchronized(engineLock) { engine = null; history.clear(); locations.clear(); previewStartNs = null }
    }
    private suspend fun startPreviewLocked(generation: Long = captureGeneration) {
        if (!captureAllowed(generation) || session != null || _ui.value.calibrating) return
        _ui.update { it.copy(busy = true) }
        try {
            stopMeasurementLocked()
            if (!captureAllowed(generation)) return
            captureStop = null
            if (!cameraRunning) { cameraRunning = true; capture.startCamera() }
            val input = audio.prepare()
            val reference = LevelReference.forInput(input.source, input.key, calibrationStore.read())
            val ns = SystemClock.elapsedRealtimeNanos()
            synchronized(engineLock) {
                previewStartNs = ns
                if (windowEngine == null) {
                    windowStartNs = ns
                    windowEngine = MeasurementEngine(startNs = ns, startWallMs = System.currentTimeMillis(), levelReference = reference)
                    windowHistory.clear()
                }
                engine = MeasurementEngine(startNs = ns, startWallMs = System.currentTimeMillis(), levelReference = reference)
                lastAudioNs = ns
            }
            _ui.update { it.copy(mode = CaptureMode.PREVIEW, previewStartNs = ns, levelReference = reference,
                measurement = null, location = null, nowNs = ns) }
            audio.start(::onPcm); location.start(); startTick()
        } catch (error: Exception) {
            stopMeasurementLocked(); clearSessionState()
            capture.stopCamera(); cameraRunning = false
            if (error is CancellationException) throw error
            show("预览测量失败：${error.message}，可重新进入拍摄页或开始取证重试")
        } finally { _ui.update { it.copy(busy = false) } }
    }
    private fun overlayAt(atNs: Long): OverlayState = synchronized(engineLock) {
        val row = session
        val snapshot = if (row == null) windowHistory.lastOrNull { it.timeNs <= atNs }?.forPhotoWindow(windowStartNs, atNs)
            else history.lastOrNull { it.timeNs <= atNs }
        val point = locations.lastOrNull { it.timeNs <= atNs }
        OverlayState(row?.id.orEmpty(), row?.startNs ?: previewStartNs ?: atNs, videoStart, snapshot?.let {
            if (atNs - it.timeNs > 250_000_000L) it.copy(currentDb = null, valid = false) else it
        }, point, wallTimeAt(atNs, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis()),
            _ui.value.levelReference, graphStartNs = if (row == null) windowStartNs else row.startNs)
    }
    private fun onPcm(pcm: ShortArray, count: Int, firstNs: Long) {
        synchronized(engineLock) {
            windowEngine?.process(pcm, count, firstNs)?.let { measured ->
                windowHistory.addLast(measured.copy(wallTimeMs = wallTimeAt(measured.timeNs, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())))
                while (windowHistory.size > 100) windowHistory.removeFirst()
            }
            engine?.process(pcm, count, firstNs)?.let { measured ->
                val snapshot = measured.copy(wallTimeMs = wallTimeAt(measured.timeNs, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis()))
                history.addLast(snapshot)
                while (history.size > 100) history.removeFirst()
            }
            lastAudioNs = SystemClock.elapsedRealtimeNanos()
        }
        capture.acceptAudio(pcm, count, firstNs)
    }
    fun startSession(): Job {
        val generation = captureGeneration
        return viewModelScope.launch { operation.withLock { startSessionLocked(generation) } }
    }
    private suspend fun startSessionLocked(generation: Long) {
            initialized.await()
            if (!captureAllowed(generation) || session != null || _ui.value.calibrating) return
            if (_galleryUi.value.running) {
                show("正在保存到相册，请完成后再开始取证")
                return
            }
            _ui.update { it.copy(busy = true, rawCalibrationMean = null) }
            try {
                check(File(getApplication<Application>().filesDir.path).usableSpace > 150L * 1024 * 1024) { "可用存储不足150MB" }
                stopMeasurementLocked()
                if (!captureAllowed(generation)) return
                captureStop = null
                if (!cameraRunning) { cameraRunning = true; capture.startCamera() }
                val input = audio.prepare()
                val stored = calibrationStore.read()
                val calibration = stored?.takeIf { it.inputKey == input.key }
                val reference = LevelReference.forInput(input.source, input.key, calibration)
                val ns = SystemClock.elapsedRealtimeNanos()
                val ms = System.currentTimeMillis()
                val id = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(java.time.LocalDateTime.now()) + "_" + UUID.randomUUID().toString().take(6)
                val row = EvidenceSession(id, ms, ns, calibrated = calibration != null,
                    calibrationJson = CalibrationStore.json(calibration)?.toString() ?: "null", inputKey = input.key,
                    levelReferenceJson = LevelReferenceJson.encode(reference))
                repository.create(row)
                session = row
                if (!captureAllowed(generation)) {
                    endSessionLocked("已离开拍摄页", stopCamera = true)
                    return
                }
                synchronized(engineLock) { history.clear(); locations.clear(); engine = MeasurementEngine(48000, calibration, ns, ms, reference); lastAudioNs = ns }
                lastWrittenSecond = -1
                _ui.update { it.copy(session = row, measurement = null, location = null,
                    mode = CaptureMode.EVIDENCE, previewStartNs = null, levelReference = reference) }
                audio.start(::onPcm)
                location.start()
                if (stored != null && calibration == null) show("输入配置与上次校准不同，本次使用Android标称估算")
                startTick()
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    audio.stop(); location.stop()
                    session?.let { row ->
                        runCatching { repository.event(row.id, error.message ?: "启动失败") }
                        runCatching { repository.finish(row.copy(status = "失败", endedAtMs = System.currentTimeMillis())) }
                    }
                    clearSessionState()
                    show(error.message ?: "无法开始取证")
                }
                if (error is CancellationException) throw error
            } finally { _ui.update { it.copy(busy = false) } }
    }
    private fun startTick() {
        tick = viewModelScope.launch {
            var iteration = 0
            var wallOffset = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            var timezone = java.util.TimeZone.getDefault().id
            while (isActive && _ui.value.mode in listOf(CaptureMode.PREVIEW, CaptureMode.EVIDENCE)) {
                delay(250)
                val ns = SystemClock.elapsedRealtimeNanos()
                val snap = synchronized(engineLock) { engine?.snapshot(ns) }?.let { it.copy(wallTimeMs = wallTimeAt(it.timeNs, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())) } ?: continue
                val displayed = if (session == null) synchronized(engineLock) { windowEngine?.snapshot(ns)?.forPhotoWindow(windowStartNs, ns) } ?: snap else snap
                _ui.update { it.copy(measurement = displayed, nowNs = ns) }
                val row = session
                if (row == null) {
                    if (ns - synchronized(engineLock) { lastAudioNs } >= 2_000_000_000L) {
                        fail("麦克风超过2秒没有有效采样"); break
                    }
                    continue
                }
                val current = row.copy(averageDb = snap.averageDb, maximumDb = snap.maximumDb, durationMs = (ns - row.startNs) / 1_000_000)
                session = current
                _ui.update { it.copy(session = current) }
                try {
                    lastWrittenSecond = repository.appendMeasurements(row.id, snap, lastWrittenSecond)
                    val newTimezone = java.util.TimeZone.getDefault().id
                    if (newTimezone != timezone) {
                        repository.event(row.id, "设备时区变更：$timezone → $newTimezone")
                        timezone = newTimezone
                    }
                    val newOffset = System.currentTimeMillis() - SystemClock.elapsedRealtime()
                    if (abs(newOffset - wallOffset) > 2000) {
                        repository.event(row.id, "设备时钟发生跳变，差值${newOffset - wallOffset}ms")
                        wallOffset = newOffset
                    }
                    if (++iteration % 20 == 0) {
                        repository.checkpoint(current)
                        check(File(getApplication<Application>().filesDir.path).usableSpace > 100L * 1024 * 1024) { "存储空间不足，取证已停止" }
                    }
                    check(ns - synchronized(engineLock) { lastAudioNs } < 2_000_000_000L) { "麦克风超过2秒没有有效采样" }
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { fail(error.message ?: "测量数据写入失败"); break }
            }
        }
    }
    fun photo(): Job {
        val generation = captureGeneration
        return finalizerScope.launch { operation.withLock {
            if (!captureAllowed(generation)) return@withLock
            if (_ui.value.busy || _ui.value.calibrating || _galleryUi.value.running) return@withLock
            if (_ui.value.mode !in listOf(CaptureMode.PREVIEW, CaptureMode.EVIDENCE)) return@withLock
            _ui.update { it.copy(busy = true) }
            val id = "photo_" + UUID.randomUUID().toString()
            val part = File(getApplication<Application>().cacheDir, "$id.jpg.part")
            var row: EvidenceSession? = null
            try {
                check(getApplication<Application>().filesDir.usableSpace > 100L * 1024 * 1024) { "可用存储不足100MB" }
                val outcome = CompletableDeferred<Result<OverlayState>>()
                capture.takeEvidencePhoto(part, { frameNs -> synchronized(engineLock) {
                    val snapshot = checkNotNull(windowHistory.lastOrNull { it.timeNs <= frameNs }) { "尚未获得测量，请稍候拍照" }
                        .forPhotoWindow(windowStartNs, frameNs)
                    check(snapshot.valid) { "当前麦克风采样不可用，请稍候拍照" }
                    OverlayState(id, frameNs, null, snapshot,
                        locations.lastOrNull { it.timeNs <= frameNs }, snapshot.wallTimeMs,
                        snapshot.levelReference, graphStartNs = windowStartNs, isPhoto = true)
                } }) { outcome.complete(it) }
                val captured = withTimeout(15_000) { outcome.await().getOrThrow() }
                val snap = checkNotNull(captured.measurement)
                val reference = snap.levelReference
                val calibration = calibrationStore.read()?.takeIf { reference.mode == LevelReferenceMode.USER_REFERENCE && it.inputKey == reference.inputKey }
                val photoRow = EvidenceSession(id, captured.wallTimeMs, captured.sessionStartNs,
                    calibrated = snap.calibrated, calibrationJson = CalibrationStore.json(calibration)?.toString() ?: "null",
                    inputKey = reference.inputKey, levelReferenceJson = LevelReferenceJson.encode(reference))
                repository.create(photoRow); row = photoRow
                val file = File(repository.directory(id), "$id.jpg")
                val media = MediaArtifact(UUID.randomUUID().toString(), id, "photo", file.path, captured.wallTimeMs)
                repository.addMedia(media)
                check(part.renameTo(file)) { "无法提交照片文件" }
                repository.completeMedia(media, true)
                repository.savePhotoWindow(id, snap, captured.graphStartNs)
                captured.location?.let { repository.appendLocation(id, it) }
                repository.finish(photoRow.copy(status = "完成", endedAtMs = captured.wallTimeMs, durationMs = 0,
                    averageDb = snap.averageDb, maximumDb = snap.maximumDb))
                show("照片已保存为独立记录")
            } catch (error: Exception) {
                row?.let { runCatching { repository.finish(it.copy(status = "失败", endedAtMs = System.currentTimeMillis())) } }
                show("照片保存失败：${error.message}")
            } finally {
                part.delete()
                _ui.update { it.copy(busy = false) }
            }
        } }
    }
    fun toggleVideo(): Job {
        val generation = captureGeneration
        return finalizerScope.launch { operation.withLock {
            if (!captureAllowed(generation)) return@withLock
            if (_ui.value.busy || _ui.value.calibrating) return@withLock
            if (videoStart != null) {
                endSessionLocked()
                return@withLock
            }
            if (session == null) startSessionLocked(generation)
            if (!captureAllowed(generation) || session == null) return@withLock
            _ui.update { it.copy(busy = true) }
            try { startVideoInternal(generation) }
            catch (error: Exception) { endSessionLocked(error.message ?: "录像失败", stopCamera = true) }
            finally { _ui.update { it.copy(busy = false) } }
        } }
    }
    private suspend fun startVideoInternal(generation: Long) {
        val row = checkNotNull(session)
        val file = File(repository.directory(row.id), "video_${System.currentTimeMillis()}_${UUID.randomUUID()}.mp4")
        val media = MediaArtifact(UUID.randomUUID().toString(), row.id, "video", file.path, System.currentTimeMillis())
        repository.addMedia(media)
        if (!captureAllowed(generation)) {
            repository.completeMedia(media, false)
            return
        }
        val ns = SystemClock.elapsedRealtimeNanos()
        videoStart = ns
        activeVideo = media
        _ui.update { it.copy(videoStartNs = ns) }
        val result = CompletableDeferred<Result<Unit>>()
        capture.startVideo(File(file.path + ".part"), ns) { result.complete(it) }
        try { withTimeout(12_000) { result.await().getOrThrow() } }
        catch (error: Exception) { videoStart = null; activeVideo = null; _ui.update { it.copy(videoStartNs = null) }; repository.completeMedia(media, false); throw error }
    }
    private suspend fun stopVideoInternal(): Boolean {
        val media = activeVideo ?: return true
        val result = CompletableDeferred<Result<Unit>>()
        capture.stopVideo { result.complete(it) }
        val outcome = withTimeoutOrNull(15_000) { result.await() } ?: Result.failure(IllegalStateException("录像结束超时"))
        videoStart = null
        activeVideo = null
        _ui.update { it.copy(videoStartNs = null) }
        val file = File(media.path)
        val success = outcome.isSuccess && File(file.path + ".part").renameTo(file)
        repository.completeMedia(media, success)
        if (!success) { repository.event(media.sessionId, "录像未完成：${outcome.exceptionOrNull()?.message ?: "无法提交文件"}"); show("当前录像未完成，已保留其他记录") }
        return success
    }
    private fun clearSessionState() {
        captureStop = null
        session = null; videoStart = null; activeVideo = null
        synchronized(engineLock) { engine = null; history.clear(); locations.clear(); previewStartNs = null }
        _ui.update { it.copy(session = null, videoStartNs = null, measurement = null, location = null,
            calibrating = false, calibrationSeconds = 0, mode = CaptureMode.IDLE, previewStartNs = null) }
    }
    fun endSession(reason: String? = null, stopCamera: Boolean = false) = finalizerScope.launch {
        operation.withLock { endSessionLocked(reason, stopCamera) }
    }
    private suspend fun endSessionLocked(reason: String? = null, stopCamera: Boolean = false) {
            ending = true
            _ui.update { it.copy(busy = true, mode = CaptureMode.FINALIZING) }
            var terminalReason = reason
            try {
                tick?.cancelAndJoin(); tick = null
                calibrationJob?.cancelAndJoin(); calibrationJob = null
                val row = session
                if (row != null) {
                    // Capture ends here; encoding, hashing and disk writes are finalization time.
                    audio.stop(); location.stop()
                    val stopped = captureStop ?: CaptureStop(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
                    val captureEndNs = stopped.timeNs
                    val captureEndMs = stopped.wallTimeMs
                    val snap = synchronized(engineLock) { engine?.finish(captureEndNs) }?.let {
                        it.copy(wallTimeMs = wallTimeAt(it.timeNs, captureEndNs, captureEndMs))
                    }
                    try {
                        if (activeVideo != null && !stopVideoInternal()) terminalReason = terminalReason ?: "录像未完成"
                    } catch (error: Exception) { terminalReason = "录像保存失败：${error.message}" }
                    finally { audio.stop(); location.stop() }
                    if (withTimeoutOrNull(15_000) { photoJobs.toList().awaitAll(); locationWrites.toList().joinAll(); true } != true)
                        terminalReason = "等待媒体或位置保存超时"
                    try {
                        if (snap != null) lastWrittenSecond = repository.appendMeasurements(row.id, snap, lastWrittenSecond)
                    } catch (error: Exception) { terminalReason = "测量数据保存失败：${error.message}" }
                    terminalReason?.let { message -> runCatching { repository.event(row.id, message) } }
                    repository.finish(row.copy(status = if (terminalReason == null) "完成" else "中断",
                        endedAtMs = captureEndMs, durationMs = (captureEndNs - row.startNs) / 1_000_000,
                        averageDb = snap?.averageDb, maximumDb = snap?.maximumDb))
                    show(terminalReason?.let { "本次取证已中断：$it" } ?: "本次取证已保存，可在记录中导出")
                }
            } catch (error: Exception) {
                terminalReason = error.message ?: "保存失败"
                show("结束取证失败：$terminalReason；已停止采集，保留已落盘内容")
            } finally {
                audio.stop(); location.stop()
                clearSessionState()
                if (stopCamera || terminalReason != null) {
                    capture.stopCamera(); cameraRunning = false
                    synchronized(engineLock) { windowEngine = null; windowHistory.clear() }
                }
                ending = false
                _ui.update { it.copy(busy = false) }
            }
            if (!stopCamera && terminalReason == null && capturePageVisible) startPreviewLocked()
    }
    private fun fail(message: String) { show(message); endSession(message, stopCamera = true) }
    fun startCalibration(): Job {
        val generation = captureGeneration
        return viewModelScope.launch { operation.withLock {
            if (!captureAllowed(generation) || session != null || _ui.value.calibrating) return@withLock
            try {
                stopMeasurementLocked()
                if (!captureAllowed(generation)) return@withLock
                synchronized(engineLock) { windowEngine = null; windowHistory.clear() }
                val input = audio.prepare()
                calibrationInput = input.key
                val ns = SystemClock.elapsedRealtimeNanos()
                synchronized(engineLock) { engine = MeasurementEngine(48000, null, ns, System.currentTimeMillis()); history.clear() }
                _ui.update { it.copy(calibrating = true, calibrationSeconds = 0, rawCalibrationMean = null, mode = CaptureMode.CALIBRATION) }
                audio.start(::onPcm)
                calibrationJob = viewModelScope.launch {
                    try {
                        repeat(40) { index ->
                            delay(250)
                            val snapshot = synchronized(engineLock) { engine?.snapshot(SystemClock.elapsedRealtimeNanos()) }
                            _ui.update { it.copy(measurement = snapshot, calibrationSeconds = (index + 1) / 4) }
                        }
                        audio.stop()
                        val snap = synchronized(engineLock) { engine?.snapshot(SystemClock.elapsedRealtimeNanos()) }
                        val levels = snap?.curve?.mapNotNull { it.db }.orEmpty()
                        check(levels.size >= 9 && levels.max() - levels.min() <= 4.0) { "声场波动过大或有效采样不足，请保持稳定后重新采集" }
                        _ui.update { it.copy(rawCalibrationMean = snap?.averageDb, calibrating = false) }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { audio.stop(); _ui.update { it.copy(calibrating = false, rawCalibrationMean = null) }; show(error.message ?: "校准失败") }
                    finally {
                        synchronized(engineLock) { engine = null; history.clear() }
                        _ui.update { it.copy(measurement = null, mode = CaptureMode.IDLE) }
                    }
                }
            } catch (error: Exception) {
                audio.stop()
                synchronized(engineLock) { engine = null; history.clear() }
                _ui.update { it.copy(calibrating = false, calibrationSeconds = 0, rawCalibrationMean = null, measurement = null, mode = CaptureMode.IDLE) }
                if (error is CancellationException) throw error
                show(error.message ?: "无法启动校准")
            }
        } }
    }
    fun saveCalibration(reference: Double) {
        if (session != null || _ui.value.calibrating) return
        val raw = _ui.value.rawCalibrationMean ?: return show("请先采集10秒稳定声场")
        if (!reference.isFinite() || reference !in 20.0..140.0) return show("请输入20至140之间的参考读数")
        val result = calibrationFromRawMean(raw, reference, System.currentTimeMillis(), calibrationInput)
        calibrationStore.save(result)
        _ui.update { it.copy(calibration = result, rawCalibrationMean = null, measurement = null) }
        show("校准已保存，仅用于相同音频输入配置")
    }
    fun saveGallery(sessionId: String, mediaId: String? = null) = viewModelScope.launch {
        if (_galleryUi.value.running) return@launch
        // Admission happens on Main before suspension, paired with startSession's guard.
        // A long gallery copy must never block the active session's per-second writes.
        if (session != null || _ui.value.busy || ending) {
            show("请等待当前取证结束后再保存到相册")
            return@launch
        }
        _galleryUi.value = GalleryUiState(running = true, sessionId = sessionId)
        var saved = 0; var existing = 0; var failed = 0
        var lastError: String? = null
        try {
            val items = repository.dao.media(sessionId).filter { it.status == "完成" && (mediaId == null || it.id == mediaId) }
            check(items.isNotEmpty()) { "没有可保存的已完成媒体" }
            _galleryUi.update { it.copy(total = items.size) }
            for (item in items) {
                try {
                    if (repository.saveToGallery(item.id).alreadySaved) existing++ else saved++
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { failed++; lastError = error.message }
                _galleryUi.update { it.copy(completed = saved + existing + failed) }
            }
            show("相册保存：新增${saved}项，已存在${existing}项，失败${failed}项" +
                (lastError?.let { "（$it）" } ?: "；相册目录 DCIM/DecibelEvi"))
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { show("保存到相册失败：${error.message}") }
        finally { _galleryUi.update { it.copy(running = false) } }
    }
    fun clearCalibration() {
        if (session != null || _ui.value.calibrating) return
        calibrationStore.clear(); _ui.update { it.copy(calibration = null, rawCalibrationMean = null) }
    }
    override fun onCleared() {
        val ending = leaveCapturePage("取证界面已关闭")
        finalizerScope.launch {
            ending.join()
            audio.stop(); location.release(); capture.release()
            finalizerScope.cancel()
        }
        super.onCleared()
    }
}
