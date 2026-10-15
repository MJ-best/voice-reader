package app.voicereader

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.storage.StorageManager
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
import app.voicereader.korean.ReadingOptions
import app.voicereader.korean.applyReadingOptions
import app.voicereader.korean.isLatinOnly
import app.voicereader.korean.joinShortSentences
import app.voicereader.korean.splitDialogue
import java.io.DataInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
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

/** A book in the library. [path] is set for an EPUB found on the device and read in place (not copied). */
class Book(
    val id: String,
    val title: String,
    val author: String,
    var opened: Long,
    var locator: Locator?,
    val path: String? = null,
)

/** Names of a Supertonic release's built-in voices, by speaker id (voice_styles/F1..F5, M1..M5, sorted). */
private val BUILT_IN_VOICES = (1..5).map { "여성 $it" } + (1..5).map { "남성 $it" }

private const val PREVIEW_SENTENCE = "안녕하세요. 이 목소리로 책을 읽어 드릴게요."

/** Every number in a (nested) JSON array, flattened: style tensors are stored as [1][50][256]. */
private fun floats(array: JSONArray): FloatArray {
    val out = ArrayList<Float>()
    fun walk(a: JSONArray) {
        for (i in 0 until a.length()) {
            val v = a.get(i)
            if (v is JSONArray) walk(v) else out += (v as Number).toFloat()
        }
    }
    walk(array)
    return out.toFloatArray()
}

/** A user-made group of books (책장). A book can sit on several shelves. */
class Shelf(val id: String, var name: String, val bookIds: MutableList<String>)

private enum class Added { YES, DUPLICATE, DRM, UNREADABLE }

fun Context.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

/** A voice pack the app fetches by itself: sherpa-onnx's Supertonic files on Hugging Face, pinned to a revision. */
class PackSource(val name: String, val repo: String, val revision: String, val bytes: Long, val note: String)

// Supertonic 3 first: it reads better, and an e-ink reader (tested: 듀로보) keeps up with it. 2 is lighter.
val DOWNLOADABLE_PACKS = listOf(
    PackSource(
        "supertonic-3", "csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
        "cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4", 145_296_838, "품질 추천",
    ),
    PackSource(
        "supertonic-2", "csukuangfj2/sherpa-onnx-supertonic-tts-int8-2026-03-06",
        "5dec5897b86e2b293790eedb5e1f561a3295651f", 96_405_714, "가볍고 빠름, 느린 기기용",
    ),
)

private val PACK_FILES = listOf(
    "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx", "vocoder.int8.onnx",
    "tts.json", "unicode_indexer.bin", "voice.bin", "LICENSE",
)

/** Fetches [url] into [to] through a .part file, so [to] exists only once it is complete. */
private fun download(url: String, to: File, onBytes: (Int) -> Unit) {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 30_000
    try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
        val part = File(to.path + ".part")
        connection.inputStream.use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    onBytes(n)
                }
            }
        }
        val expected = connection.contentLengthLong
        if (expected >= 0 && part.length() != expected) throw IOException("cut off at ${part.length()} of $expected")
        if (!part.renameTo(to)) throw IOException("can't save ${to.name}")
    } finally {
        connection.disconnect()
    }
}

private fun File.readAtomically(): String? =
    try { String(AtomicFile(this).readFully()) } catch (e: FileNotFoundException) { null }

/** A crash mid-write leaves the previous version instead of a truncated file. */
private fun File.writeAtomically(text: String) = writeAtomically(text.toByteArray())

