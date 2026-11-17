package app.voicereader

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsEngineProvider
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsPreferencesEditor
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try

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

/** The language code Supertonic is asked to speak; anything it doesn't know is read as Korean. */
fun supertonicLanguage(language: Language?): String =
    language?.code?.substringBefore('-')?.takeIf { it in LANGUAGES } ?: "ko"

/**
 * Owns a loaded Supertonic model. Synthesizes on one background thread and keeps recent results, so the sentences
 * coming up are ready before they are read (O-2). Results are keyed by the exact text spoken (after pronunciation
 * fixes and normalization), language and speed, so a dictionary edit or speed change never replays stale audio.
 */
class Synthesizer(private val dir: File) {

    private data class Key(val text: String, val language: String, val speed: Float, val sid: Int)

    private val tts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = onnx(dir, "duration_predictor"),
                    textEncoder = onnx(dir, "text_encoder"),
                    vectorEstimator = onnx(dir, "vector_estimator"),
                    vocoder = onnx(dir, "vocoder"),
                    ttsJson = File(dir, "tts.json").path,
                    unicodeIndexer = File(dir, "unicode_indexer.bin").path,
                    voiceStyle = File(dir, "voice.bin").path,
                ),
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            ),
        ),
    )
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // ponytail: in-memory only (~0.5MB per sentence). Persisting whole chapters to disk only pays off on phones too
    // slow to synthesize in real time (RTF ≥ 1); add it if the on-device measurement says so.
    private val results = object : LinkedHashMap<Key, CompletableFuture<GeneratedAudio>>(32, 0.75f, true) {
        // Only finished results are evicted: a pending one may be awaited by the player.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CompletableFuture<GeneratedAudio>>) =
            size > 32 && eldest.value.isDone
    }
    private val wanted = ArrayDeque<Key>() // what to synthesize next, in order
    private val claimed = mutableSetOf<Key>() // asked for by the player and not done yet: never dropped
    private var generating: Key? = null
    @Volatile private var running = true
    private val worker = thread(name = "supertonic", isDaemon = true) { work() }

    /**
     * Queues the sentences of the paragraph Readium just loaded. Look-ahead for an earlier paragraph is stale by now,
     * except sentences the player already asked for: Readium can load the next paragraph before reading the last
     * sentence of the current one.
     */
    fun prefetch(texts: List<Spoken>, speed: Float) = lock.withLock {
        wanted.retainAll { it in claimed }
        texts.map { Key(it.text, it.language, speed, it.sid) }
            .filter { results[it]?.isDone != true && it != generating && it !in wanted }
            .forEach { wanted.addLast(it) }
        changed.signal()
    }

    /** The audio for the sentence about to be read, in voice [sid]; jumps the queue if it isn't ready yet. */
    fun take(text: String, language: String, speed: Float, sid: Int): Future<GeneratedAudio> = lock.withLock {
        val key = Key(text, language, speed, sid)
        val audio = results.getOrPut(key) { CompletableFuture() }
        Log.i("Supertonic", if (audio.isDone) "ready" else "waiting") // "waiting" = an audible gap before this sentence
        if (!audio.isDone) {
            claimed += key
            if (key != generating) {
                wanted.remove(key)
                wanted.addFirst(key)
            }
            changed.signal()
        }
        audio
    }

    private fun work() {
        while (true) {
            val (key, audio) = lock.withLock {
                while (wanted.isEmpty() && running) changed.await()
                if (!running) return
                val key = wanted.removeFirst()
                generating = key
                key to results.getOrPut(key) { CompletableFuture() }
            }
            if (!audio.isDone) {
                try {
                    audio.complete(generate(key))
                } catch (e: Exception) {
                    audio.completeExceptionally(e)
                }
            }
            lock.withLock {
                generating = null
                claimed -= key
            }
        }
    }

    private fun generate(key: Key): GeneratedAudio {
        val started = SystemClock.elapsedRealtime()
        val pacedSpeed = VoiceReaderNative.recommendedSpeed(key.speed, key.text)
        val audio = tts.generateWithConfig(
            key.text,
            GenerationConfig(sid = key.sid, speed = pacedSpeed, numSteps = STEPS, extra = mapOf("lang" to key.language)),
        )
        val seconds = audio.samples.size.toDouble() / audio.sampleRate
        Log.i(
            "Supertonic",
            "${dir.name} sid=${key.sid} ${key.language} speed=$pacedSpeed rtf=%.3f (%.1fs audio)".format(
                (SystemClock.elapsedRealtime() - started) / 1000.0 / seconds,
                seconds,
            ),
        )
        val normalized = VoiceReaderNative.trimAndNormalizeSpeech(audio.samples, audio.sampleRate, key.text)
        return GeneratedAudio(normalized, audio.sampleRate)
    }

    /** Blocks until the sentence being synthesized (if any) is done, then frees the model. Call off the main thread. */
    fun release() {
        lock.withLock {
            running = false
            changed.signalAll()
        }
        worker.join()
        tts.release()
    }

    private companion object {
        fun onnx(dir: File, part: String) = dir.listFiles()!!.first { it.name.startsWith(part) && it.name.endsWith(".onnx") }.path
    }
}

