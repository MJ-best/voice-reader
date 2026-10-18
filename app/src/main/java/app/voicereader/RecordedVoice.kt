package app.voicereader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsPreferencesEditor
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try

/*
 * A book read in a voice recorded elsewhere: the app exports the book's sentences exactly as it will speak them, a
 * computer turns each into audio (VoxCPM2, see docs/my-voice-book.md) and the zip comes back as <key>.m4a files.
 */

/** A sentence's file name in a recording: SHA-1 of the text as spoken (after fixes and normalization). */
fun recordingKey(spoken: String): String =
    MessageDigest.getInstance("SHA-1").digest(spoken.toByteArray()).joinToString("") { "%02x".format(it) }

val RECORDING_FILE = Regex("[0-9a-f]{40}\\.m4a")

/** Decodes a short audio file (AAC in .m4a) to mono float samples. */
fun decodeAudio(file: File): GeneratedAudio {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.path)
        val format = extractor.getTrackFormat(0)
        extractor.selectTrack(0)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        val chunks = ArrayList<ShortArray>()
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val pcm = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    chunks += ShortArray(pcm.remaining()).also { pcm.get(it) }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.release()
        }
        val all = ShortArray(chunks.sumOf { it.size })
        var at = 0
        chunks.forEach { it.copyInto(all, at); at += it.size }
        val samples = FloatArray(all.size / channels) { i -> (0 until channels).sumOf { all[i * channels + it].toInt() } / (32768f * channels) }
        return GeneratedAudio(samples, rate)
    } finally {
        extractor.release()
    }
}

private typealias AndroidTtsProvider =
    TtsEngineProvider<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsPreferencesEditor, AndroidTtsEngine.Error, AndroidTtsEngine.Voice>
private typealias AndroidTts = TtsEngine<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice>

/** Plays the recording of each sentence from [dir]; a sentence without one (or that fails to decode) goes to [provider]'s engine. */
class RecordedEngineProvider(
    private val provider: AndroidTtsProvider,
    private val dir: File,
    private val pauseMs: () -> Int,
) : AndroidTtsProvider by provider {

    override suspend fun createEngine(publication: Publication, initialPreferences: AndroidTtsPreferences): Try<AndroidTts, Error> =
        provider.createEngine(publication, initialPreferences).map { RecordedEngine(it, dir, pauseMs) }
}

private class RecordedEngine(
    private val engine: AndroidTts,
    private val dir: File,
    private val pauseMs: () -> Int,
) : AndroidTts by engine {

    private val player = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var listener: TtsEngine.Listener<AndroidTtsEngine.Error>? = null

    /** The recording allowed to play; stop() or a newer speak() replaces it and the player thread bails out. */
    @Volatile private var current: TtsEngine.RequestId? = null

    override fun setListener(listener: TtsEngine.Listener<AndroidTtsEngine.Error>?) {
        this.listener = listener
        engine.setListener(listener)
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        val file = File(dir, "${recordingKey(text)}.m4a")
        if (!file.exists()) {
            current = null
            return engine.speak(requestId, text, language)
        }
        engine.stop()
        current = requestId
        val speed = settings.value.speed.toFloat()
        player.execute {
            val finished = runCatching {
                val audio = decodeAudio(file)
                val started = SystemClock.elapsedRealtime()
                playSentence(audio, speed, pauseMs()) { current == requestId }.also {
                    Log.i("Recorded", "%.1fs audio ×%.2f played in %.1fs".format(audio.samples.size.toFloat() / audio.sampleRate, speed, (SystemClock.elapsedRealtime() - started) / 1000f))
                }
            }
            main.post {
                when {
                    finished.isFailure -> {
                        Log.e("Recorded", "can't play ${file.name}", finished.exceptionOrNull())
                        if (current == requestId) {
                            current = null
                            engine.speak(requestId, text, language) // synthesize it instead
                        }
                    }
                    finished.getOrThrow() && current == requestId -> {
                        current = null
                        listener?.onDone(requestId)
                    }
                    else -> listener?.onInterrupted(requestId)
                }
            }
        }
    }

    override fun stop() {
        current = null
        engine.stop()
    }

    override fun close() {
        current = null
        player.shutdown()
        engine.close()
    }
}
