package app.voicereader

import android.util.Log
import app.voicereader.korean.KoreanNormalizer
import app.voicereader.korean.PronunciationDictionary
import app.voicereader.korean.ReadingOptions
import app.voicereader.korean.applyReadingOptions as ktApplyReadingOptions
import app.voicereader.korean.isDialogue as ktIsDialogue
import app.voicereader.korean.isLatinOnly as ktIsLatinOnly
import app.voicereader.korean.joinShortSentences
import app.voicereader.korean.splitDialogue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * JNI bridge to the Rust `voicereader-core` engine (`libvoicereader_core.so`).
 * Every method transparently falls back to Kotlin when running in a pure-JVM unit test without the `.so`.
 */
object VoiceReaderNative {

    val isLoaded: Boolean = try {
        System.loadLibrary("voicereader_core")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.w("VoiceReaderNative", "Native core library unavailable, using Kotlin fallback", e)
        false
    } catch (e: Throwable) {
        Log.w("VoiceReaderNative", "Failed to load native core library, using Kotlin fallback", e)
        false
    }

    @JvmStatic
    private external fun nativeNormalizeKorean(
        text: String,
        parentheses: Boolean,
        footnoteMarks: Boolean,
        spellAcronyms: Boolean,
        dictKeys: Array<String>,
        dictVals: Array<String>,
    ): String

    @JvmStatic
    private external fun nativeApplyReadingOptions(
        text: String,
        parentheses: Boolean,
        footnoteMarks: Boolean,
        spellAcronyms: Boolean,
    ): String

    @JvmStatic
    private external fun nativeIsLatinOnly(text: String): Boolean

    @JvmStatic
    private external fun nativeIsDialogue(text: String): Boolean

    @JvmStatic
    private external fun nativeChunkParagraph(
        text: String,
        sentenceBoundsUtf16: IntArray,
        splitQuotes: Boolean,
        minChars: Int,
        maxChars: Int,
    ): IntArray

    @JvmStatic
    private external fun nativeTrimAndNormalizeSpeech(
        samples: FloatArray,
        sampleRate: Int,
        text: String,
    ): FloatArray

    @JvmStatic
    private external fun nativeAppendPauseSilence(
        samples: FloatArray,
        sampleRate: Int,
        pauseMs: Int,
        text: String,
    ): FloatArray

    @JvmStatic
    private external fun nativePunctuationPauseBonusMs(text: String): Int

    @JvmStatic
    private external fun nativeRecommendedSpeed(baseSpeed: Float, text: String): Float

    @JvmStatic
    private external fun nativeReadVoiceBinCount(bytes: ByteArray): Int

