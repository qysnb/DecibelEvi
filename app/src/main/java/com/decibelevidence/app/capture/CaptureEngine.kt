package com.decibelevidence.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.decibelevidence.app.BuildConfig
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import com.decibelevidence.app.model.OverlayState
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Camera2 -> OES -> one watermarked FBO -> preview/JPEG/H264. No microphone is opened here:
 * acceptAudio receives the exact mono PCM16 / 48 kHz blocks used by the measurement engine.
 * Media/EGL work runs on [thread]; callers may close camera input immediately. Callbacks use main.
 * Caller owns preview surfaces and files, and should lock the Activity orientation during video.
 */
class CaptureEngine(
    context: Context,
    private val overlayAt: (Long) -> OverlayState,
    private val onError: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("EvidenceCameraGL").apply { start() }
    private val worker = Handler(thread.looper)
    private val manager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audioGate = Any()
    @Volatile private var released = false
    private var audioTarget = 0L
    private val queuedAudioSamples = AtomicInteger()
    private var recordingId = 0L
    private var renderer: EglRenderer? = null
    @Volatile private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false
    @Volatile private var cameraWanted = false
    private val inputGeneration = AtomicInteger()
    private var cameraGeneration = 0
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0
    private var displayRotation = Surface.ROTATION_0
    private var sensorOrientation = 90
    private var cameraSize = Size(1280,720)
    private var encoderName = ""
    private var clock = CaptureClock(false)
    private var lastFrameNs = 0L
    private var lastTimingReportNs = 0L
    private var lastEncodedNs = Long.MIN_VALUE
    private var recorder: AvRecorder? = null
    private var recordingFailure: Throwable? = null

    private val pump = object : Runnable {
        override fun run() {
            if (released || recorder == null) return
            try { recorder?.pump() } catch (error: Throwable) { failRecording(error) }
            if (recorder != null) worker.postDelayed(this,10)
        }
    }

    fun attachPreview(surface: Surface, width: Int, height: Int, rotation: Int) {
        if (released) return
        worker.post {
            if (released) return@post
            if (width <= 0 || height <= 0 || !surface.isValid) { error("预览画布尚未就绪"); return@post }
            val changedRotation = displayRotation != rotation
            previewSurface = surface; previewWidth = width; previewHeight = height
            if (changedRotation && recorder != null) { error("录像期间请保持屏幕方向锁定"); return@post }
            displayRotation = rotation
            try {
                if (changedRotation && renderer != null) {
                    val wanted = cameraWanted; closeCamera(); renderer?.close(); renderer = null
                    if (wanted) openCamera()
                } else renderer?.attachPreview(surface,width,height)
            } catch (failure: Throwable) { error("连接预览失败: ${failure.message}") }
        }
    }

    fun detachPreview() {
        if (released) return
        worker.post { previewSurface = null; runCatching { renderer?.detachPreview() }.onFailure { error("释放预览失败: ${it.message}") } }
    }

    fun startCamera() {
        if (released) return
        val generation = inputGeneration.get()
        cameraWanted = true
        worker.post {
            if (released || !cameraWanted || generation != inputGeneration.get()) return@post
            try { openCamera() } catch (failure: Throwable) { closeCamera(); error("启动相机失败: ${failure.message}") }
        }
    }

    /** Stop camera input while retaining encoder/EGL resources for muxer finalization. */
    fun closeInput() {
        if (released) return
        cameraWanted = false
        inputGeneration.incrementAndGet()
        synchronized(audioGate) { audioTarget = 0L }
        // CameraDevice.close is thread-safe; do not wait behind JPEG encoding on worker.
        runCatching { camera?.close() }
        worker.postAtFrontOfQueue { closeCamera() }
    }

    fun stopCamera() {
        if (released) return
        closeInput()
        worker.post {
            if (recorder != null) failRecording(IllegalStateException("相机已停止，录像未完成"))
            closeCamera()
            runCatching { renderer?.close() }.onFailure { error("释放相机画布失败: ${it.message}") }
            renderer = null
        }
    }

    fun takePhoto(file: File, onComplete: (Result<Unit>) -> Unit) =
        takeEvidencePhoto(file, overlayAt) { onComplete(it.map { Unit }) }

    fun takeEvidencePhoto(file: File, photoOverlay: (Long) -> OverlayState, onComplete: (Result<OverlayState>) -> Unit) {
        if (released) { main.post { onComplete(Result.failure(IllegalStateException("相机已释放"))) }; return }
        val generation = inputGeneration.get()
        worker.post {
            val result = runCatching {
                check(!released && cameraWanted && generation == inputGeneration.get()) { "相机采集已停止" }
                val gl = checkNotNull(renderer) { "请先启动相机" }
                check(camera != null && gl.hasFrame) { "相机尚未获得画面，请稍候" }
                check(SystemClock.elapsedRealtimeNanos() - lastFrameNs in 0..250_000_000L) { "相机画面已过期，请重新开始取证" }
                val captured = photoOverlay(lastFrameNs)
                gl.compose(captured,lastFrameNs,cameraSize.width,cameraSize.height,rotationDegrees())
                val bitmap = gl.photo()
                try {
                    FileOutputStream(file).use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG,94,output)) { "照片JPEG编码失败" }
                        output.flush(); output.fd.sync()
                    }
                } finally { bitmap.recycle() }
                captured
            }
            main.post { onComplete(result) }
        }
    }

    fun startVideo(file: File, startNs: Long, onStarted: (Result<Unit>) -> Unit) {
        if (released) { complete(onStarted,Result.failure(IllegalStateException("相机已释放"))); return }
        val generation = inputGeneration.get()
        worker.post {
            val result = runCatching {
                check(!released && cameraWanted && generation == inputGeneration.get()) { "相机采集已停止" }
                check(recorder == null) { "录像正在进行" }
                val gl = checkNotNull(renderer) { "请先启动相机" }
                check(camera != null && gl.hasFrame) { "相机尚未获得画面，请稍候" }
                check(startNs > 0 && startNs <= SystemClock.elapsedRealtimeNanos()) { "录像起点时间无效" }
                recordingFailure = null
                val next = AvRecorder(file,startNs,gl.width,gl.height,encoderName)
                try {
                    check(cameraWanted && generation == inputGeneration.get()) { "相机采集已停止" }
                    gl.attachEncoder(next.inputSurface)
                } catch (error: Throwable) { next.abort(); throw error }
                recorder = next; lastEncodedNs = Long.MIN_VALUE
                synchronized(audioGate) { recordingId++; audioTarget = recordingId }
                worker.post(pump)
                Unit
            }
            complete(onStarted,result)
        }
    }

    /** Input is copied before returning: AudioRecord callers may immediately reuse their buffer. */
    fun acceptAudio(pcm: ShortArray, count: Int, firstSampleNs: Long) {
        if (count <= 0) return
        synchronized(audioGate) {
            if (released || audioTarget == 0L) return
            val target = audioTarget
            if (count > pcm.size) {
                audioTarget = 0L
                worker.post { if (recordingId == target) failRecording(IllegalArgumentException("音频数据长度无效")) }
                return
            }
            if (queuedAudioSamples.addAndGet(count) > 48_000*5) {
                queuedAudioSamples.addAndGet(-count); audioTarget = 0L
                worker.post { if (recordingId == target) failRecording(IllegalStateException("音频采集队列超过5秒，录像已终止")) }
                return
            }
            val copy = pcm.copyOf(count)
            worker.post {
                queuedAudioSamples.addAndGet(-count)
                if (recordingId == target && recorder != null) {
                    try { recorder?.addAudio(copy,firstSampleNs) } catch (error: Throwable) { failRecording(error) }
                }
            }
        }
    }

    fun stopVideo(onComplete: (Result<Unit>) -> Unit) {
        if (released) { complete(onComplete,Result.failure(IllegalStateException("相机已释放"))); return }
        synchronized(audioGate) {
            // Posting under the same gate keeps every accepted audio block before the EOS command.
            audioTarget = 0L
            worker.post {
                worker.removeCallbacks(pump)
                val current = recorder
                val result = if (current == null) recordingFailure?.let { Result.failure(it) } ?: Result.success(Unit) else {
                    runCatching { renderer?.detachEncoder() }
                    recorder = null
                    current.finish()
                }
                recordingFailure = result.exceptionOrNull()
                complete(onComplete,result)
            }
        }
    }

    fun release() {
        synchronized(audioGate) {
            if (released) return
            released = true; audioTarget = 0L
            worker.post {
                cameraWanted = false; worker.removeCallbacks(pump)
                runCatching { renderer?.detachEncoder() }
                recorder?.abort(); recorder = null
                closeCamera()
                runCatching { renderer?.close() }; renderer = null
                thread.quitSafely()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!cameraWanted || camera != null || opening || released) return
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: throw IllegalStateException("没有可用的后置相机")
        val characteristics = manager.getCameraCharacteristics(id)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val configuration = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("无法读取相机输出格式")
        val sizes = configuration.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val swap = rotationDegrees() % 180 != 0
        if (renderer == null) {
            val choices = listOf(Size(1920,1080),Size(1280,720))
            var selected: Triple<Size,Size,String>? = null
            for (target in choices) {
                val stream = sizes.filter { it.width == target.width && it.height == target.height }
                    .sortedByDescending { it.width.toLong()*it.height }
                    .firstOrNull { configuration.getOutputMinFrameDuration(SurfaceTexture::class.java,it) <= 34_000_000L } ?: continue
                val output = if (swap) Size(target.height,target.width) else target
                val encoder = findEncoder(output.width,output.height) ?: continue
                selected = Triple(stream,output,encoder); break
            }
            val choice = selected ?: throw IllegalStateException("设备不支持720p/1080p 30帧H.264录像")
            cameraSize = choice.first; encoderName = choice.third
            val gl = EglRenderer(choice.second.width,choice.second.height)
            renderer = gl
            gl.cameraInput.setDefaultBufferSize(cameraSize.width,cameraSize.height)
            gl.cameraInput.setOnFrameAvailableListener({ if (renderer === gl) frame() },worker)
            previewSurface?.takeIf { it.isValid }?.let { renderer!!.attachPreview(it,previewWidth,previewHeight) }
        }
        clock = CaptureClock(characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
        if (BuildConfig.DEBUG) Log.i("EvidenceCamera", "timestampSource=${characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)} sensorOrientation=$sensorOrientation output=${renderer?.width}x${renderer?.height}")
        val inputVersion = inputGeneration.get()
        if (!cameraWanted) return
        val generation = ++cameraGeneration
        opening = true
        manager.openCamera(id,object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (released || !cameraWanted || inputVersion != inputGeneration.get() || generation != cameraGeneration) { device.close(); return }
                opening = false
                camera = device
                try { createSession(device,generation,characteristics,inputVersion) } catch (failure: Throwable) { closeCamera(); error("配置相机失败: ${failure.message}") }
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); if (cameraWanted && inputVersion == inputGeneration.get() && generation == cameraGeneration) cameraFailed("后置相机已断开") }
            override fun onError(device: CameraDevice, code: Int) { device.close(); if (cameraWanted && inputVersion == inputGeneration.get() && generation == cameraGeneration) cameraFailed("后置相机错误 $code") }
        },worker)
    }

    @Suppress("DEPRECATION")
    private fun createSession(device: CameraDevice,generation: Int,characteristics: CameraCharacteristics,inputVersion: Int) {
        val surface = renderer!!.cameraSurface
        device.createCaptureSession(listOf(surface),object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (released || !cameraWanted || inputVersion != inputGeneration.get() || generation != cameraGeneration || camera !== device) { configured.close(); return }
                session = configured
                try {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                        val fps = ranges.filter { it.contains(30) }.minByOrNull { (it.upper-it.lower)*100 + abs(it.upper-30) }
                        if (fps != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fps)
                    }.build()
                    configured.setRepeatingRequest(request,null,worker)
                } catch (failure: Throwable) { cameraFailed("启动相机预览失败: ${failure.message}") }
            }
            override fun onConfigureFailed(configured: CameraCaptureSession) {
                configured.close(); if (cameraWanted && inputVersion == inputGeneration.get() && generation == cameraGeneration) cameraFailed("相机输出会话配置失败")
            }
        },worker)
    }

    private fun frame() {
        val gl = renderer ?: return
        if (released || !cameraWanted || camera == null) return
        try {
            val raw = gl.updateFrame()
            val arrivedNs = SystemClock.elapsedRealtimeNanos()
            val timestamp = clock.map(raw,arrivedNs)
            if (BuildConfig.DEBUG && arrivedNs - lastTimingReportNs > 1_000_000_000L) {
                Log.i("EvidenceCamera", "frameAgeMs=${(arrivedNs-timestamp)/1_000_000}")
                lastTimingReportNs = arrivedNs
            }
            lastFrameNs = timestamp
            gl.compose(overlayAt(timestamp),timestamp,cameraSize.width,cameraSize.height,rotationDegrees())
            recorder?.let { current ->
                if (timestamp >= current.startNs && current.canEncodeFrame(timestamp) &&
                    (lastEncodedNs == Long.MIN_VALUE || timestamp-lastEncodedNs >= 30_000_000L)) {
                    // Drain before swap to avoid blocking EGL behind a full codec output queue.
                    current.pump(); gl.encode(timestamp-current.startNs); lastEncodedNs = timestamp; current.pump()
                }
            }
            try { gl.showPreview() } catch (failure: Throwable) {
                // SurfaceView can disappear during rotation before its detach callback is handled.
                // Camera/encoder continue; Activity.onStop owns the background interruption policy.
                gl.detachPreview()
                if (BuildConfig.DEBUG) Log.d("EvidenceCamera", "预览等待重新连接: ${failure.message}")
            }
        } catch (failure: Throwable) {
            cameraFailed("画面采集失败: ${failure.message}")
        }
    }

    private fun failRecording(failure: Throwable) {
        synchronized(audioGate) { audioTarget = 0L }
        worker.removeCallbacks(pump)
        runCatching { renderer?.detachEncoder() }
        recorder?.abort(); recorder = null; recordingFailure = failure
        error("录像失败: ${failure.message ?: failure.javaClass.simpleName}")
    }
    private fun cameraFailed(message: String) {
        val wasRecording = recorder != null
        if (wasRecording) failRecording(IllegalStateException(message))
        closeCamera()
        if (!wasRecording) error(message)
    }
    private fun closeCamera() {
        cameraGeneration++; opening = false
        runCatching { session?.stopRepeating() }; runCatching { session?.abortCaptures() }
        session?.close(); session = null; camera?.close(); camera = null
    }
    private fun rotationDegrees(): Int {
        val display = when(displayRotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        return (sensorOrientation-display+360)%360
    }
    private fun findEncoder(width: Int,height: Int): String? = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { codec ->
        codec.isEncoder && codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC,true) } && runCatching {
            val caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) && caps.videoCapabilities.areSizeAndRateSupported(width,height,30.0)
        }.getOrDefault(false)
    }?.name
    private fun error(message: String) { main.post { onError(message) } }
    private fun complete(callback: (Result<Unit>) -> Unit,result: Result<Unit>) { main.post { callback(result) } }
}