private fun File.writeAtomically(bytes: ByteArray) {
    val file = AtomicFile(this)
    val out = file.startWrite()
    try {
        out.write(bytes)
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
    // With the screen off the CPU may sleep between sentences (no audio plays while the next one is synthesized
    // or during the pause), so hold it awake while reading aloud. The timeout is renewed on every sentence.
    private val wakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicereader:tts")
            .apply { setReferenceCounted(false) }
    }

    fun epub(book: Book) = book.path?.let(::File) ?: File(dir, "${book.id}.epub")
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
        if (!copied) return "파일을 읽을 수 없어요"
        val error = when (addBook(id, file, path = null)) {
            Added.YES, Added.DUPLICATE -> null
            Added.DRM -> "DRM이 걸린 책은 열 수 없어요"
            Added.UNREADABLE -> "EPUB 파일을 열 수 없어요"
        }
        if (error != null) file.delete() else saveBooks()
        return error
    }

    /**
     * Opens [file], turns away DRM, and adds the book with its title, author and cover. A device file ([path] set)
     * already in the library under the same title and author (e.g. imported earlier) is a duplicate.
     */
    private suspend fun addBook(id: String, file: File, path: String?): Added {
        val pub = open(file) ?: return Added.UNREADABLE
        try {
            if (pub.isRestricted || withContext(Dispatchers.IO) { hasUnknownDrm(file) }) return Added.DRM
            val title = pub.metadata.title ?: path?.let { File(it).nameWithoutExtension } ?: "제목 없음"
            val author = pub.metadata.authors.joinToString { it.name }
            if (path != null && books.any { it.title == title && it.author == author }) return Added.DUPLICATE
            // Found on the device = not opened yet: it goes after the books being read.
            val book = Book(id, title, author, opened = if (path == null) System.currentTimeMillis() else 0, locator = null, path = path)
            pub.coverFitting(Size(300, 450))?.let { bitmap ->
                withContext(Dispatchers.IO) {
                    cover(book).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                }
            }
            books += book
            return Added.YES
        } finally {
            pub.close()
        }
    }

    // --- Shelves ---

    private val shelvesFile by lazy { File(dir, "shelves.json") }
    val shelves: MutableList<Shelf> by lazy { loadShelves() }

    private fun loadShelves(): MutableList<Shelf> {
        val array = JSONArray(shelvesFile.readAtomically() ?: return mutableListOf())
        return MutableList(array.length()) { i ->
            with(array.getJSONObject(i)) {
                val ids = getJSONArray("books")
                Shelf(getString("id"), getString("name"), MutableList(ids.length()) { ids.getString(it) })
            }
        }
    }

    fun saveShelves() = shelvesFile.writeAtomically(
        JSONArray(shelves.map { JSONObject().put("id", it.id).put("name", it.name).put("books", JSONArray(it.bookIds)) }).toString(),
    )

    fun addShelf(name: String): Shelf = Shelf(UUID.randomUUID().toString(), name, mutableListOf()).also {
        shelves += it
        saveShelves()
    }

    fun deleteShelf(shelf: Shelf) {
        shelves.remove(shelf)
        saveShelves()
    }

    /** The library shows shelves (true) or every book in one grid. */
    var showShelves: Boolean
        get() = prefs.getBoolean("showShelves", true)
        set(value) = prefs.edit().putBoolean("showShelves", value).apply()

    /** Volume keys turn pages while reading (while listening they still set the volume). */
    var volumeKeysTurnPages: Boolean
        get() = prefs.getBoolean("volumeKeysTurnPages", false)
        set(value) = prefs.edit().putBoolean("volumeKeysTurnPages", value).apply()

    // --- Device scan: every EPUB on the device, read in place ---

    /** Books added so far while a device scan runs; null when idle. */
    val scanProgress = MutableStateFlow<Int?>(null)

    /** Device files never to list again: removed by the user, or DRM / duplicate / unreadable last time. */
    private var skippedPaths: Set<String>
        get() = prefs.getStringSet("skippedPaths", emptySet())!!
        set(value) = prefs.edit().putStringSet("skippedPaths", value).apply()

    /** Android 11+ needs "all files access" to see other apps' documents; older versions the storage permission. */
    fun canReadAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun storageRoots(): List<File> =
        if (Build.VERSION.SDK_INT >= 30) {
            getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }
        } else {
            // Internal storage plus SD cards, found through this app's folder on each volume.
            listOf(Environment.getExternalStorageDirectory()) +
                getExternalFilesDirs(null).drop(1).mapNotNull { dir -> dir?.path?.substringBefore("/Android/")?.let(::File) }
        }

    /**
     * Adds every EPUB on the device that isn't listed yet. Runs in the app scope, so leaving the screen doesn't stop
     * it; [announce] shows the result even when nothing new turned up (a scan the user asked for).
     */
    fun startDeviceScan(announce: Boolean) {
        if (scanProgress.value != null || !canReadAllFiles()) return
        scanProgress.value = 0
        scope.launch {
            var added = 0
            var drm = 0
            try {
                val files = withContext(Dispatchers.IO) { findEpubs(storageRoots()) }
                val known = books.mapNotNullTo(HashSet()) { it.path } + skippedPaths
                val skipped = HashSet<String>()
                for (file in files.filter { it.path !in known }) {
                    when (addBook(UUID.randomUUID().toString(), file, file.path)) {
                        Added.YES -> {
                            added++
                            scanProgress.value = added
                            if (added % 10 == 0) saveBooks()
                        }
                        Added.DRM -> {
                            drm++
                            skipped += file.path
                        }
                        Added.DUPLICATE, Added.UNREADABLE -> skipped += file.path
                    }
                }
                saveBooks()
                skippedPaths = skippedPaths + skipped
            } finally {
                scanProgress.value = null
            }
            if (added > 0 || announce) {
                toast(
                    (if (added > 0) "기기에서 새 책 ${added}권을 찾았어요" else "새로 찾은 책이 없어요") +
                        if (drm > 0) " (DRM ${drm}권 제외)" else "",
                )
            }
        }
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

    /** How far into the book the saved position is, in percent; null if never opened. */
    fun progress(book: Book): Int? = book.locator?.locations?.totalProgression?.let { (it * 100).roundToInt() }

    /** Removes the book with its files, position and per-book settings. */
    fun deleteBook(book: Book) {
        if (this.book?.id == book.id) {
            stopTts()
            publication?.close()
            publication = null
            this.book = null
        }
        books.removeAll { it.id == book.id }
        saveBooks()
        shelves.forEach { it.bookIds.remove(book.id) }
        saveShelves()
        // A device file stays where it is (it's the user's); it is only kept out of future scans.
        if (book.path != null) skippedPaths = skippedPaths + book.path else epub(book).delete()
        listOf(cover(book), bookWordsFile(book)).forEach { it.delete() }
        prefs.edit().remove("dialogueVoice:${book.id}").apply()
    }

    private fun loadBooks(): MutableList<Book> {
        val array = JSONArray(index.readAtomically() ?: return mutableListOf())
        return MutableList(array.length()) { i ->
            with(array.getJSONObject(i)) {
                Book(
                    getString("id"), getString("title"), getString("author"), getLong("opened"),
                    optJSONObject("locator")?.let { Locator.fromJSON(it) },
                    optString("path").takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    // ponytail: rewrites the whole index on every save; move to Room when bookmarks/search (R-5) land.
    private fun saveBooks() {
        val array = JSONArray(books.map {
            JSONObject().put("id", it.id).put("title", it.title).put("author", it.author)
                .put("opened", it.opened).put("locator", it.locator?.toJSON()).put("path", it.path)
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
        rereadSentence()
    }

    /** Starts the current sentence over, so a pronunciation or reading change is heard right away. */
    private fun rereadSentence() {
        tts.value?.let { it.go(it.location.value.utteranceLocator) }
    }

    /** Reading options for every book (parentheses, footnote marks, acronyms). */
    var readingOptions: ReadingOptions
        get() = ReadingOptions(
            parentheses = prefs.getBoolean("readParentheses", true),
            footnoteMarks = prefs.getBoolean("readFootnoteMarks", false),
            spellAcronyms = prefs.getBoolean("spellAcronyms", true),
        )
        set(value) {
            prefs.edit()
                .putBoolean("readParentheses", value.parentheses)
                .putBoolean("readFootnoteMarks", value.footnoteMarks)
                .putBoolean("spellAcronyms", value.spellAcronyms)
                .apply()
            rereadSentence()
        }

    /** Silence after each sentence, for voice packs (the system voice paces itself). */
    var sentencePauseMs: Int
        get() = prefs.getInt("sentencePauseMs", 150)
        set(value) = prefs.edit().putInt("sentencePauseMs", value).apply()

    /** What the engine actually reads: user fixes first, then reading options and Korean normalization. */
    /** The language a sentence is read in: the book's, except an English-only sentence in a Korean (or unlabeled) book. */
    private fun spokenLanguage(text: String, language: Language?): Language? =
        if ((language == null || language.code.startsWith("ko")) && isLatinOnly(text)) Language("en") else language

    private fun speakable(text: String, language: Language?): String {
        val fixed = dictionary.apply(text)
        val korean = language == null || language.code.startsWith("ko")
        return if (korean) KoreanNormalizer.normalize(fixed, readingOptions) else applyReadingOptions(fixed, readingOptions)
    }

    // --- Voice packs (V-1, V-4): Supertonic files unzipped under voices/<name>; none = Android system TTS ---

    private val voicesDir by lazy { File(filesDir, "voices").apply { mkdirs() } }
    private val loaded = mutableMapOf<String, Synthesizer>()

    /** Set while Supertonic reads, so sentence splitting can queue each paragraph ahead of time (O-2). */
    private var speakers: Speakers? = null

    fun voicePacks(): List<String> = voicesDir.listFiles().orEmpty().filter { isSupertonicPack(it) }.map { it.name }.sorted()

    /** The narration voice, for every book. */
    var activeVoice: String?
        get() = prefs.getString("voice", null)?.takeIf { File(voicesDir, it).isDirectory }
        set(value) {
            stopTts() // the running engine may hold the model we are about to swap
            prefs.edit().putString("voice", value).apply()
        }

    /** Which voice of the narration pack reads (its speaker id). */
    val activeSpeaker: Int get() = prefs.getInt("speaker", 0)

    fun setNarrationVoice(pack: String, sid: Int) {
        stopTts()
        prefs.edit().putString("voice", pack).putInt("speaker", sid).apply()
    }

    /** The pack for lines of dialogue in the open book (PRD N-6); null = the narration voice. */
    var dialogueVoice: String?
        get() = book?.let { prefs.getString("dialogueVoice:${it.id}", null) }?.takeIf { File(voicesDir, it).isDirectory }
        set(value) {
            stopTts()
            book?.let { prefs.edit().putString("dialogueVoice:${it.id}", value).apply() }
        }

    val dialogueSpeaker: Int get() = book?.let { prefs.getInt("dialogueSpeaker:${it.id}", 0) } ?: 0

    fun setDialogueVoice(pack: String, sid: Int) {
        stopTts()
        book?.let { prefs.edit().putString("dialogueVoice:${it.id}", pack).putInt("dialogueSpeaker:${it.id}", sid).apply() }
    }

    /** Loads (once) the model of [pack]. Blocking. */
    @Synchronized
    private fun model(pack: String): Synthesizer = loaded.getOrPut(pack) { Synthesizer(File(voicesDir, pack)) }

    /** Loads the voices the open book needs and frees any other model. Null = Android system TTS. Blocking. */
    @Synchronized
    private fun loadSpeakers(): Speakers? {
        val narration = activeVoice ?: return null
        val dialogue = dialogueVoice ?: narration
        (loaded.keys - setOf(narration, dialogue)).forEach { loaded.remove(it)?.release() }
        fun voice(pack: String, sid: Int) = Voice(model(pack), sid.coerceIn(0, voicesOf(pack).size - 1))
        val narrator = voice(narration, activeSpeaker)
        return Speakers(narrator, if (dialogueVoice == null) narrator else voice(dialogue, dialogueSpeaker))
    }

    // --- Voices inside a pack: the release's built-in ones, then voice files made elsewhere (Voice Builder, Colab) ---

    private fun customVoices(pack: String): JSONArray =
        File(voicesDir, "$pack/voices.json").readAtomically()?.let(::JSONArray) ?: JSONArray()

    /** Names of the voices in [pack], by speaker id. */
    fun voicesOf(pack: String): List<String> {
        val count = try {
            DataInputStream(File(voicesDir, "$pack/voice.bin").inputStream()).use { input ->
                ByteArray(48).also { input.readFully(it) }.let { readVoiceBinHeader(it).count }
            }
        } catch (e: Exception) {
            1
        }
        val custom = customVoices(pack)
        val builtIn = count - custom.length()
        return List(builtIn) { if (builtIn == BUILT_IN_VOICES.size) BUILT_IN_VOICES[it] else "목소리 ${it + 1}" } +
            List(custom.length()) { custom.getJSONObject(it).getString("name") }
    }

    /** "여성 1", or "기기 음성" for the Android system voice. */
    val narrationVoiceName: String
        get() = activeVoice?.let { voicesOf(it).getOrNull(activeSpeaker) } ?: "기기 음성"

    val dialogueVoiceName: String?
        get() = dialogueVoice?.let { pack -> "${voicesOf(pack).getOrNull(dialogueSpeaker)} · ${voiceLabel(pack)}" }

    private var previewJob: Job? = null

    /** Says a short sentence in voice [sid] of [pack], so the listener can hear it before reading with it. */
    fun previewVoice(pack: String, sid: Int) {
        previewJob?.cancel()
        previewJob = scope.launch(Dispatchers.IO) {
            val audio = runCatching { model(pack).take(PREVIEW_SENTENCE, "ko", speed.toFloat(), sid).get() }.getOrNull()
            if (audio != null && isActive) playBlocking(audio) { isActive }
        }
    }

    /**
     * Adds a voice made elsewhere (a Supertonic style JSON from Voice Builder or a Colab notebook) to [pack], after the
     * user confirmed it is their own voice or used with consent. Returns an error, or null.
     */
    suspend fun importVoiceStyle(uri: Uri, pack: String): String? {
        val name = displayName(uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "내 목소리"
        stopTts() // the model is reloaded below with the new voice
        val error = withContext(Dispatchers.IO) {
            try {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    ?: return@withContext "파일을 읽을 수 없어요"
                val json = JSONObject(text)
                val ttl = floats(json.getJSONObject("style_ttl").getJSONArray("data"))
                val dp = floats(json.getJSONObject("style_dp").getJSONArray("data"))
                val bin = File(voicesDir, "$pack/voice.bin")
                bin.writeAtomically(appendVoice(bin.readBytes(), ttl, dp))
                val custom = customVoices(pack).put(
                    JSONObject().put("name", name).put("consentConfirmed", true).put("importedAt", System.currentTimeMillis()),
                )
                File(voicesDir, "$pack/voices.json").writeAtomically(custom.toString())
                null
            } catch (e: JSONException) {
                "Supertonic 목소리 파일(JSON)이 아니에요"
            } catch (e: IllegalArgumentException) {
                "이 음성 팩과 맞지 않는 목소리 파일이에요: ${e.message}"
            } catch (e: IOException) {
                "파일을 읽을 수 없어요: ${e.message}"
            }
        }
        if (error == null) withContext(Dispatchers.IO) { synchronized(this@App) { loaded.remove(pack)?.release() } }
        return error
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** Loads the models while the reader opens, so pressing play doesn't wait for them. */
    fun warmUpVoice() {
        if (activeVoice == null || tts.value != null) return
        scope.launch(Dispatchers.IO) { runCatching { loadSpeakers() } }
    }

    /**
     * Readium's sentence splitter, plus: lines of dialogue become pieces of their own when the book mixes voices, and
     * each freshly split paragraph is queued for synthesis before it is read.
     */
    private fun sentenceTokenizer(language: Language?): TextTokenizer {
        val sentences = DefaultTextContentTokenizer(TextUnit.Sentence, language)
        return object : TextTokenizer {
            override fun tokenize(data: String): List<IntRange> {
                val speakers = speakers
                val split = if (speakers?.mixed == true) splitDialogue(data, sentences::tokenize) else sentences.tokenize(data)
                if (speakers == null) return split // the device's TTS has no trouble with short sentences
                val ranges = joinShortSentences(split) { a, b ->
                    val (x, y) = data.substring(a) to data.substring(b)
                    speakers.of(x) != speakers.of(y) || isLatinOnly(x) != isLatinOnly(y) // one voice, one language per piece
                }
                prefetch(speakers, data, ranges, language ?: publication?.metadata?.language)
                return ranges
            }
        }
    }

    private fun prefetch(speakers: Speakers, paragraph: String, ranges: List<IntRange>, language: Language?) {
        val texts = ranges.map { paragraph.substring(it) }.filter { text -> text.any { it.isLetterOrDigit() } } // as Readium
        // One queue per model, in reading order: narration and dialogue may be two voices of the same pack.
        texts.groupBy { speakers.of(it).synthesizer }.forEach { (synthesizer, texts) ->
            synthesizer.prefetch(
                texts.map { text ->
                    val spoken = spokenLanguage(text, language)
                    Spoken(speakable(text, spoken), supertonicLanguage(spoken), speakers.of(text).sid)
                },
                speed.toFloat(),
            )
        }
        // A model with nothing in this paragraph still drops its stale look-ahead.
        setOf(speakers.narration.synthesizer, speakers.dialogue.synthesizer)
            .filter { s -> texts.none { speakers.of(it).synthesizer === s } }
            .forEach { it.prefetch(emptyList(), speed.toFloat()) }
    }

    /**
     * Unzips a voice pack the user confirmed they have consent for; the first one becomes the narration voice.
     * Files are flattened, which also keeps "../" entries from escaping the pack folder. Returns an error, or null.
     */
    suspend fun importVoicePack(uri: Uri): String? {
        val displayName = displayName(uri)
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
        if (error != null) dir.deleteRecursively() else if (activeVoice == null) setNarrationVoice(name, 0) // first pack narrates
        return error
    }

    /** The pack being downloaded and how far along (percent, in steps of 10 so e-ink redraws stay rare), or null. */
    val packDownload = MutableStateFlow<Pair<String, Int>?>(null)

    // ponytail: in-process download; it dies with the process. Tapping again picks up after the finished files.
    // DownloadManager if downloading in the background ever matters.
    fun downloadVoicePack(source: PackSource) {
        if (packDownload.value != null) return toast("이미 음성 팩을 받는 중이에요")
        packDownload.value = source.name to 0
        val lock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicereader:download")
            .apply { acquire(30 * 60_000L) }
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                val tmp = File(cacheDir, "pack-${source.name}").apply { mkdirs() } // finished files survive a failed try
                var done = 0L
                try {
                    for (file in PACK_FILES) {
                        val to = File(tmp, file)
                        if (to.exists()) done += to.length()
                        else download("https://huggingface.co/${source.repo}/resolve/${source.revision}/$file", to) { n ->
                            done += n
                            packDownload.value = source.name to (done * 10 / source.bytes).toInt().coerceAtMost(9) * 10
                        }
                    }
                    if (!isSupertonicPack(tmp)) return@withContext "받은 음성 팩이 온전하지 않아요"
                    File(tmp, "voice-pack.json").writeAtomically(
                        JSONObject().put("source", "https://huggingface.co/${source.repo}/tree/${source.revision}")
                            .put("importedAt", System.currentTimeMillis()).toString(),
                    )
                    val dir = File(voicesDir, source.name)
                    dir.deleteRecursively()
                    if (!tmp.renameTo(dir)) "음성 팩을 저장할 수 없어요" else null
                } catch (e: IOException) {
                    Log.w("VoicePack", "download ${source.name}", e)
                    "음성 팩을 받지 못했어요. 인터넷 연결을 확인하고 다시 눌러 주세요. 받은 부분부터 이어서 받아요."
                }
            }
            lock.release()
            packDownload.value = null
            if (error == null && activeVoice == null) setNarrationVoice(source.name, 0) // first pack narrates
            toast(error ?: "${voiceLabel(source.name)} 음성 팩을 설치했어요. 음성 버튼에서 목소리를 고를 수 있어요.")
        }
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

    private var starting = false

    /**
     * Loading the model takes seconds on a slow e-ink device, and tts is set only after that: without this guard a
     * second tap on play meanwhile started a second reader, both reading and fighting over the page.
     */
    suspend fun startTts(start: Locator?) {
        if (starting || tts.value != null) return
        starting = true
        try {
            launchTts(start)
        } finally {
            starting = false
        }
    }

    private suspend fun launchTts(start: Locator?) {
        val pub = publication ?: return
        previewJob?.cancel()
        val speakers = try {
            withContext(Dispatchers.IO) { loadSpeakers() } // usually already warm
        } catch (e: Exception) {
            return toast("음성 팩을 불러올 수 없습니다: ${e.message}")
        }
        val android = AndroidTtsEngineProvider(this)
        val base = if (speakers == null) android else SupertonicEngineProvider(android, speakers) { sentencePauseMs }
        this.speakers = speakers
        val factory = TtsNavigatorFactory(
            this, pub, TransformingEngineProvider(base, ::speakable, ::spokenLanguage), tokenizerFactory = ::sentenceTokenizer,
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
                if (playback.playWhenReady) wakeLock.acquire(10 * 60_000L) else wakeLock.release()
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
        speakers = null
        session?.release()
        session = null
        nav.close()
        stopService(Intent(this, PlaybackService::class.java))
        wakeLock.release()
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
