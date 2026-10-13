package app.voicereader

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class LibraryActivity : AppCompatActivity() {

    private val app get() = application as App
    private val adapter = Adapter()

    private val pickBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            app.import(uri)?.let { toast(it) }
            refresh()
        }
    }

    private val pickVoicePack = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            toast("음성 팩을 설치하는 중…")
            toast(app.importVoicePack(uri) ?: "음성 팩을 설치했어요. 대화문 목소리는 책을 연 뒤 ⋮ 메뉴에서 고를 수 있어요.")
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.library)

        val grid = findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        grid.adapter = adapter
        grid.itemAnimator = null // no cross-fades: every animation frame is a refresh on e-ink
        findViewById<View>(R.id.emptyAdd).setOnClickListener { importBook() }

        // Keep the "continue" card's play/pause icon in step with the player.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.tts.flatMapLatest { it?.playback ?: flowOf(null) }.collect { adapter.notifyItemChanged(1) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.books = app.books.sortedByDescending { it.opened }
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.empty).isVisible = adapter.books.isEmpty()
    }

    // Some file pickers label .epub as octet-stream.
    private fun importBook() = pickBook.launch(arrayOf("application/epub+zip", "application/octet-stream"))

    private fun open(book: Book, autoplay: Boolean = false) = lifecycleScope.launch {
        if (!app.openBook(book)) return@launch toast("책을 열 수 없어요")
        startActivity(Intent(this@LibraryActivity, ReaderActivity::class.java).putExtra(ReaderActivity.AUTOPLAY, autoplay))
    }

    /** The continue card's button: pause/resume what is playing, or open the book and start listening. */
    private fun playOrPause(book: Book) {
        val nav = app.tts.value
        when {
            nav == null || app.book?.id != book.id -> open(book, autoplay = true)
            nav.playback.value.playWhenReady -> nav.pause()
            else -> nav.play()
        }
    }

    private fun confirmDelete(book: Book) {
        MaterialAlertDialogBuilder(this)
            .setTitle("‘${book.title}’ 삭제")
            .setMessage("서재에서 이 책을 지울까요? 읽던 위치와 이 책의 발음 사전도 함께 지워져요.") // no particle after the title: it depends on the last syllable
            .setPositiveButton("삭제") { _, _ ->
                app.deleteBook(book)
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showVoices() {
        Sheet(this, "낭독 음성", "모든 책의 서술에 쓰는 목소리예요. 대화문 목소리는 책을 연 뒤 ⋮ 메뉴에서 따로 고를 수 있어요.").apply {
            row("기기 기본 음성", "Android TTS", icon = R.drawable.ic_voice, checked = app.activeVoice == null) {
                app.activeVoice = null
                refresh()
            }
            app.voicePacks().forEach { pack ->
                row(voiceLabel(pack), "음성 팩", icon = R.drawable.ic_headphones, checked = pack == app.activeVoice) {
                    app.activeVoice = pack
                    refresh()
                }
            }
            button("음성 팩 가져오기") { confirmVoiceConsent() }
        }.show()
    }

    // PRD ethics rule: only voices that are the user's own or used with the speaker's explicit consent.
    private fun confirmVoiceConsent() {
        MaterialAlertDialogBuilder(this)
            .setTitle("목소리 사용 동의")
            .setMessage(
                "본인의 목소리이거나, 화자에게 이 앱에서 쓰는 것에 대해 명시적인 동의를 받은 목소리만 가져올 수 있어요.\n\n" +
                    "실존 인물(성우·배우·가수 등)의 목소리를 동의 없이 복제한 음성 팩은 가져오지 마세요.",
            )
            .setPositiveButton("동의하고 zip 선택") { _, _ ->
                pickVoicePack.launch(arrayOf("application/zip", "application/octet-stream"))
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun meta(book: Book) = listOf(book.author, app.progress(book)?.let { "$it%" }).filter { !it.isNullOrBlank() }.joinToString(" · ")

    /** The cover, or (no cover) the title in large type on white. */
    private fun bindCover(book: Book, cover: ImageView, placeholder: TextView? = null) {
        val bitmap = BitmapFactory.decodeFile(app.cover(book).path)
        cover.setImageBitmap(bitmap)
        placeholder?.apply {
            isVisible = bitmap == null
            text = book.title
        }
    }

    /** Header, then a full-width "continue" card for the latest book, then every book as a pin. */
    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var books: List<Book> = emptyList()

        override fun getItemCount() = if (books.isEmpty()) 1 else books.size + 2

        override fun getItemViewType(position: Int) = when (position) {
            0 -> R.layout.library_header
            1 -> R.layout.library_continue
            else -> R.layout.library_item
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
            if (viewType != R.layout.library_item) {
                view.layoutParams = StaggeredGridLayoutManager.LayoutParams(view.layoutParams).apply { isFullSpan = true }
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            when (getItemViewType(position)) {
                R.layout.library_header -> {
                    view.findViewById<TextView>(R.id.count).text = if (books.isEmpty()) "" else "${books.size}권"
                    view.findViewById<View>(R.id.actions).isVisible = books.isNotEmpty() // empty state has its own button
                    view.findViewById<MaterialButton>(R.id.voice).apply {
                        text = app.activeVoice?.let(::voiceLabel) ?: "기기 음성"
                        setOnClickListener { showVoices() }
                    }
                    view.findViewById<View>(R.id.add).setOnClickListener { importBook() }
                }
                R.layout.library_continue -> {
                    val book = books.first()
                    val progress = app.progress(book)
                    val playing = app.book?.id == book.id && app.tts.value?.playback?.value?.playWhenReady == true
                    bindCover(book, view.findViewById(R.id.cover))
                    view.findViewById<TextView>(R.id.label).text = when {
                        playing -> "지금 듣는 중"
                        progress == null -> "읽기 시작"
                        else -> "이어 듣기"
                    }
                    view.findViewById<TextView>(R.id.title).text = book.title
                    view.findViewById<TextView>(R.id.meta).text = meta(book)
                    view.findViewById<LinearProgressIndicator>(R.id.progress).apply {
                        isVisible = progress != null
                        setProgressCompat(progress ?: 0, false)
                    }
                    view.findViewById<MaterialButton>(R.id.play).apply {
                        setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                        contentDescription = if (playing) "일시정지" else "이어 듣기"
                        setOnClickListener { playOrPause(book) }
                    }
                    view.setOnClickListener { open(book) }
                }
                else -> {
                    val book = books[position - 2]
                    bindCover(book, view.findViewById(R.id.cover), view.findViewById(R.id.placeholder))
                    view.findViewById<TextView>(R.id.title).text = book.title
                    view.findViewById<TextView>(R.id.meta).text = meta(book)
                    view.setOnClickListener { open(book) }
                    view.setOnLongClickListener {
                        confirmDelete(book)
                        true
                    }
                }
            }
        }
    }
}
