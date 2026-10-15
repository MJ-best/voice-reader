package app.voicereader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import android.view.ActionMode
import android.view.KeyEvent as AndroidKeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.Url

private val SPEEDS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
private val PAUSES = listOf(60, 150, 320) // ms of silence between sentences: short, normal, long
private val THEMES = listOf(Theme.LIGHT, Theme.SEPIA, Theme.DARK)
/** Page keys, and scroll wheels that send arrow keys: true turns forward. */
private val PAGE_KEYS = mapOf(
    AndroidKeyEvent.KEYCODE_PAGE_DOWN to true, AndroidKeyEvent.KEYCODE_DPAD_DOWN to true,
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT to true, AndroidKeyEvent.KEYCODE_NAVIGATE_NEXT to true,
    AndroidKeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN to true,
    AndroidKeyEvent.KEYCODE_PAGE_UP to false, AndroidKeyEvent.KEYCODE_DPAD_UP to false,
    AndroidKeyEvent.KEYCODE_DPAD_LEFT to false, AndroidKeyEvent.KEYCODE_NAVIGATE_PREVIOUS to false,
    AndroidKeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP to false,
)

private val MEDIA_KEYS = setOf(
    AndroidKeyEvent.KEYCODE_HEADSETHOOK, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE,
    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_NEXT, AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS,
    AndroidKeyEvent.KEYCODE_MEDIA_STOP,
)

class ReaderActivity : AppCompatActivity() {

    companion object {
        /** Start reading aloud from the saved position as soon as the book opens ("continue listening"). */
        const val AUTOPLAY = "autoplay"
    }

    private val app get() = application as App
    private lateinit var navigator: EpubNavigatorFragment
    private lateinit var topBar: View
    private lateinit var player: View
    private lateinit var play: MaterialButton
    private var chromeVisible = false
    private var selection: ActionMode? = null

