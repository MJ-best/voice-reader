package app.voicereader

import android.os.Handler
import android.os.Looper
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try

/**
 * Wraps any engine so it speaks [transform]ed text (pronunciation fixes + normalization) instead of the raw book text,
 * in the language [languageOf] picks for each sentence (an English line in a Korean book is read as English).
 * When [interSentencePauseMs] is provided (for Android system TTS), enforces natural pacing between sentences.
 */
class TransformingEngineProvider<S : TtsEngine.Settings, P : TtsEngine.Preferences<P>, E : PreferencesEditor<P>, F : TtsEngine.Error, V : TtsEngine.Voice>(
    private val provider: TtsEngineProvider<S, P, E, F, V>,
    private val transform: (text: String, language: Language?) -> String,
    private val languageOf: (text: String, language: Language?) -> Language?,
    private val interSentencePauseMs: ((spokenText: String) -> Int)? = null,
) : TtsEngineProvider<S, P, E, F, V> by provider {

    override suspend fun createEngine(publication: Publication, initialPreferences: P): Try<TtsEngine<S, P, F, V>, Error> =
        provider.createEngine(publication, initialPreferences).map {
            TransformingEngine(it, transform, languageOf, interSentencePauseMs)
        }
}

private class TransformingEngine<S : TtsEngine.Settings, P : TtsEngine.Preferences<P>, E : TtsEngine.Error, V : TtsEngine.Voice>(
    private val engine: TtsEngine<S, P, E, V>,
    private val transform: (text: String, language: Language?) -> String,
    private val languageOf: (text: String, language: Language?) -> Language?,
    private val interSentencePauseMs: ((spokenText: String) -> Int)?,
) : TtsEngine<S, P, E, V> by engine {

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var outerListener: TtsEngine.Listener<E>? = null
    private var activeId: TtsEngine.RequestId? = null
    private var pendingDone: Runnable? = null
    private var lastSpokenText: String = ""

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        val spoken = languageOf(text, language ?: engine.settings.value.language)
        val transformed = transform(text, spoken)
        synchronized(lock) {
            pendingDone?.let(main::removeCallbacks)
            pendingDone = null
            activeId = requestId
            lastSpokenText = transformed
        }
        engine.speak(requestId, transformed, spoken)
    }

    // Word ranges would point into the transformed text, not the book's; drop them. Highlighting is per sentence.
    override fun setListener(listener: TtsEngine.Listener<E>?) {
        outerListener = listener
        engine.setListener(listener?.let { l ->
            object : TtsEngine.Listener<E> by l {
                override fun onRange(requestId: TtsEngine.RequestId, range: IntRange) {}

                override fun onDone(requestId: TtsEngine.RequestId) {
                    val pause = synchronized(lock) {
                        if (activeId != requestId) 0
                        else interSentencePauseMs?.invoke(lastSpokenText)?.coerceAtLeast(0) ?: 0
                    }
                    if (pause <= 0) {
                        synchronized(lock) { if (activeId == requestId) activeId = null }
                        l.onDone(requestId)
                        return
                    }
                    val task = Runnable {
                        val fire = synchronized(lock) {
                            if (activeId == requestId) {
                                pendingDone = null
                                activeId = null
                                true
                            } else {
                                false
                            }
                        }
                        if (fire) l.onDone(requestId)
                    }
                    synchronized(lock) {
                        if (activeId == requestId) {
                            pendingDone?.let(main::removeCallbacks)
                            pendingDone = task
                            main.postDelayed(task, pause.toLong())
                        }
                    }
                }

                override fun onInterrupted(requestId: TtsEngine.RequestId) {
                    synchronized(lock) {
                        if (activeId == requestId) {
                            pendingDone?.let(main::removeCallbacks)
                            pendingDone = null
                            activeId = null
                        }
                    }
                    l.onInterrupted(requestId)
                }

                override fun onError(requestId: TtsEngine.RequestId, error: E) {
                    synchronized(lock) {
                        if (activeId == requestId) {
                            pendingDone?.let(main::removeCallbacks)
                            pendingDone = null
                            activeId = null
                        }
                    }
                    l.onError(requestId, error)
                }
            }
        })
    }

    override fun stop() {
        val interruptedId = synchronized(lock) {
            pendingDone?.let { task ->
                main.removeCallbacks(task)
                pendingDone = null
                val id = activeId
                activeId = null
                id
            }
        }
        engine.stop()
        if (interruptedId != null) {
            main.post { outerListener?.onInterrupted(interruptedId) }
        }
    }

    override fun close() {
        synchronized(lock) {
            pendingDone?.let(main::removeCallbacks)
            pendingDone = null
            activeId = null
        }
        engine.close()
    }
}
