package com.ali.menbaradkshk.util

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 🎛️ دمج صوتيات **بأي صيغ** في ملف واحد — الحل الجذري لقيد «MP3 فقط»:
 *
 * - كل ملف يُفَكّ إلى PCM عبر MediaCodec (يدعم mp3/m4a/aac/ogg/opus/wav/
 *   amr/flac وكل ما يفكّه الجهاز أصلاً).
 * - يُوحَّد الصوت إلى 44.1kHz ستيريو 16-bit (مضاعفة الأحادي، إسقاط القنوات
 *   الزائدة، إعادة عيّنة خطية ذات حالة تحفظ استمرارية الطور بين الدُفعات).
 * - ثم يُرمَّز AAC-LC ‏128kbps في حاوية MP4 (‏.m4a) عبر MediaMuxer.
 *
 * لا مكتبات خارجية، ويعمل تدفّقياً (لا يُحمَّل الملف كاملاً في الذاكرة).
 * نتيجة الدمج صيغة واحدة دائماً: audio/mp4 بامتداد .m4a.
 */
object AudioTranscodeMerger {
    private const val TARGET_SAMPLE_RATE = 44_100
    private const val TARGET_CHANNELS = 2
    private const val BIT_RATE = 128_000
    private const val TIMEOUT_US = 10_000L
    private const val BYTES_PER_FRAME = 2 * TARGET_CHANNELS

    const val OUTPUT_MIME = "audio/mp4"
    const val OUTPUT_EXTENSION = "m4a"

    /** صيغة غير قابلة للفك على هذا الجهاز (تصل رسالتها للمستخدم كما هي). */
    class UnsupportedAudioException(message: String) : Exception(message)