    @JvmStatic
    private external fun nativeAppendVoiceBin(
        binBytes: ByteArray,
        ttlFloats: FloatArray,
        dpFloats: FloatArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeConvertEpubWordWise(
        inputPath: String,
        outputPath: String,
        level: Int,
    ): Int

    @JvmStatic
    private external fun nativeTransformHtmlWordWise(html: String, level: Int): String

    @JvmStatic
    private external fun nativeLookupWordHint(word: String, level: Int): String?

    fun normalizeKorean(
        text: String,
        options: ReadingOptions,
        dictKeys: Array<String>,
        dictVals: Array<String>,
        fallbackDict: PronunciationDictionary,
    ): String =
        if (isLoaded) {
            nativeNormalizeKorean(
                text,
                options.parentheses,
                options.footnoteMarks,
                options.spellAcronyms,
                dictKeys,
                dictVals,
            )
        } else {
            KoreanNormalizer.normalize(fallbackDict.apply(text), options)
        }

    fun applyReadingOptions(text: String, options: ReadingOptions): String =
        if (isLoaded) {
            nativeApplyReadingOptions(text, options.parentheses, options.footnoteMarks, options.spellAcronyms)
        } else {
            ktApplyReadingOptions(text, options)
        }

    fun isLatinOnly(text: String): Boolean =
        if (isLoaded) nativeIsLatinOnly(text) else ktIsLatinOnly(text)

    fun isDialogue(text: String): Boolean =
        if (isLoaded) nativeIsDialogue(text) else ktIsDialogue(text)

    /**
     * Splits dialogue (when [splitQuotes] is true) and joins only ultra-short interjections (`< minChars`),
     * keeping normal sentences separate so TTS models never rush across periods.
     */
    fun chunkParagraph(
        text: String,
        sentenceRanges: List<IntRange>,
        sentencesOf: (String) -> List<IntRange>,
        splitQuotes: Boolean,
        minChars: Int = 10,
        maxChars: Int = 75,
    ): List<IntRange> {
        if (isLoaded) {
            val flat = IntArray(sentenceRanges.size * 2)
            for (i in sentenceRanges.indices) {
                val r = sentenceRanges[i]
                flat[i * 2] = r.first
                flat[i * 2 + 1] = r.last + 1
            }
            val out = nativeChunkParagraph(text, flat, splitQuotes, minChars, maxChars)
            val ranges = ArrayList<IntRange>(out.size / 2)
            var i = 0
            while (i + 1 < out.size) {
                val s = out[i]
                val e = out[i + 1]
                if (s < e) ranges += s until e
                i += 2
            }
            return ranges
        }
        val split = if (splitQuotes) splitDialogue(text, sentencesOf) else sentenceRanges
        return joinShortSentences(split, min = minChars, max = maxChars) { a, b ->
            val x = text.substring(a)
            val y = text.substring(b)
            (splitQuotes && ktIsDialogue(x) != ktIsDialogue(y)) || ktIsLatinOnly(x) != ktIsLatinOnly(y)
        }
    }

    fun recommendedSpeed(baseSpeed: Float, text: String): Float =
        if (isLoaded) {
            nativeRecommendedSpeed(baseSpeed, text)
        } else {
            val base = baseSpeed.coerceIn(0.5f, 2.5f)
            val chars = text.trim().length
            val factor = when {
                chars <= 6 -> 0.90f
                chars < 18 -> 0.90f + 0.10f * ((chars - 6) / 12f)
                else -> 1.0f
            }
            (base * factor).coerceIn(0.5f, 2.5f)
        }

    fun punctuationPauseBonusMs(text: String): Int =
        if (isLoaded) {
            nativePunctuationPauseBonusMs(text)
        } else {
            val t = text.trimEnd { it.isWhitespace() || it in "\"'”’)]" }
            when {
                t.endsWith("...") || t.endsWith('…') -> 180
                t.endsWith('?') || t.endsWith('!') -> 70
                t.endsWith(',') || t.endsWith(';') || t.endsWith(':') || t.endsWith('—') -> -120
                else -> 0
            }
        }

    fun trimAndNormalizeSpeech(samples: FloatArray, sampleRate: Int, text: String): FloatArray {
        if (isLoaded) return nativeTrimAndNormalizeSpeech(samples, sampleRate, text)
        if (samples.isEmpty() || sampleRate <= 0) return samples
        val thresh = 0.0018f // ~ -55 dBFS
        val first = samples.indexOfFirst { abs(it) > thresh }
        if (first < 0) return samples
        val last = samples.indexOfLast { abs(it) > thresh }
        val leadMargin = sampleRate * 90 / 1000
        val trailMargin = sampleRate * 140 / 1000
        val out = samples.copyOfRange((first - leadMargin).coerceAtLeast(0), (last + trailMargin + 1).coerceAtMost(samples.size))
        val fadeIn = (sampleRate * 12 / 1000).coerceAtMost(out.size / 4)
        for (i in 0 until fadeIn) out[i] *= (0.5 * (1.0 - cos(PI * i / fadeIn))).toFloat()
        val fadeOut = (sampleRate * 22 / 1000).coerceAtMost(out.size / 4)
        for (i in 0 until fadeOut) out[out.size - 1 - i] *= (0.5 * (1.0 - cos(PI * i / fadeOut))).toFloat()
        return out
    }

    fun appendPauseSilence(samples: FloatArray, sampleRate: Int, pauseMs: Int, text: String): FloatArray {
        if (isLoaded) return nativeAppendPauseSilence(samples, sampleRate, pauseMs, text)
        if (sampleRate <= 0) return samples
        val totalMs = (pauseMs + punctuationPauseBonusMs(text)).coerceAtLeast(80)
        val extra = sampleRate * totalMs / 1000
        return samples.copyOf(samples.size + extra)
    }

    fun readVoiceBinCount(bytes: ByteArray): Int? =
        if (isLoaded) nativeReadVoiceBinCount(bytes).takeIf { it >= 0 } else null

    fun appendVoiceBin(binBytes: ByteArray, ttlFloats: FloatArray, dpFloats: FloatArray): ByteArray? =
        if (isLoaded) {
            nativeAppendVoiceBin(binBytes, ttlFloats, dpFloats)
                ?: throw IllegalArgumentException("voice.bin 형식이 맞지 않아요")
        } else {
            null
        }

    fun convertEpubWordWise(inputPath: String, outputPath: String, level: Int): Int {
        check(isLoaded) { "Word Wise 변환을 위해 네이티브 라이브러리가 필요해요" }
        val n = nativeConvertEpubWordWise(inputPath, outputPath, level)
        check(n >= 0) { "EPUB Word Wise 변환에 실패했어요" }
        return n
    }

    fun transformHtmlWordWise(html: String, level: Int): String =
        if (isLoaded) nativeTransformHtmlWordWise(html, level) else html

    fun lookupWordHint(word: String, level: Int): String? =
        if (isLoaded) nativeLookupWordHint(word, level) else null
}
