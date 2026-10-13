package app.voicereader

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import app.voicereader.korean.KoreanNormalizer
import app.voicereader.korean.PronunciationDictionary
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsEngineProvider
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.coverFitting
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

/** Readium flags LCP and Adobe ADEPT; this catches other vendors' DRM: any encryption besides font obfuscation. */
fun hasUnknownDrm(epub: File): Boolean = ZipFile(epub).use { zip ->
    val xml = zip.getEntry("META-INF/encryption.xml")?.let { zip.getInputStream(it).reader().readText() } ?: return false
    Regex("""Algorithm\s*=\s*["']([^"']+)["']""").findAll(xml).any { it.groupValues[1] !in FONT_OBFUSCATION }
}

/**
 * Readium reports a single-item timeline, so lock-screen / headset next and previous do nothing.
 * Advertise them and route them to chapter skip.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class ChapterSkipPlayer(player: Player, private val skip: (Int) -> Unit) : ForwardingPlayer(player) {
    private val extra = intArrayOf(
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )
    override fun getAvailableCommands() = super.getAvailableCommands().buildUpon().addAll(*extra).build()
    override fun isCommandAvailable(command: Int) = command in extra || super.isCommandAvailable(command)
    override fun seekToNext() = skip(1)
    override fun seekToNextMediaItem() = skip(1)
    override fun seekToPrevious() = skip(-1)
    override fun seekToPreviousMediaItem() = skip(-1)
}

class Book(val id: String, val title: String, val author: String, var opened: Long, var locator: Locator?)

fun Context.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

private fun File.readAtomically(): String? =
    try { String(AtomicFile(this).readFully()) } catch (e: FileNotFoundException) { null }

/** A crash mid-write leaves the previous version instead of a truncated file. */
private fun File.writeAtomically(text: String) {
    val file = AtomicFile(this)
    val out = file.startWrite()
    try {
        out.write(text.toByteArray())
        file.finishWrite(out)
    } catch (e: Exception) {
        file.failWrite(out)
        throw e
    }
}

/** Process-wide state: the library, the open publication and the TTS session (outlives activities). */
class App : Application(), TtsNavigator.Listener {

    private val http = DefaultHttpClient()
    private val assets by lazy { AssetRetriever(contentResolver, http) }
    private val opener by lazy { PublicationOpener(DefaultPublicationParser(this, http, assets, pdfFactory = null)) }
    private val scope = MainScope()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val dir by lazy { File(filesDir, "books").apply { mkdirs() } }
    private val index by lazy { File(dir, "index.json") }
    val books: MutableList<Book> by lazy { loadBooks() }

    var book: Book? = null
        private set
    var publication: Publication? = null
        private set
    val tts = MutableStateFlow<AndroidTtsNavigator?>(null)
    var session: MediaSession? = null
        private set
    private var ttsJob: Job? = null

    fun epub(book: Book) = File(dir, "${book.id}.epub")
    fun cover(book: Book) = File(dir, "${book.id}.jpg")

    // --- Library ---

    private suspend fun open(file: File): Publication? {
        val asset = assets.retrieve(file).getOrElse {
            Log.w("App", "retrieve failed: $it"); return null
        }
        return opener.open(asset, allowUserInteraction = false).getOrElse {
            Log.w("App", "open failed: $it"); null
        }
    }

