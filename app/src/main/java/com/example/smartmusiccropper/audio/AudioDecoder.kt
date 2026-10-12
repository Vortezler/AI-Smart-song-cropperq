package com.example.smartmusiccropper.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/** Thrown for files the v1 pipeline can't handle (wrong codec, no audio, too long). */
class UnsupportedAudioException(message: String) : Exception(message)

/** Thrown when a track exceeds the v1 length cap. */
class TrackTooLongException : Exception("Track too long for this version (max 20 minutes).")

/** What the extractor reports about a file before decoding. */
data class AudioInfo(
    val mime: String,
    val sampleRate: Int,
    val channels: Int,
    /** Container-reported duration, or -1 when unknown. */
    val durationUs: Long,
)

/** Format of the PCM the decoder actually produces (may differ from [AudioInfo]). */
data class PcmFormat(val sampleRate: Int, val channels: Int)

/** The decoded tail of a track, ready for the model and the waveform view. */
class TailPcm(
    /** 16 kHz mono samples in [-1, 1]. */
    val samples: FloatArray,
    /** Where [samples] starts, relative to the start of the track. */
    val startMs: Long,
    /** Decoded length of the whole track (end of [samples]). */
    val trackDurationMs: Long,
)

/** MediaExtractor + MediaCodec → 16-bit PCM, for either the whole file or just its tail. */
object AudioDecoder {

    /** v1 supports MP3 and WAV. MediaExtractor reports PCM WAV data as audio/raw. */
    private val SUPPORTED_MIMES = setOf(MediaFormat.MIMETYPE_AUDIO_MPEG, MediaFormat.MIMETYPE_AUDIO_RAW)

    const val MAX_DURATION_US = 20L * 60 * 1_000_000

    private const val TIMEOUT_US = 10_000L
    /** Give up if the codec makes no progress for this many consecutive polls (~10 s). */
    private const val MAX_IDLE_POLLS = 1_000

    // AudioFormat.ENCODING_PCM_24BIT_PACKED / ENCODING_PCM_32BIT are API 31+ constants.
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22

