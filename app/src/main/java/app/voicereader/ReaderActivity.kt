package app.voicereader

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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
import org.readium.r2.shared.util.Url

private val SPEEDS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
private val PAUSES = listOf(60, 150, 320) // ms of silence between sentences: short, normal, long
private val THEMES = listOf(Theme.LIGHT, Theme.SEPIA, Theme.DARK)

class ReaderActivity : AppCompatActivity() {

    companion object {
        /** Start reading aloud from the saved position as soon as the book opens ("continue listening"). */
        const val AUTOPLAY = "autoplay"
    }

    private val app get() = application as App
    private lateinit var navigator: EpubNavigatorFragment
    private lateinit var topBar: View
    private var chromeVisible = true

    /** Adds "발음 고치기" to the text selection menu, next to Copy: long-press a word, fix how it is read. */
    private val selectionActions = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, R.id.fixPronunciation, 0, "발음 고치기")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != R.id.fixPronunciation) return false
            lifecycleScope.launch {
                val word = selectedWord() // read before the selection goes away
                mode.finish()
                editPronunciation(word, "", bookOnly = true, existing = false)
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {}
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
        findViewById<TextView>(R.id.bookTitle).text = app.book?.title
        applyPageTheme(app.readerPrefs.theme ?: Theme.LIGHT)
        app.warmUpVoice()

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.toc).setOnClickListener { showToc() }
        findViewById<View>(R.id.settings).setOnClickListener { showSettings() }

        val play = findViewById<MaterialButton>(R.id.play)
        val chapter = findViewById<TextView>(R.id.chapter)
        play.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.prev).setOnClickListener { app.tts.value?.skipToPreviousUtterance() }
        findViewById<View>(R.id.next).setOnClickListener { app.tts.value?.skipToNextUtterance() }

        // Taps near the edges and the device's page keys turn pages without animation, and put the top bar away
        // (that's reading); a tap in the middle shows or hides it.
        val pages = DirectionalNavigationAdapter(navigator, animatedTransition = false)
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                setChrome(if (pages.onTap(event)) false else !chromeVisible)
                return true
            }

            override fun onKey(event: KeyEvent): Boolean = pages.onKey(event).also { if (it) setChrome(false) }
        })

        if (savedInstanceState == null && intent.getBooleanExtra(AUTOPLAY, false) && app.tts.value == null) {
            lifecycleScope.launch { app.startTts(app.book?.locator) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.tts.flatMapLatest { it?.playback ?: flowOf(null) }
                        .map { it?.playWhenReady == true }
                        .distinctUntilChanged()
                        .collect { playing ->
                            play.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                            play.contentDescription = if (playing) "일시정지" else "재생"
                            if (playing) setChrome(false) // listening starts: the page is all that matters
                        }
                }
                launch {
                    navigator.currentLocator.collect { locator ->
                        chapter.text = listOfNotNull(
                            chapterTitle(locator.href),
                            locator.locations.totalProgression?.let { "${(it * 100).roundToInt()}%" },
                        ).joinToString("  ·  ")
                    }
                }
                val utterances = app.tts.flatMapLatest { nav -> nav?.location?.map { it.utteranceLocator } ?: flowOf(null) }
                    .distinctUntilChanged()
                // A jump into another chapter first lands wherever that chapter was pre-laid-out (e.g. its
                // last page), so re-sync once the navigator reports the new chapter.
                val chapters = navigator.currentLocator.map { it.href }.distinctUntilChanged()
                combine(utterances, chapters) { utterance, _ -> utterance }
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

    override fun onStop() {
        super.onStop()
        // While TTS runs, App saves its position in the background instead.
        if (::navigator.isInitialized && app.tts.value == null) app.saveLocator(navigator.currentLocator.value)
    }

    /** The top bar takes the page's colors, so it reads as part of the page; a rule separates it from the text. */
    private fun applyPageTheme(theme: Theme) {
        val page = theme.backgroundColor
        val ink = theme.contentColor
        val pressed = arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf())
        findViewById<View>(R.id.root).setBackgroundColor(page)
        topBar.setBackgroundColor(page)
        findViewById<View>(R.id.topBarRule).setBackgroundColor(ink)
        findViewById<TextView>(R.id.bookTitle).setTextColor(ink)
        findViewById<MaterialButton>(R.id.back).apply {
            iconTint = ColorStateList(pressed, intArrayOf(page, ink))
            backgroundTintList = ColorStateList(pressed, intArrayOf(ink, Color.TRANSPARENT))
        }
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            ColorUtils.calculateLuminance(page) > 0.5
    }

    /** Shows or hides the top bar at once: a fade would be a dozen e-ink refreshes. */
    private fun setChrome(visible: Boolean) {
        if (chromeVisible == visible) return
        chromeVisible = visible
        topBar.isVisible = visible
    }

    private fun togglePlay() {
        val nav = app.tts.value
        when {
            nav == null -> lifecycleScope.launch { app.startTts(navigator.firstVisibleElementLocator()) }
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

    private fun Link.isAt(href: Url) = url().removeFragment().isEquivalent(href.removeFragment())

    private fun chapterTitle(href: Url): String? = tocEntries().lastOrNull { (_, link) -> link.isAt(href) }?.second?.title

    private fun showToc() {
        val href = navigator.currentLocator.value.href
        Sheet(this, "목차").apply {
            tocEntries().forEach { (depth, link) ->
                row(link.title ?: link.href.toString(), indent = depth, current = link.isAt(href)) { go(link) }
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
                "${if (prefs.fontFamily == FontFamily.SANS_SERIF) "고딕" else "명조"} · ${percent(prefs.fontSize ?: 1.0)} · 줄 간격 ${"%.1f".format(prefs.lineHeight ?: 1.6)}",
                icon = R.drawable.ic_text,
            ) { showDisplay() }
            row(
                "낭독",
                "속도 ${speedLabel(app.speed)} · ${app.activeVoice?.let(::voiceLabel) ?: "기기 음성"}",
                icon = R.drawable.ic_headphones,
            ) { showNarration() }
            row(
                "읽기 옵션",
                "괄호 ${on(options.parentheses)} · 각주 기호 ${on(options.footnoteMarks)} · 약어 풀기 ${on(options.spellAcronyms)}",
                icon = R.drawable.ic_toc,
            ) { showReadingOptions() }
            row("발음 사전", "${app.bookWords.size + app.globalWords.size}개 · 단어를 길게 눌러 바로 고칠 수 있어요", icon = R.drawable.ic_mic) {
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
            row("대화문 목소리", app.dialogueVoice?.let(::voiceLabel) ?: "서술과 같은 목소리", icon = R.drawable.ic_dialogue) {
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
            ?: return toast("대화문 목소리를 따로 쓰려면 서재의 음성에서 음성 팩을 먼저 골라 주세요")
        Sheet(this, "대화문 목소리", "따옴표로 시작하는 대사만 이 목소리로 읽어요. 이 책에만 적용돼요.").apply {
            row("서술과 같은 목소리", voiceLabel(narration), checked = app.dialogueVoice == null) { app.dialogueVoice = null }
            (app.voicePacks() - narration).forEach { pack ->
                row(voiceLabel(pack), "음성 팩", checked = app.dialogueVoice == pack) {
                    app.dialogueVoice = pack
                    app.warmUpVoice()
                }
            }
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