    /** Copies the EPUB into app storage and adds it to the library. Returns an error message, or null. */
    suspend fun import(uri: Uri): String? {
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.epub")
        val copied = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } != null
        }
        if (!copied) return "파일을 읽을 수 없습니다"

        val pub = open(file)
        val drm = pub != null && (pub.isRestricted || withContext(Dispatchers.IO) { hasUnknownDrm(file) })
        if (pub == null || drm) {
            pub?.close()
            file.delete()
            return if (pub == null) "EPUB 파일을 열 수 없습니다" else "DRM이 걸린 책은 열 수 없습니다"
        }
        val book = Book(
            id = id,
            title = pub.metadata.title ?: "제목 없음",
            author = pub.metadata.authors.joinToString { it.name },
            opened = System.currentTimeMillis(),
            locator = null,
        )
        pub.coverFitting(Size(300, 450))?.let { bitmap ->
            withContext(Dispatchers.IO) {
                cover(book).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
        }
        pub.close()
        books += book
        saveBooks()
        return null
    }

    suspend fun openBook(book: Book): Boolean {
        if (this.book?.id != book.id || publication == null) {
            stopTts()
            publication?.close()
            this.book = null
            publication = open(epub(book)) ?: return false
            this.book = book
            bookWords.clear()
            bookWords.putAll(readWords(bookWordsFile(book)))
            rebuildDictionary()
        }
        book.opened = System.currentTimeMillis()
        saveBooks()
        return true
    }

    fun saveLocator(locator: Locator) {
        book?.locator = locator
        saveBooks()
    }

    private fun loadBooks(): MutableList<Book> {
        val array = JSONArray(index.readAtomically() ?: return mutableListOf())
        return MutableList(array.length()) { i ->
            with(array.getJSONObject(i)) {
                Book(
                    getString("id"), getString("title"), getString("author"), getLong("opened"),
                    optJSONObject("locator")?.let { Locator.fromJSON(it) },
                )
            }
        }
    }

    // ponytail: rewrites the whole index on every save; move to Room when bookmarks/search (R-5) land.
    private fun saveBooks() {
        val array = JSONArray(books.map {
            JSONObject().put("id", it.id).put("title", it.title).put("author", it.author)
                .put("opened", it.opened).put("locator", it.locator?.toJSON())
        })
        index.writeAtomically(array.toString())
    }

    // --- Pronunciation dictionaries (Q-2): global + per book, plain {"원문": "읽는 법"} JSON shared across platforms ---

    private val globalWordsFile by lazy { File(filesDir, "pronunciation.json") }
    private fun bookWordsFile(book: Book) = File(dir, "${book.id}.pronunciation.json")
    val globalWords: MutableMap<String, String> by lazy { readWords(globalWordsFile) }
    val bookWords = mutableMapOf<String, String>()
    private var dictionary = PronunciationDictionary(emptyMap())

    private fun readWords(file: File): MutableMap<String, String> {
        val json = JSONObject(file.readAtomically() ?: return mutableMapOf())
        return json.keys().asSequence().associateWith { json.getString(it) }.toMutableMap()
    }

    private fun rebuildDictionary() {
        dictionary = PronunciationDictionary(globalWords + bookWords) // book entries win
    }

    /** Persists both dictionaries and re-reads the current sentence, so a fix is heard right away. */
    fun saveWords() {
        globalWordsFile.writeAtomically(JSONObject(globalWords as Map<*, *>).toString())
        book?.let { bookWordsFile(it).writeAtomically(JSONObject(bookWords as Map<*, *>).toString()) }
        rebuildDictionary()
        tts.value?.let { it.go(it.location.value.utteranceLocator) }
    }

    /** What the engine actually reads: user fixes first, then Korean normalization. */
    private fun speakable(text: String, language: Language?): String {
        val fixed = dictionary.apply(text)
        val korean = language == null || language.code.startsWith("ko")
        return if (korean) KoreanNormalizer.normalize(fixed) else fixed
    }

    // --- Voice packs (V-1, V-4): Supertonic files unzipped under voices/<name>; none = Android system TTS ---

    private val voicesDir by lazy { File(filesDir, "voices").apply { mkdirs() } }
    private var loadedVoice: Pair<String, Synthesizer>? = null

    /** Set while Supertonic reads, so sentence splitting can queue the paragraph ahead of time (O-2). */
    private var prefetcher: Synthesizer? = null

    fun voicePacks(): List<String> = voicesDir.listFiles().orEmpty().filter { isSupertonicPack(it) }.map { it.name }.sorted()

    var activeVoice: String?
        get() = prefs.getString("voice", null)?.takeIf { File(voicesDir, it).isDirectory }
        set(value) {
            stopTts() // the running engine may hold the model we are about to swap
            prefs.edit().putString("voice", value).apply()
        }

    /** Loads (once) the Supertonic model of [name]; blocking, call off the main thread. */
    @Synchronized
    private fun synthesizer(name: String): Synthesizer {
        loadedVoice?.let { (loaded, synthesizer) -> if (loaded == name) return synthesizer else synthesizer.release() }
        return Synthesizer(File(voicesDir, name)).also { loadedVoice = name to it }
    }

    /** Loads the model while the reader opens, so pressing play doesn't wait for it. */
    fun warmUpVoice() {
        val name = activeVoice ?: return
        scope.launch(Dispatchers.IO) { runCatching { synthesizer(name) } }
    }

    /** Readium's sentence splitter, plus: queue each freshly split paragraph for synthesis before it is read. */
    private fun sentenceTokenizer(language: Language?): TextTokenizer {
        val sentences = DefaultTextContentTokenizer(TextUnit.Sentence, language)
        return object : TextTokenizer {
            override fun tokenize(data: String): List<IntRange> = sentences.tokenize(data).also { ranges ->
                val synthesizer = prefetcher ?: return@also
                val lang = language ?: publication?.metadata?.language
                val texts = ranges.map { data.substring(it) }
                    .filter { text -> text.any { it.isLetterOrDigit() } } // Readium skips the rest too
                    .map { speakable(it, lang) }
                synthesizer.prefetch(texts, supertonicLanguage(lang), speed.toFloat())
            }
        }
    }

    /**
     * Unzips a voice pack the user confirmed they have consent for, and makes it the active voice.
     * Files are flattened, which also keeps "../" entries from escaping the pack folder. Returns an error, or null.
     */
    suspend fun importVoicePack(uri: Uri): String? {
        val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        val name = (displayName?.substringBeforeLast('.') ?: "voice").replace(Regex("""[^\w가-힣.-]"""), "_")
        val dir = File(voicesDir, name)
        val error = withContext(Dispatchers.IO) {
            dir.deleteRecursively()
            dir.mkdirs()
            try {
                val input = contentResolver.openInputStream(uri) ?: return@withContext "파일을 읽을 수 없습니다"
                ZipInputStream(input).use { zip ->
                    generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { entry ->
                        val file = File(entry.name).name
                        if (!file.startsWith(".")) File(dir, file).outputStream().use { zip.copyTo(it) }
                    }
                }
            } catch (e: IOException) {
                return@withContext "압축을 풀 수 없습니다: ${e.message}"
            }
            if (!isSupertonicPack(dir)) return@withContext "Supertonic 음성 팩(zip)이 아닙니다"
            File(dir, "voice-pack.json").writeAtomically(
                JSONObject().put("source", displayName).put("consentConfirmed", true)
                    .put("importedAt", System.currentTimeMillis()).toString(),
            )
            null
        }
        if (error != null) dir.deleteRecursively() else activeVoice = name
        return error
    }

    // --- Settings ---

    var readerPrefs: EpubPreferences
        get() = EpubPreferences(
            theme = Theme.valueOf(prefs.getString("theme", Theme.LIGHT.name)!!),
            fontFamily = FontFamily(prefs.getString("font", FontFamily.SERIF.name)!!),
            fontSize = prefs.getFloat("fontSize", 1f).toDouble(),
            lineHeight = prefs.getFloat("lineHeight", 1.6f).toDouble(),
            publisherStyles = false, // required for lineHeight to apply
        )
        set(p) = prefs.edit()
            .putString("theme", p.theme?.name)
            .putString("font", p.fontFamily?.name)
            .putFloat("fontSize", (p.fontSize ?: 1.0).toFloat())
            .putFloat("lineHeight", (p.lineHeight ?: 1.6).toFloat())
            .apply()

    var speed: Double
        get() = prefs.getFloat("speed", 1f).toDouble()
        set(value) {
            prefs.edit().putFloat("speed", value.toFloat()).apply()
            tts.value?.submitPreferences(AndroidTtsPreferences(speed = value))
        }

    // --- TTS ---

    suspend fun startTts(start: Locator?) {
        val pub = publication ?: return
        val voice = activeVoice
        val synthesizer = try {
            voice?.let { withContext(Dispatchers.IO) { synthesizer(it) } } // usually already warm
        } catch (e: Exception) {
            return toast("음성 팩을 불러올 수 없습니다: ${e.message}")
        }
        val android = AndroidTtsEngineProvider(this)
        val base = if (synthesizer == null) android else SupertonicEngineProvider(android, synthesizer)
        prefetcher = synthesizer
        val factory = TtsNavigatorFactory(
            this, pub, TransformingEngineProvider(base, ::speakable), tokenizerFactory = ::sentenceTokenizer,
        ) ?: return toast("이 책은 낭독할 수 없습니다")
        val nav = factory.createNavigator(this, start, AndroidTtsPreferences(speed = speed))
            .getOrElse { return toast("낭독 엔진을 시작할 수 없습니다: ${it.message}") }

        // Notification tap: back to the existing reader instead of stacking a new library screen on top.
        val reader = Intent(this, ReaderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        session = MediaSession.Builder(this, ChapterSkipPlayer(nav.asMedia3Player(), ::skipChapter))
            .setSessionActivity(PendingIntent.getActivity(this, 0, reader, PendingIntent.FLAG_IMMUTABLE))
            .build()
        tts.value = nav
        startService(Intent(this, PlaybackService::class.java))

        ttsJob = scope.launch {
            launch { nav.currentLocator.sample(3000).collect { saveLocator(it) } }
            nav.playback.collect { playback ->
                when (val state = playback.state) {
                    is TtsNavigator.State.Ended -> stopTts()
                    is TtsNavigator.State.Failure -> {
                        onTtsError(state.error)
                        stopTts()
                    }
                    else -> {}
                }
            }
        }
        nav.play()
    }

    /** Moves TTS to the previous/next reading-order item (chapter). */
    fun skipChapter(delta: Int) {
        val nav = tts.value ?: return
        val pub = publication ?: return
        val link = pub.readingOrder.getOrNull(nav.playback.value.index + delta) ?: return
        pub.locatorFromLink(link)?.let { nav.go(it) }
    }

    fun stopTts() {
        val nav = tts.value ?: return
        tts.value = null
        prefetcher = null
        session?.release()
        session = null
        nav.close()
        stopService(Intent(this, PlaybackService::class.java))
        ttsJob?.cancel() // last: may cancel the coroutine calling us
    }

    override fun onStopRequested() = stopTts()

    private fun onTtsError(error: TtsNavigator.Error) {
        val engineError = (error as? TtsNavigator.Error.EngineError<*>)?.cause
        if (engineError is AndroidTtsEngine.Error.LanguageMissingData) {
            toast("이 언어의 음성 데이터가 없습니다. 설치 후 다시 재생하세요.")
            AndroidTtsEngine.requestInstallVoice(this)
        } else {
            toast("낭독 오류: ${error.message}")
        }
    }
}