/** A sentence to synthesize ahead: the text as spoken, its Supertonic language and voice. */
data class Spoken(val text: String, val language: String, val sid: Int)

/** One voice of a loaded pack: its synthesizer and speaker id (built-in F1..F5, M1..M5, then imported ones). */
data class Voice(val synthesizer: Synthesizer, val sid: Int)

/** Who reads what: narration, and lines of dialogue (PRD N-6). The same voice unless the book mixes voices. */
class Speakers(val narration: Voice, val dialogue: Voice) {
    val mixed: Boolean get() = narration != dialogue

    fun of(text: String): Voice = if (mixed && VoiceReaderNative.isDialogue(text)) dialogue else narration
}

/**
 * Persistent `AudioTrack.MODE_STREAM` player that appends sample-accurate trailing silence in Rust (`appendPauseSilence`)
 * instead of releasing a `MODE_STATIC` track on every sentence. Eliminates end-of-sentence syllable clipping and
 * maintains exact inter-sentence pacing across all synthesized and recorded voices.
 */
class StreamAudioPlayer {
    @Volatile private var track: AudioTrack? = null
    private var trackRate: Int = 0
    private var trackSpeed: Float = 1f

    private fun ensureTrack(sampleRate: Int, speed: Float): AudioTrack {
        val current = track
        if (current != null && trackRate == sampleRate) {
            if (trackSpeed != speed) {
                current.playbackParams = PlaybackParams().setSpeed(speed)
                trackSpeed = speed
            }
            return current
        }
        release()
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(sampleRate)
        val bufferBytes = minBytes.coerceAtLeast(sampleRate * 4 / 5) // >= 200ms float mono buffer
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
            .also { created ->
                if (speed != 1f) created.playbackParams = PlaybackParams().setSpeed(speed)
                track = created
                trackRate = sampleRate
                trackSpeed = speed
            }
    }

    fun playSentence(
        audio: GeneratedAudio,
        speed: Float,
        pauseMs: Int,
        text: String,
        keepGoing: () -> Boolean,
    ): Boolean {
        val sampleRate = audio.sampleRate
        if (sampleRate <= 0) return true
        val scaledPause = if (speed != 1f) (pauseMs * speed).roundToInt() else pauseMs
        val samples = VoiceReaderNative.appendPauseSilence(audio.samples, sampleRate, scaledPause, text)
        if (samples.isEmpty()) return true
        val t = ensureTrack(sampleRate, speed)
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        val chunkSize = (sampleRate / 50).coerceAtLeast(256) // ~20ms chunks for fast stop/skip response
        var offset = 0
        while (offset < samples.size) {
            if (!keepGoing()) {
                stopPlayback()
                return false
            }
            val n = minOf(chunkSize, samples.size - offset)
            val written = t.write(samples, offset, n, AudioTrack.WRITE_BLOCKING)
            if (written < 0) return false
            offset += written
        }
        return true
    }

