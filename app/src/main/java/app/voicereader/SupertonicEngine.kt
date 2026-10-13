package app.voicereader

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsEngineProvider
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsPreferencesEditor
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try

private const val SPEAKER = 0 // 0..4 = F1..F5, 5..9 = M1..M5. ponytail: fixed until there is a voice picker.
private const val STEPS = 8 // what the listening test used; drop to 4–5 if a phone can't keep up.
private val LANGUAGES = setOf("ko", "en", "es", "pt", "fr") // Supertonic 2

private val ONNX_PARTS = listOf("duration_predictor", "text_encoder", "vector_estimator", "vocoder")
private val OTHER_FILES = listOf("tts.json", "unicode_indexer.bin", "voice.bin")

/** A folder holding sherpa-onnx's Supertonic release files (supertonic-tts-int8, supertonic-3-tts-int8, fp32 too). */
fun isSupertonicPack(dir: File): Boolean {
    val names = dir.list().orEmpty()
    return ONNX_PARTS.all { part -> names.any { it.startsWith(part) && it.endsWith(".onnx") } } &&
        OTHER_FILES.all { it in names }
}

fun loadSupertonic(dir: File): OfflineTts {
    fun onnx(part: String) = dir.listFiles()!!.first { it.name.startsWith(part) && it.name.endsWith(".onnx") }.path
    return OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = onnx("duration_predictor"),
                    textEncoder = onnx("text_encoder"),
                    vectorEstimator = onnx("vector_estimator"),
                    vocoder = onnx("vocoder"),
                    ttsJson = File(dir, "tts.json").path,
                    unicodeIndexer = File(dir, "unicode_indexer.bin").path,
                    voiceStyle = File(dir, "voice.bin").path,
                ),
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            ),
        ),
    )
}

/**
 * Supertonic (sherpa-onnx) behind Readium's TTS navigator.
 * ponytail: reuses the AndroidTts* settings/preferences/error/voice types so the navigator type, the speed preference
 * and the media session stay shared with the Android TTS fallback. Give it its own types once voice/steps are settings.
 */
class SupertonicEngineProvider(
    android: AndroidTtsEngineProvider,
    private val model: () -> OfflineTts,
) : TtsEngineProvider<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsPreferencesEditor, AndroidTtsEngine.Error, AndroidTtsEngine.Voice> by android {

    override suspend fun createEngine(
        publication: Publication,
        initialPreferences: AndroidTtsPreferences,
    ): Try<TtsEngine<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice>, Error> =
        try {
            val tts = withContext(Dispatchers.IO) { model() } // first load takes a moment; App caches it
            Try.success(SupertonicEngine(tts, publication.metadata.language, initialPreferences))
        } catch (e: Exception) {
            Try.failure(DebugError("Supertonic voice pack failed to load: ${e.message}"))
        }
}

private class SupertonicEngine(
    private val tts: OfflineTts,
    private val bookLanguage: Language?,
    initialPreferences: AndroidTtsPreferences,
) : TtsEngine<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice> {

    private val _settings = MutableStateFlow(resolve(initialPreferences))
    override val settings: StateFlow<AndroidTtsSettings> = _settings.asStateFlow()
    override val voices: Set<AndroidTtsEngine.Voice> = emptySet()

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var listener: TtsEngine.Listener<AndroidTtsEngine.Error>? = null

    /** The request allowed to play; stop() or a newer speak() replaces it and the worker bails out. */
    @Volatile private var current: TtsEngine.RequestId? = null

    override fun submitPreferences(preferences: AndroidTtsPreferences) {
        _settings.value = resolve(preferences)
    }

    private fun resolve(p: AndroidTtsPreferences) = AndroidTtsSettings(
        language = p.language ?: bookLanguage ?: Language(Locale.getDefault()),
        overrideContentLanguage = p.language != null,
        pitch = p.pitch ?: 1.0,
        speed = p.speed ?: 1.0,
        voices = p.voices ?: emptyMap(),
    )

    override fun setListener(listener: TtsEngine.Listener<AndroidTtsEngine.Error>?) {
        this.listener = listener
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        current = requestId
        val lang = (language ?: settings.value.language).code.substringBefore('-').takeIf { it in LANGUAGES } ?: "ko"
        val config = GenerationConfig(
            sid = SPEAKER,
            speed = settings.value.speed.toFloat(),
            numSteps = STEPS,
            extra = mapOf("lang" to lang),
        )
        worker.execute {
            val finished = runCatching { current == requestId && play(requestId, text, config) }
                .onFailure { Log.e("Supertonic", "synthesis failed", it) }
            main.post {
                when {
                    finished.isFailure -> listener?.onError(requestId, AndroidTtsEngine.Error.Synthesis)
                    finished.getOrThrow() && current == requestId -> {
                        current = null
                        listener?.onDone(requestId)
                    }
                    else -> listener?.onInterrupted(requestId)
                }
            }
        }
    }

    /** Synthesizes then plays [text] on the worker thread. Returns false if stopped or replaced midway. */
    private fun play(id: TtsEngine.RequestId, text: String, config: GenerationConfig): Boolean {
        val started = SystemClock.elapsedRealtime()
        val audio = tts.generateWithConfig(text, config)
        val samples = audio.samples
        if (samples.isEmpty()) return true
        val seconds = samples.size.toDouble() / audio.sampleRate
        Log.i("Supertonic", "rtf=%.3f (%.1fs audio)".format((SystemClock.elapsedRealtime() - started) / 1000.0 / seconds, seconds))
        if (current != id) return false

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 4)
            .build()
        try {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            while (track.playbackHeadPosition < samples.size) {
                if (current != id) return false
                Thread.sleep(20)
            }
            return true
        } finally {
            track.release()
        }
    }

    override fun stop() {
        current = null
    }

    override fun close() {
        current = null
        worker.shutdown() // the running request notices within ~20ms; the shared model stays loaded in App
    }
}
