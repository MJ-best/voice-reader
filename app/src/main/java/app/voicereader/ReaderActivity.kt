package app.voicereader

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.indexOfFirstWithHref

private val SPEEDS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
private const val HIGHLIGHT = 0xFFFFD54F.toInt()

class ReaderActivity : AppCompatActivity() {

    private val app get() = application as App
    private lateinit var navigator: EpubNavigatorFragment

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
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.reader)
        title = app.book?.title
        navigator = supportFragmentManager.findFragmentById(R.id.navigator) as EpubNavigatorFragment

        val play = findViewById<ImageButton>(R.id.play)
        val speed = findViewById<Button>(R.id.speed)
        play.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.prev).setOnClickListener { app.tts.value?.skipToPreviousUtterance() }
        findViewById<View>(R.id.next).setOnClickListener { app.tts.value?.skipToNextUtterance() }
        findViewById<View>(R.id.prevChapter).setOnClickListener { skipChapter(-1) }
        findViewById<View>(R.id.nextChapter).setOnClickListener { skipChapter(1) }
        speed.text = "${app.speed}x"
        speed.setOnClickListener {
            app.speed = SPEEDS[(SPEEDS.indexOf(app.speed) + 1) % SPEEDS.size]
            speed.text = "${app.speed}x"
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.tts.flatMapLatest { it?.playback ?: flowOf(null) }.collect { playback ->
                        val playing = playback?.playWhenReady == true
                        play.setImageResource(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                        play.contentDescription = if (playing) "일시정지" else "재생"
                    }
                }
                val utterances = app.tts.flatMapLatest { nav -> nav?.location?.map { it.utteranceLocator } ?: flowOf(null) }
                    .distinctUntilChanged()
                // A jump into another chapter first lands wherever that chapter was pre-laid-out (e.g. its
                // last page), so re-sync once the navigator reports the new chapter.
                val chapter = navigator.currentLocator.map { it.href }.distinctUntilChanged()
                combine(utterances, chapter) { utterance, _ -> utterance }
                    .collect { utterance ->
                        val highlight = utterance?.let { Decoration("tts", it, Decoration.Style.Highlight(tint = HIGHLIGHT)) }
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

    private fun skipChapter(delta: Int) {
        if (app.tts.value != null) return app.skipChapter(delta)
        val readingOrder = app.publication?.readingOrder ?: return
        val index = readingOrder.indexOfFirstWithHref(navigator.currentLocator.value.href) ?: return
        readingOrder.getOrNull(index + delta)?.let { navigator.go(it) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add("목차").setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
            .setOnMenuItemClickListener { showToc(); true }
        menu.add("발음").setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
            .setOnMenuItemClickListener { showPronunciations(); true }
        menu.add("보기").setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
            .setOnMenuItemClickListener { showSettings(); true }
        return true
    }

    /** With text selected (long-press), jumps straight to fixing it; otherwise lists the dictionary. */
    private fun showPronunciations() = lifecycleScope.launch {
        // Long-press selects a whole space-separated word; drop a particle glued to a Latin word ("Readium을").
        val selected = navigator.currentSelection()?.locator?.text?.highlight?.trim().orEmpty()
            .replace(Regex("""^([A-Za-z0-9][A-Za-z0-9.\- ]*)[가-힣]{1,2}$"""), "$1")
        if (selected.isNotEmpty()) {
            navigator.clearSelection()
            return@launch editPronunciation(selected, "", bookOnly = true, existing = false)
        }
        val entries = app.bookWords.map { (from, to) -> Triple(from, to, true) } +
            app.globalWords.map { (from, to) -> Triple(from, to, false) }
        val dialog = AlertDialog.Builder(this@ReaderActivity)
            .setTitle("발음 사전")
            .setPositiveButton("추가") { _, _ -> editPronunciation("", "", bookOnly = true, existing = false) }
            .setNegativeButton("닫기", null)
        if (entries.isEmpty()) {
            dialog.setMessage("본문에서 단어를 길게 눌러 선택한 뒤 '발음'을 누르면 바로 고칠 수 있습니다.")
        } else {
            val labels = entries.map { (from, to, book) -> "$from → $to" + if (book) "  · 이 책" else "" }
            dialog.setItems(labels.toTypedArray()) { _, i ->
                val (from, to, book) = entries[i]
                editPronunciation(from, to, book, existing = true)
            }
        }
        dialog.show()
    }

    private fun editPronunciation(from: String, to: String, bookOnly: Boolean, existing: Boolean) {
        val fromField = EditText(this).apply { hint = "원문 (예: Readium)"; setText(from) }
        val toField = EditText(this).apply { hint = "읽는 법 (예: 리디움)"; setText(to) }
        val bookOnlyBox = CheckBox(this).apply { text = "이 책에만 적용"; isChecked = bookOnly }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 0)
            addView(fromField)
            addView(toField)
            addView(bookOnlyBox)
        }
        fun words(book: Boolean) = if (book) app.bookWords else app.globalWords
        AlertDialog.Builder(this)
            .setTitle("발음 고치기")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val newFrom = fromField.text.toString().trim()
                val newTo = toField.text.toString().trim()
                if (newFrom.isEmpty() || newTo.isEmpty()) return@setPositiveButton
                if (existing) words(bookOnly).remove(from)
                words(bookOnlyBox.isChecked)[newFrom] = newTo
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
        toField.requestFocus()
    }

    private fun showToc() {
        val pub = app.publication ?: return
        val entries = mutableListOf<Pair<Int, Link>>()
        fun walk(links: List<Link>, depth: Int) {
            for (link in links) {
                entries += depth to link
                walk(link.children, depth + 1)
            }
        }
        walk(pub.tableOfContents.ifEmpty { pub.readingOrder }, 0)
        AlertDialog.Builder(this)
            .setTitle("목차")
            .setItems(entries.map { (depth, link) -> "    ".repeat(depth) + (link.title ?: link.href.toString()) }.toTypedArray()) { _, i ->
                go(entries[i].second)
            }
            .show()
    }

    private fun showSettings() {
        var prefs = app.readerPrefs
        fun update(new: EpubPreferences) {
            prefs = new
            app.readerPrefs = new
            navigator.submitPreferences(new)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 0)
            addView(radios(listOf("라이트", "다크", "세피아"), listOf(Theme.LIGHT, Theme.DARK, Theme.SEPIA), prefs.theme) {
                update(prefs.copy(theme = it))
            })
            addView(radios(listOf("명조", "고딕"), listOf(FontFamily.SERIF, FontFamily.SANS_SERIF), prefs.fontFamily) {
                update(prefs.copy(fontFamily = it))
            })
            addView(slider("글자 크기", 0.5, 2.5, prefs.fontSize ?: 1.0) { update(prefs.copy(fontSize = it)) })
            addView(slider("줄 간격", 1.0, 2.5, prefs.lineHeight ?: 1.6) { update(prefs.copy(lineHeight = it)) })
        }
        AlertDialog.Builder(this).setTitle("보기 설정").setView(box).setPositiveButton("닫기", null).show()
    }

    private fun <T> radios(labels: List<String>, values: List<T>, current: T?, pick: (T) -> Unit) =
        RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            labels.forEachIndexed { i, label ->
                addView(RadioButton(context).apply { id = i + 1; text = label; isChecked = values[i] == current })
            }
            setOnCheckedChangeListener { _, id -> pick(values[id - 1]) }
        }

    private fun slider(label: String, min: Double, max: Double, current: Double, pick: (Double) -> Unit): View {
        val text = TextView(this)
        fun show(value: Double) { text.text = "$label  ${"%.1f".format(value)}" }
        show(current)
        val bar = SeekBar(this).apply {
            this.max = ((max - min) * 10).roundToInt()
            progress = ((current - min) * 10).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = show(min + progress / 10.0)
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) = pick(min + bar.progress / 10.0)
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 0)
            addView(text)
            addView(bar)
        }
    }
}