    /** Back clears a text selection, then puts the bars away, and only then leaves the book. */
    private val backSteps = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val mode = selection ?: return setChrome(false)
            navigator.clearSelection() // else the page keeps it and spends the next tap on clearing it
            mode.finish()
        }
    }

    private fun updateBack() {
        backSteps.isEnabled = selection != null || chromeVisible
    }

    /** Adds "발음 고치기" to the text selection menu, next to Copy: long-press a word, fix how it is read. */
    private val selectionActions = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, R.id.fixPronunciation, 0, "발음 고치기")
            selection = mode
            updateBack()
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != R.id.fixPronunciation) return false
            lifecycleScope.launch {
                val word = selectedWord() // read before the selection goes away
                navigator.clearSelection() // else the page keeps it and spends the next tap on clearing it
                mode.finish()
                editPronunciation(word, "", bookOnly = true, existing = false)
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selection = null
            updateBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val pub = app.publication
        if (pub == null) {
            // Process was killed while reading: nothing to restore, go back to the library.
            supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
            super.onCreate(savedInstanceState)
            finish()
            return
        }
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
            initialLocator = app.book?.locator,
            initialPreferences = app.readerPrefs,
            configuration = EpubNavigatorFragment.Configuration(selectionActionModeCallback = selectionActions),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.reader)
        navigator = supportFragmentManager.findFragmentById(R.id.navigator) as EpubNavigatorFragment
        topBar = findViewById(R.id.topBar)
        player = findViewById(R.id.player)
        play = findViewById(R.id.play)
        findViewById<TextView>(R.id.bookTitle).text = app.book?.title
        findViewById<PageKeyLayout>(R.id.root).apply {
            onPageKey = { event -> pageKey(event) }
            requestFocus() // keeps the root in the focus chain, so its pre-IME hook sees every key
        }
        applyPageTheme(app.readerPrefs.theme ?: Theme.LIGHT)
        app.warmUpVoice()
        onBackPressedDispatcher.addCallback(this, backSteps)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.toc).setOnClickListener { showToc() }
        findViewById<View>(R.id.settings).setOnClickListener { showSettings() }

        val footChapter = findViewById<TextView>(R.id.footChapter)
        val footPosition = findViewById<TextView>(R.id.footPosition)
        play.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.prev).setOnClickListener { app.tts.value?.skipToPreviousUtterance() }
        findViewById<View>(R.id.next).setOnClickListener { app.tts.value?.skipToNextUtterance() }

        // Taps near the edges turn pages without animation and put the bars away (that's reading); a tap in the middle
        // shows or hides them. Page keys and the scroll wheel: dispatchKeyEvent and dispatchGenericMotionEvent below.
        val pages = DirectionalNavigationAdapter(navigator, animatedTransition = false)
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                setChrome(if (pages.onTap(event)) false else !chromeVisible)
                return true
            }

            // Keys that reach Readium's page anyway (PageKeyLayout takes the usual ones first).
            override fun onKey(event: KeyEvent): Boolean = pages.onKey(event).also { if (it) setChrome(false) }
        })

        if (savedInstanceState == null && intent.getBooleanExtra(AUTOPLAY, false) && app.tts.value == null) {
            lifecycleScope.launch { app.startTts(app.book?.locator) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val playing = app.tts.flatMapLatest { it?.playback ?: flowOf(null) }
                    .map { it?.playWhenReady == true }
                    .distinctUntilChanged()
                launch {
                    playing.collect {
                        play.setIconResource(if (it) R.drawable.ic_pause else R.drawable.ic_play)
                        play.text = if (it) "일시정지" else "듣기"
                        if (it) setChrome(false) // listening: the page and its highlighted line are all that show
                    }
                }
                launch {
                    val pages = pub.positions().size
                    navigator.currentLocator.collect { locator ->
                        footChapter.text = chapterTitle(locator.href).orEmpty()
                        footPosition.text = locator.locations.position?.takeIf { pages > 0 }?.let { "$it/$pages" }
                            ?: locator.locations.totalProgression?.let { "${(it * 100).roundToInt()}%" }.orEmpty()
                    }
                }
                val utterances = app.tts.flatMapLatest { nav -> nav?.location?.map { it.utteranceLocator } ?: flowOf(null) }
                    .distinctUntilChanged()
                // A jump into another chapter first lands wherever that chapter was pre-laid-out (e.g. its
                // last page), so re-sync once the navigator reports the new chapter.
                val chapters = navigator.currentLocator.map { it.href }.distinctUntilChanged()
                // Only while playing: a paused session neither marks a sentence nor pulls the page back to it.
                combine(utterances, chapters, playing) { utterance, _, on -> utterance.takeIf { on } }
                    .collect { utterance ->
                        val highlight = utterance?.let {
                            Decoration("tts", it, Decoration.Style.Highlight(tint = getColor(R.color.highlight)))
                        }
                        navigator.applyDecorations(listOfNotNull(highlight), "tts")
                        // ponytail: turns pages per sentence; use tokenLocator + throttle if long sentences straddle pages.
                        utterance?.let { navigator.go(it, animated = false) }
                    }
            }
        }
    }

    /**
     * Media keys (earphone buttons) go to the TTS session: the page's WebView would swallow them. Page keys turn the
     * page wherever the focus is: arrow keys would otherwise walk between the bars' buttons.
     */
    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        if (event.keyCode in MEDIA_KEYS && app.session != null) {
            getSystemService(AudioManager::class.java).dispatchMediaKeyEvent(event)
            return true
        }
        return pageKey(event) || super.dispatchKeyEvent(event)
    }

    /** Turns the page for a page key (on key down) and says whether it was one. */
    private fun pageKey(event: AndroidKeyEvent): Boolean {
        val forward = PAGE_KEYS[event.keyCode] ?: volumeKey(event.keyCode) ?: return false
        if (event.action == AndroidKeyEvent.ACTION_DOWN) turnPage(forward)
        return true
    }

    /** A scroll wheel, rotary dial or mouse wheel turns pages: down or clockwise is forward. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL) return super.dispatchGenericMotionEvent(event)
        val amount = -event.getAxisValue(MotionEvent.AXIS_VSCROLL) +
            event.getAxisValue(MotionEvent.AXIS_HSCROLL) + event.getAxisValue(MotionEvent.AXIS_SCROLL)
        if (amount == 0f) return super.dispatchGenericMotionEvent(event)
        turnPage(amount > 0)
        return true
    }

    /** Volume down / up as next / previous page, when turned on in 보기 and nothing is being read aloud. */
    private fun volumeKey(code: Int): Boolean? {
        if (!app.volumeKeysTurnPages || app.tts.value?.playback?.value?.playWhenReady == true) return null
        return when (code) {
            AndroidKeyEvent.KEYCODE_VOLUME_DOWN -> true
            AndroidKeyEvent.KEYCODE_VOLUME_UP -> false
            else -> null
        }
    }

    private var lastTurn = 0L

    /** One page per notch: a wheel sends a burst of events, and e-ink takes a moment to redraw anyway. */
    private fun turnPage(forward: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTurn < 250) return
        lastTurn = now
        setChrome(false)
        if (forward) navigator.goForward(animated = false) else navigator.goBackward(animated = false)
    }

    override fun onStop() {
        super.onStop()
        // While TTS runs, App saves its position in the background instead.
        if (::navigator.isInitialized && app.tts.value == null) app.saveLocator(navigator.currentLocator.value)
    }

    /** Shows or hides the bars at once: a fade would be a dozen e-ink refreshes. */
    private fun setChrome(visible: Boolean) {
        if (chromeVisible == visible) return
        chromeVisible = visible
        topBar.isVisible = visible
        player.isVisible = visible
        updateBack()
    }

    /** The bars take the page's colors, so they read as part of the page; rules separate them from the text. */
    private fun applyPageTheme(theme: Theme) {
        val page = theme.backgroundColor
        val ink = theme.contentColor
        val pressed = arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf())
        findViewById<View>(R.id.root).setBackgroundColor(page)
        topBar.setBackgroundColor(page)
        findViewById<View>(R.id.player).setBackgroundColor(page)
        findViewById<View>(R.id.topBarRule).setBackgroundColor(ink)
        findViewById<View>(R.id.playerRule).setBackgroundColor(ink)
        findViewById<TextView>(R.id.bookTitle).setTextColor(ink)
        findViewById<View>(R.id.footer).setBackgroundColor(page)
        val quiet = ColorUtils.setAlphaComponent(ink, 0xB0) // the page line stays in the background
        findViewById<TextView>(R.id.footChapter).setTextColor(quiet)
        findViewById<TextView>(R.id.footPosition).setTextColor(quiet)
        findViewById<MaterialButton>(R.id.back).apply {
            iconTint = ColorStateList(pressed, intArrayOf(page, ink))
            backgroundTintList = ColorStateList(pressed, intArrayOf(ink, Color.TRANSPARENT))
        }
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            ColorUtils.calculateLuminance(page) > 0.5
    }

    private fun togglePlay() {
        val nav = app.tts.value
        when {
            nav == null -> lifecycleScope.launch {
                play.text = "준비 중…" // loading the voice can take seconds on an e-ink device
                try {
                    app.startTts(navigator.firstVisibleElementLocator())
                } finally {
                    if (app.tts.value?.playback?.value?.playWhenReady != true) play.text = "듣기"
                }
            }
            nav.playback.value.playWhenReady -> nav.pause()
            else -> nav.play()
        }
    }

    private fun go(link: Link) {
        val nav = app.tts.value
        if (nav != null) app.publication?.locatorFromLink(link)?.let { nav.go(it) }
        else navigator.go(link)
    }

    /** Table of contents, flattened with depth. */
    private fun tocEntries(): List<Pair<Int, Link>> {
        val pub = app.publication ?: return emptyList()
        val entries = mutableListOf<Pair<Int, Link>>()
        fun walk(links: List<Link>, depth: Int) {
            for (link in links) {
                entries += depth to link
                walk(link.children, depth + 1)
            }
        }
        walk(pub.tableOfContents.ifEmpty { pub.readingOrder }, 0)
        return entries
    }

    /**
     * The table-of-contents entry for [href]: the last one at or before it in reading order, since a chapter can span
     * several files and the contents point at the first only.
     */
    private fun currentTocLink(href: Url): Link? {
        val order = app.publication?.readingOrder?.map { it.url().removeFragment() } ?: return null
        fun indexOf(url: Url) = order.indexOfFirst { it.isEquivalent(url.removeFragment()) }
        val here = indexOf(href).takeIf { it >= 0 } ?: return null
        val placed = tocEntries().map { (_, link) -> indexOf(link.url()) to link }.filter { (i, _) -> i in 0..here }
        val nearest = placed.maxOfOrNull { (i, _) -> i } ?: return null
        return placed.last { (i, _) -> i == nearest }.second
    }

    private fun chapterTitle(href: Url): String? = currentTocLink(href)?.title

    private fun showToc() {
        val here = currentTocLink(navigator.currentLocator.value.href)
        Sheet(this, "목차").apply {
            tocEntries().forEach { (depth, link) ->
                row(link.title ?: link.href.toString(), indent = depth, current = link === here) { go(link) }
            }
        }.show()
    }

    private fun percent(fontSize: Double) = "${(fontSize * 100).roundToInt()}%"

    private fun speedLabel(speed: Double) = "${speed.toString().removeSuffix(".0")}×"

    /** One entry point, four short sheets that each fit on screen without scrolling. */
    private fun showSettings() {
        val prefs = app.readerPrefs
        val options = app.readingOptions
        fun on(value: Boolean) = if (value) "켬" else "끔"
        Sheet(this, "설정").apply {
            row(
                "보기",
                "${if (prefs.fontFamily == FontFamily.SANS_SERIF) "고딕" else "명조"} · ${percent(prefs.fontSize ?: 1.0)} · 줄 간격 ${"%.1f".format(prefs.lineHeight ?: 1.6)}" +
                    if (app.volumeKeysTurnPages) " · 볼륨 키 넘김" else "",
                icon = R.drawable.ic_text,
                more = true,
            ) { showDisplay() }
            row(
                "낭독",
                "속도 ${speedLabel(app.speed)} · ${app.narrationVoiceName}" + (app.activeVoice?.let { " (${voiceLabel(it)})" } ?: ""),
                icon = R.drawable.ic_headphones,
                more = true,
            ) { showNarration() }
            row(
                "읽기 옵션",
                "괄호 ${on(options.parentheses)} · 각주 기호 ${on(options.footnoteMarks)} · 약어 풀기 ${on(options.spellAcronyms)}",
                icon = R.drawable.ic_toc,
                more = true,
            ) { showReadingOptions() }
            row("발음 사전", "${app.bookWords.size + app.globalWords.size}개 · 단어를 길게 눌러 바로 고칠 수 있어요", icon = R.drawable.ic_mic, more = true) {
                showPronunciations()
            }
        }.show()
    }

    private fun showDisplay() {
        var prefs = app.readerPrefs
        fun update(new: EpubPreferences) {
            prefs = new
            app.readerPrefs = new
            navigator.submitPreferences(new)
            applyPageTheme(new.theme ?: Theme.LIGHT)
        }
        Sheet(this, "보기").apply {
            choice("테마", null, listOf("라이트", "세피아", "다크"), THEMES.indexOf(prefs.theme ?: Theme.LIGHT).coerceAtLeast(0)) {
                update(prefs.copy(theme = THEMES[it]))
            }
            choice("글꼴", null, listOf("명조", "고딕"), if (prefs.fontFamily == FontFamily.SANS_SERIF) 1 else 0) {
                update(prefs.copy(fontFamily = if (it == 1) FontFamily.SANS_SERIF else FontFamily.SERIF))
            }
            // Each press re-lays out the book once; a slider would re-lay it out continuously while dragging.
            stepper("글자 크기", percent(prefs.fontSize ?: 1.0)) { delta ->
                val next = (((prefs.fontSize ?: 1.0) * 10).roundToInt() + delta).coerceIn(5, 25) / 10.0
                if (next == prefs.fontSize) null else percent(next).also { update(prefs.copy(fontSize = next)) }
            }
            stepper("줄 간격", "%.1f".format(prefs.lineHeight ?: 1.6)) { delta ->
                val next = (((prefs.lineHeight ?: 1.6) * 10).roundToInt() + delta).coerceIn(10, 25) / 10.0
                if (next == prefs.lineHeight) null else "%.1f".format(next).also { update(prefs.copy(lineHeight = next)) }
            }
            choice("볼륨 키로 페이지 넘기기", "아래 키는 다음 쪽, 위 키는 이전 쪽. 듣는 중에는 소리 크기를 바꿔요",
                listOf("켬", "끔"), if (app.volumeKeysTurnPages) 0 else 1) { app.volumeKeysTurnPages = it == 0 }
        }.show()
    }

    private fun showNarration() {
        Sheet(this, "낭독").apply {
            stepper("속도", speedLabel(app.speed)) { delta ->
                val next = SPEEDS.getOrNull(SPEEDS.indexOf(app.speed).coerceAtLeast(0) + delta) ?: return@stepper null
                app.speed = next
                speedLabel(next)
            }
            choice(
                "문장 사이 쉼",
                if (app.activeVoice == null) "음성 팩으로 읽을 때 적용돼요" else null,
                listOf("짧게", "보통", "길게"),
                PAUSES.indexOf(app.sentencePauseMs).takeIf { it >= 0 } ?: 1,
            ) { app.sentencePauseMs = PAUSES[it] }
            row("대사 목소리", app.dialogueVoiceName ?: "서술과 같은 목소리", icon = R.drawable.ic_dialogue, more = true) {
                showDialogueVoice()
            }
        }.show()
    }

    private fun showReadingOptions() {
        val options = app.readingOptions
        Sheet(this, "읽기 옵션").apply {
            choice("괄호 안 내용 읽기", "( )나 [ ] 안의 설명·부연", listOf("켬", "끔"), if (options.parentheses) 0 else 1) {
                app.readingOptions = app.readingOptions.copy(parentheses = it == 0)
            }
            choice("각주 기호 읽기", "¹ · [1] · * 같은 표시", listOf("켬", "끔"), if (options.footnoteMarks) 0 else 1) {
                app.readingOptions = app.readingOptions.copy(footnoteMarks = it == 0)
            }
            choice("영문 약어 풀어 읽기", "AI → 에이아이, TTS → 티티에스", listOf("켬", "끔"), if (options.spellAcronyms) 0 else 1) {
                app.readingOptions = app.readingOptions.copy(spellAcronyms = it == 0)
            }
        }.show()
    }

    /** Per-book voice for lines of dialogue, e.g. a more expressive model for a novel's quotes. */
    private fun showDialogueVoice() {
        val narration = app.activeVoice
            ?: return toast("대사를 다른 목소리로 읽으려면 서재의 목소리 버튼에서 음성 팩을 먼저 골라 주세요")
        Sheet(this, "대사 목소리", "따옴표로 시작하는 대사만 이 목소리로 읽어요. 이 책에만 적용돼요.").apply {
            row(
                "서술과 같은 목소리",
                "${app.narrationVoiceName} (${voiceLabel(narration)})",
                checked = app.dialogueVoice == null,
            ) { app.dialogueVoice = null }
            app.voicePacks().forEach { pack ->
                val current = app.dialogueVoice == pack
                row(
                    voiceLabel(pack),
                    if (current) "지금: ${app.voicesOf(pack).getOrNull(app.dialogueSpeaker)}" else "목소리 ${app.voicesOf(pack).size}개",
                    checked = current,
                    more = true,
                ) { pickDialogueVoice(pack) }
            }
        }.show()
    }

    /** The voices of [pack] for this book's dialogue; each tap selects it and says a sentence in it. */
    private fun pickDialogueVoice(pack: String) {
        val selected = if (app.dialogueVoice == pack) app.dialogueSpeaker else -1
        Sheet(this, voiceLabel(pack), "누르면 그 목소리로 고르고 들려 드려요").apply {
            pick(app.voicesOf(pack), selected) { sid ->
                app.setDialogueVoice(pack, sid)
                app.previewVoice(pack, sid)
            }
            onDismiss { app.warmUpVoice() }
        }.show()
    }

    /** Long-press selects a whole space-separated word; drop a particle glued to a Latin word ("Readium을"). */
    private suspend fun selectedWord(): String =
        navigator.currentSelection()?.locator?.text?.highlight?.trim().orEmpty()
            .replace(Regex("""^([A-Za-z0-9][A-Za-z0-9.\- ]*)[가-힣]{1,2}$"""), "$1")

    private fun showPronunciations() {
        val entries = app.bookWords.map { (from, to) -> Triple(from, to, true) } +
            app.globalWords.map { (from, to) -> Triple(from, to, false) }
        Sheet(this, "발음 사전", "본문에서 단어를 길게 누르고 ‘발음 고치기’를 고르면 바로 추가돼요.").apply {
            entries.forEach { (from, to, book) ->
                row("$from → $to", if (book) "이 책" else "모든 책") { editPronunciation(from, to, book, existing = true) }
            }
            button("새 발음 추가") { editPronunciation("", "", bookOnly = true, existing = false) }
        }.show()
    }

    private fun editPronunciation(from: String, to: String, bookOnly: Boolean, existing: Boolean) {
        fun field(hint: String, value: String) =
            TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                this.hint = hint
                addView(TextInputEditText(context).apply {
                    setText(value)
                    textSize = 18f
                })
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) }
            }
        val fromField = field("원문 (예: Readium)", from)
        val toField = field("읽는 법 (예: 리디움)", to)
        // Scope as two filled/unfilled buttons rather than a switch: unambiguous on e-ink.
        val scope = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) }
        }
        val (thisBook, allBooks) = listOf("이 책만", "모든 책").map { label ->
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
                scope.addView(this)
            }
        }
        scope.check(if (bookOnly) thisBook.id else allBooks.id)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(fromField)
            addView(toField)
            addView(scope)
        }
        fun words(book: Boolean) = if (book) app.bookWords else app.globalWords
        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing) "발음 고치기" else "발음 추가")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val newFrom = fromField.editText?.text.toString().trim()
                val newTo = toField.editText?.text.toString().trim()
                if (newFrom.isEmpty() || newTo.isEmpty()) return@setPositiveButton
                if (existing) words(bookOnly).remove(from)
                words(scope.checkedButtonId == thisBook.id)[newFrom] = newTo
                app.saveWords()
            }
            .apply {
                if (existing) setNeutralButton("삭제") { _, _ ->
                    words(bookOnly).remove(from)
                    app.saveWords()
                }
            }
            .setNegativeButton("취소", null)
            .show()
        (if (from.isEmpty()) fromField else toField).editText?.requestFocus()
    }
}

/**
 * The reader's root. It sees keys before the input method and before touch-mode handling: right after a touch on the
 * page, Android spends the first arrow or page key on leaving touch mode, so it never reached the activity and the first
 * notch of a scroll wheel did nothing.
 */
class PageKeyLayout(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    var onPageKey: ((AndroidKeyEvent) -> Boolean)? = null

    override fun dispatchKeyEventPreIme(event: AndroidKeyEvent): Boolean =
        onPageKey?.invoke(event) == true || super.dispatchKeyEventPreIme(event)
}
