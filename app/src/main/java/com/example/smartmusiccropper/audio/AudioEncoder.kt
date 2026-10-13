package com.example.smartmusiccropper.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Interleaved 16-bit PCM → AAC-LC via MediaCodec, muxed into an .m4a file via MediaMuxer.
 *
 * Android ships no MP3 encoder, so every export is AAC in an MPEG-4 container regardless of input.
 */
class AudioEncoder(
    outputFile: File,
    private val sampleRate: Int,
    private val channels: Int,
) : Closeable {

    private val codec: MediaCodec
    private val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var samplesWritten = 0
    private var framesQueued = 0L
    private var released = false

    init {
        require(channels in 1..2) { "AAC export supports mono or stereo" }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 128_000 else 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) {
            muxer.release()
            throw IOException("This device's AAC encoder couldn't be started.", e)
        }
    }

    /** Queues [frames] interleaved frames from [pcm] starting at frame 0. */
    fun write(pcm: ShortArray, frames: Int) {
        var offset = 0
        val total = frames * channels
        while (offset < total) {
            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val buf = codec.getInputBuffer(inIndex)!!
                buf.clear()
                val capacitySamples = (buf.remaining() / 2 / channels) * channels
                val n = min(total - offset, capacitySamples)
                buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, offset, n)
                val ptsUs = framesQueued * 1_000_000L / sampleRate
                codec.queueInputBuffer(inIndex, 0, n * 2, ptsUs, 0)
                offset += n
                framesQueued += n / channels
            }
            drain(endOfStream = false)
        }
    }

    /** Signals end of input, drains the encoder and finalizes the file. */
    fun finish() {
        while (true) {
            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val ptsUs = framesQueued * 1_000_000L / sampleRate
                codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(endOfStream = false)
        }
        drain(endOfStream = true)
        if (!muxerStarted || samplesWritten == 0) throw IOException("The encoder produced no audio.")
        muxer.stop()
    }

    private fun drain(endOfStream: Boolean) {
        var idlePolls = 0
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idlePolls > MAX_IDLE_POLLS) throw IOException("Encoder stopped responding")
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Encoder format changed twice" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    idlePolls = 0
                    val buf = codec.getOutputBuffer(outIndex)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Codec-specific data already reached the muxer through the output format.
                        info.size = 0
                    }
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, buf, info)
                        samplesWritten++
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun close() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.release() }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val MAX_IDLE_POLLS = 1_000

        private val AAC_SAMPLE_RATES = intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

        /** Picks a sample rate every AAC-LC encoder accepts, resampling if the source rate isn't one. */
        fun encoderSampleRate(sourceRate: Int): Int = when {
            sourceRate in AAC_SAMPLE_RATES -> sourceRate
            sourceRate > 48000 -> 48000
            else -> AAC_SAMPLE_RATES.first { it >= sourceRate }
        }

        /**
         * Decodes [uri] from the start, keeps only the first [cropMs], and encodes that to [outputFile]
         * as .m4a. The decoded PCM streams straight into the encoder, so the full track is never held
         * in memory at once. Returns the duration actually written, in ms.
         */
        suspend fun encodeTrimmed(
            context: Context,
            uri: Uri,
            cropMs: Long,
            outputFile: File,
            onProgress: (Float) -> Unit,
        ): Long {
            var encoder: AudioEncoder? = null
            var resampler: LinearResampler? = null
            var inChannels = 0
            var outChannels = 0
            var targetFrames = 0L
            var framesDone = 0L
            var sourceRate = 0
            try {
                AudioDecoder.decode(
                    context, uri, 0L,
                    onFormat = { fmt ->
                        sourceRate = fmt.sampleRate
                        inChannels = fmt.channels
                        outChannels = min(fmt.channels, 2)
                        targetFrames = cropMs * fmt.sampleRate / 1000
                        val outRate = encoderSampleRate(fmt.sampleRate)
                        if (outRate != fmt.sampleRate) resampler = LinearResampler(fmt.sampleRate, outRate, outChannels)
                        encoder = AudioEncoder(outputFile, outRate, outChannels)
                    },
                ) { pcm, frames, _ ->
                    val take = min(frames.toLong(), targetFrames - framesDone).toInt()
                    if (take > 0) {
                        val rs = resampler
                        if (rs == null && inChannels == outChannels) {
                            encoder!!.write(pcm, take)
                        } else {
                            val floats = PcmResampler.toFloat(pcm, take, inChannels, outChannels)
                            val converted = rs?.process(floats, take) ?: floats
                            val shorts = PcmResampler.toShort(converted)
                            encoder!!.write(shorts, shorts.size / outChannels)
                        }
                        framesDone += take
                        onProgress((framesDone.toFloat() / targetFrames).coerceIn(0f, 1f))
                    }
                    framesDone < targetFrames
                }
                val enc = encoder ?: throw IOException("No audio could be decoded from this file.")
                enc.finish()
                return framesDone * 1000 / sourceRate
            } finally {
                encoder?.close()
            }
        }
    }
}