    fun stopPlayback() {
        val t = track ?: return
        runCatching {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
            t.flush()
        }
    }

    fun release() {
        val t = track
        track = null
        trackRate = 0
        trackSpeed = 1f
        if (t != null) {
            runCatching {
                t.pause()
                t.flush()
                t.release()
            }
        }
    }
}

/** Plays a single standalone sample (used for voice preview) completely without clipping the tail. */
fun playBlocking(audio: GeneratedAudio, speed: Float = 1f, keepGoing: () -> Boolean): Boolean {
    if (audio.samples.isEmpty() || audio.sampleRate <= 0) return true
    val player = StreamAudioPlayer()
    return try {
        if (!player.playSentence(audio, speed, 220, "", keepGoing)) return false
        // Wait briefly so the 200ms ring buffer finishes draining the preview sentence cleanly.
        val waitUntil = SystemClock.elapsedRealtime() + 220
        while (SystemClock.elapsedRealtime() < waitUntil) {
            if (!keepGoing()) return false
            Thread.sleep(15)
        }
        true
    } finally {
        player.release()
    }
}

/**
 * Supertonic (sherpa-onnx) behind Readium's TTS navigator.
 * ponytail: reuses the AndroidTts* settings/preferences/error/voice types so the navigator type, the speed preference
 * and the media session stay shared with the Android TTS fallback. Give it its own types once voice/steps are settings.
 */
class SupertonicEngineProvider(
    android: AndroidTtsEngineProvider,
    private val speakers: Speakers,
    private val pauseMs: () -> Int,
) : TtsEngineProvider<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsPreferencesEditor, AndroidTtsEngine.Error, AndroidTtsEngine.Voice> by android {

    override suspend fun createEngine(
        publication: Publication,
        initialPreferences: AndroidTtsPreferences,
    ): Try<TtsEngine<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice>, Error> =
        Try.success(SupertonicEngine(speakers, pauseMs, publication.metadata.language, initialPreferences))
}

private class SupertonicEngine(
    private val speakers: Speakers,
    private val pauseMs: () -> Int,
    private val bookLanguage: Language?,
    initialPreferences: AndroidTtsPreferences,
) : TtsEngine<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice> {

    private val _settings = MutableStateFlow(resolve(initialPreferences))
    override val settings: StateFlow<AndroidTtsSettings> = _settings.asStateFlow()
    override val voices: Set<AndroidTtsEngine.Voice> = emptySet()

    private val player = Executors.newSingleThreadExecutor()
    private val streamPlayer = StreamAudioPlayer()
    private val main = Handler(Looper.getMainLooper())
    private var listener: TtsEngine.Listener<AndroidTtsEngine.Error>? = null

    /** The request allowed to play; stop() or a newer speak() replaces it and the player thread bails out. */
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
        val voice = speakers.of(text)
        val audio = voice.synthesizer.take(
            text,
            supertonicLanguage(language ?: settings.value.language),
            settings.value.speed.toFloat(),
            voice.sid,
        )
        player.execute {
            val finished = runCatching { play(requestId, text, audio) }
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

    /** Waits for the (usually prefetched) audio, then streams it with sample-accurate trailing silence. */
    private fun play(id: TtsEngine.RequestId, text: String, pending: Future<GeneratedAudio>): Boolean {
        var audio: GeneratedAudio? = null
        while (audio == null) {
            if (current != id) return false
            try {
                audio = pending.get(20, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // still synthesizing
            }
        }
        return streamPlayer.playSentence(audio, 1f, pauseMs(), text) { current == id }
    }

    override fun stop() {
        current = null
        streamPlayer.stopPlayback()
    }

    override fun close() {
        current = null
        streamPlayer.stopPlayback()
        player.execute { streamPlayer.release() }
        player.shutdown() // Synthesizer (model + cache) lives in App
    }
}