    /**
     * يدمج [inputs] بالترتيب في ملف M4A واحد عند [outputPath].
     * يرمي [UnsupportedAudioException] برسالة عربية تسمّي الملف المتعذّر.
     */
    fun mergeToM4a(inputs: List<File>, outputPath: String): File {
        require(inputs.isNotEmpty()) { "لا ملفات للدمج." }
        val output = File(outputPath)
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(
            MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                TARGET_SAMPLE_RATE,
                TARGET_CHANNELS,
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            },
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        encoder.start()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        var totalFrames = 0L
        val encoderInfo = MediaCodec.BufferInfo()

        fun drainEncoder(untilEos: Boolean) {
            while (true) {
                val index = encoder.dequeueOutputBuffer(
                    encoderInfo,
                    if (untilEos) TIMEOUT_US else 0L,
                )
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    index >= 0 -> {
                        val encoded = encoder.getOutputBuffer(index) ?: continue
                        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            encoderInfo.size = 0
                        }
                        if (encoderInfo.size > 0 && muxerStarted) {
                            encoded.position(encoderInfo.offset)
                            encoded.limit(encoderInfo.offset + encoderInfo.size)
                            muxer.writeSampleData(trackIndex, encoded, encoderInfo)
                        }
                        val eos = encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(index, false)
                        if (eos) return
                    }
                }
            }
        }

        // يدفع PCM موحَّداً (16-bit ستيريو 44.1k) إلى المرمّز مع تصريف مستمر
        // كي لا تمتلئ صوامعه فيتجمّد الفكّ.
        fun feedPcm(data: ByteArray, length: Int) {
            var offset = 0
            while (offset < length) {
                val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = encoder.getInputBuffer(inIndex) ?: continue
                    buffer.clear()
                    val chunk = minOf(buffer.remaining(), length - offset)
                    buffer.put(data, offset, chunk)
                    encoder.queueInputBuffer(
                        inIndex,
                        0,
                        chunk,
                        totalFrames * 1_000_000L / TARGET_SAMPLE_RATE,
                        0,
                    )
                    totalFrames += chunk / BYTES_PER_FRAME
                    offset += chunk
                }
                drainEncoder(false)
            }
        }

        try {
            inputs.forEach { file ->
                // كل ملف يجب أن يساهم بصوت فعلي؛ كان الملف المبتور الذي يفكّ
                // صفر عيّنات يسقط بصمت ويُرفع الدرس المدموج ناقصاً جزءاً كاملاً.
                val framesBefore = totalFrames
                decodeInto(file, ::feedPcm)
                if (totalFrames == framesBefore) {
                    throw UnsupportedAudioException(
                        "تعذّر استخراج صوت من «${file.name}» — الملف تالف أو فارغ.",
                    )
                }
            }
            // راية نهاية التدفق ثم تصريف كامل.
            while (true) {
                val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    encoder.queueInputBuffer(
                        inIndex,
                        0,
                        0,
                        totalFrames * 1_000_000L / TARGET_SAMPLE_RATE,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    break
                }
                drainEncoder(false)
            }
            drainEncoder(untilEos = true)
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
        if (totalFrames == 0L) {
            output.delete()
            throw UnsupportedAudioException("تعذّر استخراج صوت من الملفات المختارة.")
        }
        return output
    }

    /** يفكّ ملفاً واحداً ويسلّم PCM الموحَّد إلى [sink] دفعةً دفعة. */
    private fun decodeInto(file: File, sink: (ByteArray, Int) -> Unit) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
        } catch (error: Exception) {
            extractor.release()
            throw UnsupportedAudioException("تعذّرت قراءة «${file.name}» — الملف تالف أو غير مدعوم.")
        }
        var trackIndex = -1
        var sourceFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if ((format.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                trackIndex = i
                sourceFormat = format
                break
            }
        }
        if (trackIndex < 0 || sourceFormat == null) {
            extractor.release()
            throw UnsupportedAudioException("«${file.name}» لا يحتوي مساراً صوتياً.")
        }
        extractor.selectTrack(trackIndex)
        val mime = sourceFormat.getString(MediaFormat.KEY_MIME).orEmpty()
        val decoder = try {
            MediaCodec.createDecoderByType(mime)
        } catch (error: Exception) {
            extractor.release()
            throw UnsupportedAudioException("صيغة «${file.name}» غير مدعومة على هذا الجهاز.")
        }
        try {
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var sampleRate = sourceFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
            var channels = sourceFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmFloat = false
            var resampler = Resampler(sampleRate, channels)
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmFloat = Build.VERSION.SDK_INT >= 24 &&
                            format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                        resampler = Resampler(sampleRate, channels)
                    }

                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(outIndex)
                            if (buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val samples = if (pcmFloat) {
                                    floatBufferToShorts(buffer)
                                } else {
                                    pcm16BufferToShorts(buffer)
                                }
                                val out = resampler.process(samples)
                                if (out.isNotEmpty()) sink(out, out.size)
                            }
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (eos) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            extractor.release()
        }
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private fun pcm16BufferToShorts(buffer: ByteBuffer): ShortArray {
        val shorts = ShortArray(buffer.remaining() / 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun floatBufferToShorts(buffer: ByteBuffer): ShortArray {
        val floats = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val shorts = ShortArray(floats.remaining())
        for (i in shorts.indices) {
            val value = (floats.get() * Short.MAX_VALUE).toInt()
            shorts[i] = value.coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt(),
            ).toShort()
        }
        return shorts
    }

    /**
     * إعادة عيّنة خطية ذات حالة: تحفظ آخر إطار وموضع الكسر بين الدُفعات،
     * فلا انقطاع في الطور عند حدود الصوامع. تحوّل أيضاً عدد القنوات إلى
     * ستيريو (الأحادي يُضاعَف، والزائد عن اثنتين يُسقَط).
     */
    private class Resampler(sourceRate: Int, private val sourceChannels: Int) {
        private val step = sourceRate.toDouble() / TARGET_SAMPLE_RATE
        private var position = 0.0
        private var previousLeft = 0
        private var previousRight = 0
        private var hasPrevious = false
        private val passthrough = sourceRate == TARGET_SAMPLE_RATE && sourceChannels == 2

        fun process(samples: ShortArray): ByteArray {
            if (samples.isEmpty() || sourceChannels <= 0) return ByteArray(0)
            if (passthrough) {
                val bytes = ByteArray(samples.size * 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().put(samples)
                return bytes
            }
            val frameCount = samples.size / sourceChannels
            if (frameCount == 0) return ByteArray(0)
            fun left(frame: Int): Int = samples[frame * sourceChannels].toInt()
            fun right(frame: Int): Int =
                if (sourceChannels >= 2) samples[frame * sourceChannels + 1].toInt() else left(frame)

            if (!hasPrevious) {
                previousLeft = left(0)
                previousRight = right(0)
                hasPrevious = true
            }
            // الإطارات المتاحة للاستيفاء: السابق المحفوظ + إطارات هذه الدفعة.
            val out = ByteBuffer.allocate(
                ((frameCount / step).toInt() + 8) * BYTES_PER_FRAME,
            ).order(ByteOrder.LITTLE_ENDIAN)
            while (position < frameCount) {
                val base = position.toInt()
                val fraction = position - base
                val leftA = if (base == 0) previousLeft else left(base - 1)
                val rightA = if (base == 0) previousRight else right(base - 1)
                // نستوفي بين الإطار السابق للموضع والإطار عنده — فيبقى صالحاً
                // حتى آخر إطار في الدفعة بلا انتظار الدفعة التالية.
                val leftB = left(base)
                val rightB = right(base)
                val l = (leftA + (leftB - leftA) * fraction).toInt()
                val r = (rightA + (rightB - rightA) * fraction).toInt()
                out.putShort(l.coerceIn(-32768, 32767).toShort())
                out.putShort(r.coerceIn(-32768, 32767).toShort())
                position += step
            }
            position -= frameCount
            previousLeft = left(frameCount - 1)
            previousRight = right(frameCount - 1)
            return out.array().copyOf(out.position())
        }
    }
}
