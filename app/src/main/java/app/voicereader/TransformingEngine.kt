package app.voicereader

import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try

/** Wraps any engine so it speaks [transform]ed text (pronunciation fixes + normalization) instead of the raw book text. */
class TransformingEngineProvider<S : TtsEngine.Settings, P : TtsEngine.Preferences<P>, E : PreferencesEditor<P>, F : TtsEngine.Error, V : TtsEngine.Voice>(
    private val provider: TtsEngineProvider<S, P, E, F, V>,
    private val transform: (String) -> String,
) : TtsEngineProvider<S, P, E, F, V> by provider {

    override suspend fun createEngine(publication: Publication, initialPreferences: P): Try<TtsEngine<S, P, F, V>, Error> =
        provider.createEngine(publication, initialPreferences).map { TransformingEngine(it, transform) }
}

private class TransformingEngine<S : TtsEngine.Settings, P : TtsEngine.Preferences<P>, E : TtsEngine.Error, V : TtsEngine.Voice>(
    private val engine: TtsEngine<S, P, E, V>,
    private val transform: (String) -> String,
) : TtsEngine<S, P, E, V> by engine {

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) =
        engine.speak(requestId, transform(text), language)

    // Word ranges would point into the transformed text, not the book's; drop them. Highlighting is per sentence.
    override fun setListener(listener: TtsEngine.Listener<E>?) =
        engine.setListener(listener?.let { l -> object : TtsEngine.Listener<E> by l {
            override fun onRange(requestId: TtsEngine.RequestId, range: IntRange) {}
        } })
}