    /** Reads container metadata and validates that the file is something v1 can process. */
    fun probe(context: Context, uri: Uri): AudioInfo {
        val extractor = MediaExtractor()
        try {
            setDataSource(extractor, context, uri)
            val track = findAudioTrack(extractor)
                ?: throw UnsupportedAudioException("This file has no audio track.")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime !in SUPPORTED_MIMES) {
                throw UnsupportedAudioException("This file type isn't supported. This version handles MP3 and WAV files only.")
            }
            var durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            if (durationUs <= 0) durationUs = retrieverDurationUs(context, uri)
            return AudioInfo(
                mime = mime,
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                durationUs = durationUs,
            )
        } finally {
            extractor.release()
        }
    }

    /**
     * Decodes roughly the last [tailUs] of the track to 16 kHz mono float PCM.
     *
     * Seeks the extractor near the end when the duration is known. Either way only a rolling window
     * of the most recent [tailUs] is kept, so an imprecise seek (or an unknown duration, which means
     * decoding from the start) can't blow up memory. The length cap is enforced as it goes.
     */
    suspend fun decodeTail(context: Context, uri: Uri, info: AudioInfo, tailUs: Long): TailPcm {
        val seekUs = if (info.durationUs > 0) max(0L, info.durationUs - tailUs) else 0L
        val keepSamples = (tailUs * PcmResampler.MODEL_SAMPLE_RATE / 1_000_000).toInt()

        var resampler: LinearResampler? = null
        var channels = 1
        var firstPtsUs = -1L
        var droppedSamples = 0L
        var buffer = FloatArray(keepSamples * 2)
        var size = 0

        decode(
            context, uri, seekUs,
            onFormat = { fmt ->
                channels = fmt.channels
                resampler = LinearResampler(fmt.sampleRate, PcmResampler.MODEL_SAMPLE_RATE, 1)
            },
        ) { pcm, frames, ptsUs ->
            if (ptsUs > MAX_DURATION_US) throw TrackTooLongException()
            if (firstPtsUs < 0) firstPtsUs = ptsUs
            val mono = PcmResampler.downmixToMono(pcm, frames, channels)
            val resampled = resampler!!.process(mono)
            if (size + resampled.size > buffer.size) {
                // Keep only the most recent [keepSamples].
                val drop = (size + resampled.size - keepSamples).coerceIn(0, size)
                System.arraycopy(buffer, drop, buffer, 0, size - drop)
                size -= drop
                droppedSamples += drop
                if (size + resampled.size > buffer.size) buffer = buffer.copyOf(size + resampled.size)
            }
            System.arraycopy(resampled, 0, buffer, size, resampled.size)
            size += resampled.size
            true
        }

        if (size == 0) throw IOException("No audio could be decoded from this file.")
        val excess = (size - keepSamples).coerceAtLeast(0)
        droppedSamples += excess
        size -= excess
        val startMs = max(0L, firstPtsUs) / 1000 + droppedSamples * 1000 / PcmResampler.MODEL_SAMPLE_RATE
        val durationMs = startMs + size * 1000L / PcmResampler.MODEL_SAMPLE_RATE
        if (durationMs * 1000 > MAX_DURATION_US) throw TrackTooLongException()
        return TailPcm(buffer.copyOfRange(excess, excess + size), startMs, durationMs)
    }

    /**
     * Decodes the file to interleaved 16-bit PCM, starting from the sync frame at or before [startUs].
     *
     * [onFormat] is called once, before the first chunk. [onChunk] receives a reused buffer holding
     * `frames * channels` samples plus the chunk's presentation time; copy it if you need to keep
     * it, and return false to stop decoding early.
     */
    suspend fun decode(
        context: Context,
        uri: Uri,
        startUs: Long = 0L,
        onFormat: (PcmFormat) -> Unit,
        onChunk: (pcm: ShortArray, frames: Int, ptsUs: Long) -> Boolean,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            setDataSource(extractor, context, uri)
            val track = findAudioTrack(extractor)
                ?: throw UnsupportedAudioException("This file has no audio track.")
            extractor.selectTrack(track)
            val trackFormat = extractor.getTrackFormat(track)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime !in SUPPORTED_MIMES) {
                throw UnsupportedAudioException("This file type isn't supported. This version handles MP3 and WAV files only.")
            }
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(trackFormat, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var outFormat: MediaFormat? = null
            var pcmFormat: PcmFormat? = null
            var scratch = ShortArray(0)
            var inputDone = false
            var idlePolls = 0

            while (true) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        idlePolls = 0
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0) {
                            val fmt = outFormat ?: codec.outputFormat.also { outFormat = it }
                            val pf = pcmFormat ?: PcmFormat(
                                sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            ).also {
                                if (it.sampleRate <= 0 || it.channels <= 0) throw IOException("Invalid decoder output format")
                                pcmFormat = it
                                onFormat(it)
                            }
                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val encoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                            val needed = sampleCount(info.size, encoding)
                            if (scratch.size < needed) scratch = ShortArray(needed)
                            val samples = readAsShorts(outBuf, encoding, scratch)
                            codec.releaseOutputBuffer(outIndex, false)
                            val frames = samples / pf.channels
                            if (frames > 0 && !onChunk(scratch, frames, info.presentationTimeUs)) return
                        } else {
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                        if (eos) return
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        idlePolls = 0
                        outFormat = codec.outputFormat
                    }
                    else -> {
                        if (inputDone && ++idlePolls > MAX_IDLE_POLLS) {
                            throw IOException("Decoder stopped responding")
                        }
                    }
                }
            }
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
        }
    }

    private fun setDataSource(extractor: MediaExtractor, context: Context, uri: Uri) {
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: IOException) {
            throw IOException("Couldn't open this file. It may be corrupt or in an unsupported format.", e)
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }

    private fun retrieverDurationUs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.let { it * 1000 } ?: -1L
        } catch (e: RuntimeException) {
            -1L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun sampleCount(bytes: Int, encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> bytes
        AudioFormat.ENCODING_PCM_FLOAT, ENCODING_PCM_32BIT -> bytes / 4
        ENCODING_PCM_24BIT_PACKED -> bytes / 3
        else -> bytes / 2
    }

    /** Converts one decoder output buffer to 16-bit samples in [out]; returns the sample count. */
    private fun readAsShorts(buf: ByteBuffer, encoding: Int, out: ShortArray): Int {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.asFloatBuffer()
                val n = fb.remaining()
                for (i in 0 until n) out[i] = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                n
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val n = buf.remaining()
                for (i in 0 until n) out[i] = (((buf.get().toInt() and 0xFF) - 128) shl 8).toShort()
                n
            }
            ENCODING_PCM_24BIT_PACKED -> {
                val n = buf.remaining() / 3
                for (i in 0 until n) {
                    buf.get() // drop the least significant byte
                    val lo = buf.get().toInt() and 0xFF
                    val hi = buf.get().toInt()
                    out[i] = ((hi shl 8) or lo).toShort()
                }
                n
            }
            ENCODING_PCM_32BIT -> {
                val ib = buf.asIntBuffer()
                val n = ib.remaining()
                for (i in 0 until n) out[i] = (ib.get() shr 16).toShort()
                n
            }
            else -> {
                val sb = buf.asShortBuffer()
                val n = sb.remaining()
                sb.get(out, 0, n)
                n
            }
        }
    }
}
