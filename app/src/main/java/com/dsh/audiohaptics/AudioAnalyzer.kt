package com.dsh.audiohaptics

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * 把视频/音频文件里的音轨解成 PCM，一次性算出整段强度包络。
 * 这就是原 App "预分析 + 缓存" 的思路：播放时只查表，不做实时 DSP。
 */
object AudioAnalyzer {

    class Result(
        val envelope: IntArray,
        val durationMs: Long,
        val sampleRate: Int,
        val channels: Int
    )

    fun analyze(
        context: Context,
        uri: Uri,
        frameMs: Int,
        onProgress: ((Int) -> Unit)? = null
    ): Result? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = try {
                    f.getString(MediaFormat.KEY_MIME)
                } catch (e: Exception) {
                    null
                }
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null

            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val isFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val samplesPerFrame =
                (sampleRate.toLong() * frameMs / 1000L).toInt().coerceAtLeast(1) * channels

            val out = ArrayList<Int>(2048)
            var acc = 0.0
            var count = 0
            var smoothed = 0.0

            fun pushFrame() {
                val rms = if (count > 0) Math.sqrt(acc / count) else 0.0
                val target = Envelope.levelFromRms(rms)
                smoothed = Envelope.smooth(smoothed, target)
                out.add(Envelope.quantize(smoothed))
                acc = 0.0
                count = 0
            }

            fun pushSample(s: Double) {
                acc += s * s
                count++
                if (count >= samplesPerFrame) pushFrame()
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastPct = -1
            var guard = 0

            while (!outputDone && guard < 5_000_000) {
                guard++

                if (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = dec.getInputBuffer(inIndex)
                        if (buf == null) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = dec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val buf = dec.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bb = buf.order(ByteOrder.nativeOrder())
                        if (isFloat) {
                            val fb = bb.asFloatBuffer()
                            while (fb.hasRemaining()) pushSample(fb.get().toDouble())
                        } else {
                            val sb = bb.asShortBuffer()
                            while (sb.hasRemaining()) pushSample(sb.get() / 32768.0)
                        }
                    }
                    dec.releaseOutputBuffer(outIndex, false)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true

                    if (onProgress != null && durationUs > 0 && info.presentationTimeUs > 0) {
                        val pct = ((info.presentationTimeUs * 100L) / durationUs).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }

            if (count > 0) pushFrame()

            return Result(out.toIntArray(), durationUs / 1000L, sampleRate, channels)

        } catch (e: Exception) {
            return null
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
            }
            try {
                codec?.release()
            } catch (e: Exception) {
            }
            try {
                extractor.release()
            } catch (e: Exception) {
            }
        }
    }
}
