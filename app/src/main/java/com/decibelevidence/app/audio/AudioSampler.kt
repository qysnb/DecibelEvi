package com.decibelevidence.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.decibelevidence.app.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import com.decibelevidence.core.samplesToNanos

/** One input shared by analysis and the video encoder. No independent recording microphone. */
class AudioSampler(private val context: Context, private val onError: (String) -> Unit) {
    data class Input(val key: String, val source: Int, val sampleRate: Int = 48000)
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private var selectedDevice: AudioDeviceInfo? = null
    private var expectedKey = ""
    private var source = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private val routeListener = AudioRouting.OnRoutingChangedListener { routing ->
        val device = routing.routedDevice
        if (running.get() && device != null && key(device) != expectedKey) fail("麦克风输入已变化，本次取证结束，请重新校准后继续")
    }
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val id = recorder?.audioSessionId ?: return
            if (running.get() && configs.any { it.clientAudioSessionId == id && it.isClientSilenced })
                fail("麦克风被系统或其他应用占用，本次采集已停止")
        }
    }
    @SuppressLint("MissingPermission")
    fun prepare(): Input {
        check(recorder == null)
        source = if (manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "设备不支持48kHz单声道采样" }
        val record = AudioRecord.Builder().setAudioSource(source).setAudioFormat(AudioFormat.Builder()
            .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 4, 48000)).build()
        // Own the native resource before any validation or routing call can throw.
        recorder = record
        try {
        check(record.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
        selectedDevice = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        selectedDevice?.let { check(record.setPreferredDevice(it)) { "无法选择手机内置麦克风" } }
        expectedKey = key(selectedDevice)
        record.addOnRoutingChangedListener(routeListener, main)
        record.registerAudioRecordingCallback(context.mainExecutor, recordingCallback)
        return Input(expectedKey, source)
        } catch (error: Exception) { stop(); throw error }
    }
    private fun key(device: AudioDeviceInfo?) = "${Build.MANUFACTURER}/${Build.MODEL};source=$source;rate=48000;type=${device?.type ?: -1};address=${device?.address.orEmpty()}"

    fun start(onPcm: (ShortArray, Int, Long) -> Unit) {
        val record = checkNotNull(recorder)
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风启动失败" }
        running.set(true)
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val pcm = ShortArray(2400)
            val timestamp = AudioTimestamp()
            var anchorNs: Long? = null
            var frame = 0L
            var reportNs = 0L
            try {
                while (running.get()) {
                    val readStartedNs = SystemClock.elapsedRealtimeNanos()
                    val count = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    val readFinishedNs = SystemClock.elapsedRealtimeNanos()
                    if (!running.get()) break
                    check(count > 0) { "麦克风读取失败（$count）" }
                    if (anchorNs == null) {
                        anchorNs = if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS)
                            timestamp.nanoTime - samplesToNanos(timestamp.framePosition, 48000)
                        else SystemClock.elapsedRealtimeNanos() - samplesToNanos(count.toLong(), 48000)
                    }
                    val firstNs = anchorNs!! + samplesToNanos(frame, 48000)
                    val sampleEndNs = firstNs + samplesToNanos(count.toLong(), 48000)
                    audioTimingError(sampleEndNs, readFinishedNs)?.let { error(it) }
                    onPcm(pcm, count, firstNs)
                    frame += count
                    val finishedNs = SystemClock.elapsedRealtimeNanos()
                    if (BuildConfig.DEBUG && finishedNs - reportNs > 1_000_000_000L) {
                        val nativeStatus = record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME)
                        Log.i("EvidenceAudio", "frames=$frame count=$count readMs=${(readFinishedNs-readStartedNs)/1_000_000} processMs=${(finishedNs-readFinishedNs)/1_000_000} logicalEndLagMs=${(finishedNs-anchorNs!!-samplesToNanos(frame,48000))/1_000_000} nativeStatus=$nativeStatus nativeFrame=${timestamp.framePosition} nativeAgeMs=${(finishedNs-timestamp.nanoTime)/1_000_000}")
                        reportNs = finishedNs
                    }
                }
            } catch (error: Exception) { if (running.get()) fail(error.message ?: "音频采集失败") }
        }, "evidence-audio").apply { start() }
    }
    private fun fail(message: String) { if (running.compareAndSet(true, false)) main.post { onError(message) } }
    fun stop() {
        running.set(false)
        val record = recorder ?: return
        runCatching { record.stop() }
        if (Thread.currentThread() !== thread) {
            try { thread?.join(1500) } catch (interrupted: InterruptedException) { Thread.currentThread().interrupt() }
        }
        runCatching { record.removeOnRoutingChangedListener(routeListener) }
        runCatching { record.unregisterAudioRecordingCallback(recordingCallback) }
        runCatching { record.release() }
        recorder = null
        thread = null
    }
}
