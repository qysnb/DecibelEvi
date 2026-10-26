package com.decibelevidence.app.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synchronous codec mode; all calls, including muxer writes, are confined to the GL worker. */
internal class AvRecorder(file: File, val startNs: Long, width: Int, height: Int, codecName: String) {
    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    lateinit var inputSurface: Surface
        private set
    private val pending = ArrayDeque<Sample>()
    private val pcm = ArrayDeque<Pcm>()
    private var pendingBytes = 0
    private var pcmSamples = 0
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var videoEos = false
    private var audioEos = false
    private var audioInputEos = false
    private var lastAudioInputUs = -1L
    private var lastVideoOutputUs = -1L
    private var lastAudioOutputUs = -1L
    private var audioInputEndNs = startNs
    private var videoSamples = 0
    private var audioSamples = 0
    private var released = false
    private val createdNs = SystemClock.elapsedRealtimeNanos()
    private data class Sample(val video: Boolean,val bytes: ByteBuffer,val info: MediaCodec.BufferInfo)
    private data class Pcm(val samples: ShortArray,val firstNs: Long,var offset: Int = 0)

    init {
        try {
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE,if (width*height >= 1920*1080) 10_000_000 else 6_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE,30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES,0)
            }
            video = MediaCodec.createByCodecName(codecName)
            video!!.configure(videoFormat,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = video!!.createInputSurface(); video!!.start()
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,48_000,1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE,128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16_384)
            }
            audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audio!!.configure(audioFormat,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); audio!!.start()
            muxer = MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (error: Throwable) { closeResources(); throw error }
    }

    fun addAudio(samples: ShortArray, firstNs: Long) {
        check(!released && !audioInputEos)
        // Blocks can straddle the start button; trim only the prefix outside this recording.
        val skip = if (firstNs < startNs) (((startNs - firstNs)*48_000 + 999_999_999L)/1_000_000_000L).coerceAtMost(samples.size.toLong()).toInt() else 0
        if (skip == samples.size) return
        val actualNs = firstNs + skip*1_000_000_000L/48_000
        check(actualNs >= audioInputEndNs - 100_000L) { "录音时间戳发生倒退" }
        check(pcmSamples + samples.size-skip <= 48_000*5) { "音频编码队列超过5秒，已终止录像以避免丢失声音" }
        pcm.add(Pcm(samples,firstNs,skip)); pcmSamples += samples.size-skip
        audioInputEndNs = firstNs + samples.size*1_000_000_000L/48_000
        pump()
    }

    /** Audio arrives in 50ms blocks. Do not extend a video through an interrupted input. */
    fun canEncodeFrame(frameNs: Long): Boolean = audioInputEndNs > startNs && frameNs <= audioInputEndNs + 50_000_000L

    fun pump() {
        if (released) return
        drain(video!!,true); drain(audio!!,false)
        feedAudio()
        drain(audio!!,false); drain(video!!,true)
        check(muxerStarted || SystemClock.elapsedRealtimeNanos()-createdNs < 5_000_000_000L) { "音视频编码器未在5秒内准备完成（请确认麦克风和相机持续采集）" }
    }

    private fun feedAudio() {
        val codec = audio!!
        while (pcm.isNotEmpty()) {
            val index = codec.dequeueInputBuffer(0)
            if (index < 0) return
            val block = pcm.first()
            val input = codec.getInputBuffer(index)!!.apply { clear(); order(ByteOrder.nativeOrder()) }
            val count = minOf(input.remaining()/2,block.samples.size-block.offset)
            check(count > 0) { "AAC编码器输入缓冲区为空" }
            val pts = ((block.firstNs-startNs)/1_000 + block.offset*1_000_000L/48_000).coerceAtLeast(lastAudioInputUs+1)
            input.asShortBuffer().put(block.samples,block.offset,count)
            codec.queueInputBuffer(index,0,count*2,pts,0)
            lastAudioInputUs = pts
            block.offset += count; pcmSamples -= count
            if (block.offset == block.samples.size) pcm.removeFirst()
        }
    }

    private fun drain(codec: MediaCodec,isVideo: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info,0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(if (isVideo) videoTrack < 0 else audioTrack < 0) { "编码器意外改变输出格式" }
                    val track = muxer!!.addTrack(codec.outputFormat)
                    if (isVideo) videoTrack = track else audioTrack = track
                    if (videoTrack >= 0 && audioTrack >= 0 && !muxerStarted) {
                        muxer!!.start(); muxerStarted = true
                        while (pending.isNotEmpty()) write(pending.removeFirst())
                        pendingBytes = 0
                    }
                }
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val previous = if (isVideo) lastVideoOutputUs else lastAudioOutputUs
                            check(info.presentationTimeUs >= previous) { "编码器输出时间戳发生倒退" }
                            if (isVideo) lastVideoOutputUs = info.presentationTimeUs else lastAudioOutputUs = info.presentationTimeUs
                            val output = codec.getOutputBuffer(index)!!
                            output.position(info.offset); output.limit(info.offset+info.size)
                            // Copy before releasing the codec-owned buffer. Both track formats may arrive in either order.
                            val bytes = ByteBuffer.allocateDirect(info.size).apply { put(output); flip() }
                            val copy = MediaCodec.BufferInfo().apply { set(0,info.size,info.presentationTimeUs.coerceAtLeast(0),info.flags) }
                            val sample = Sample(isVideo,bytes,copy)
                            if (muxerStarted) write(sample) else {
                                check(pendingBytes+info.size <= 16*1024*1024) { "等待音视频轨道时缓存超过16MB" }
                                pending.add(sample); pendingBytes += info.size
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            if (isVideo) videoEos = true else audioEos = true
                            return
                        }
                    } finally { codec.releaseOutputBuffer(index,false) }
                } else return
            }
        }
    }

    private fun write(sample: Sample) {
        muxer!!.writeSampleData(if (sample.video) videoTrack else audioTrack,sample.bytes,sample.info)
        if (sample.video) videoSamples++ else audioSamples++
    }

    /** Completes only after both EOS markers and a successful muxer.stop(). */
    fun finish(): Result<Unit> {
        if (released) return Result.failure(IllegalStateException("录像已被终止"))
        val result = runCatching {
            video!!.signalEndOfInputStream()
            val deadline = SystemClock.elapsedRealtimeNanos()+8_000_000_000L
            while (!videoEos || !audioEos) {
                feedAudio(); drain(video!!,true); drain(audio!!,false)
                if (pcm.isEmpty() && !audioInputEos) {
                    val index = audio!!.dequeueInputBuffer(0)
                    if (index >= 0) {
                        audio!!.queueInputBuffer(index,0,0,maxOf(lastAudioInputUs+1,(audioInputEndNs-startNs)/1000),MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        audioInputEos = true
                    }
                }
                check(SystemClock.elapsedRealtimeNanos() < deadline) { "等待音视频编码结束超时" }
                if (!videoEos || !audioEos) Thread.sleep(3)
            }
            check(muxerStarted && videoSamples > 0 && audioSamples > 0) { "录像缺少有效的视频帧或音频帧" }
            // Without an explicit EOS timestamp MediaMuxer repeats the preceding frame interval.
            // A dropped/delayed penultimate frame could otherwise freeze the last image for hundreds of ms.
            val videoEndUs = maxOf(lastVideoOutputUs + 1, (audioInputEndNs - startNs) / 1000)
            val end = MediaCodec.BufferInfo().apply { set(0,0,videoEndUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
            muxer!!.writeSampleData(videoTrack,ByteBuffer.allocateDirect(0),end)
            muxer!!.stop(); muxerStarted = false
        }
        val closeFailure = closeResources()
        return if (result.isSuccess && closeFailure != null) Result.failure(closeFailure) else result
    }

    fun abort() { closeResources() }

    private fun closeResources(): Throwable? {
        if (released) return null
        released = true
        video?.let { runCatching { it.stop() }; runCatching { it.release() } }; video = null
        audio?.let { runCatching { it.stop() }; runCatching { it.release() } }; audio = null
        if (::inputSurface.isInitialized) runCatching { inputSurface.release() }
        var closeFailure: Throwable? = null
        muxer?.let {
            if (muxerStarted) runCatching { it.stop() }
            runCatching { it.release() }.onFailure { failure -> closeFailure = failure }
        }; muxer = null
        pending.clear(); pcm.clear()
        return closeFailure
    }
}
