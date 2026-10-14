package app.voicereader

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Books per shelf page: paged with ‹ › rather than scrolled sideways (e-ink redraws every scroll frame). */
private const val PER_SHELF_PAGE = 3

/** What the library list shows, top to bottom. */
private sealed interface Item {
    data object Header : Item
    data object Continue : Item
    data object Tabs : Item
    data object NewShelf : Item

    /** A shelf; [shelf] is null for the automatic ones (읽는 중, 분류 안 된 책). */
    class ShelfRow(val key: String, val title: String, val books: List<Book>, val shelf: Shelf?) : Item

    class Pin(val book: Book) : Item
}

class LibraryActivity : AppCompatActivity() {

    private val app get() = application as App
    private val adapter = Adapter()
    private var books: List<Book> = emptyList()

    /** Which page of three each shelf shows, by shelf key; kept while the screen lives. */
    private val shelfPages = mutableMapOf<String, Int>()

    private val pickBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            app.import(uri)?.let { toast(it) }
            refresh()
        }
    }

    /** Android 8–10: the storage permission dialog. (11+ grants "all files access" in system settings.) */
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) app.startDeviceScan(announce = true)
    }

    /** Set while the user is in system settings granting file access; checked when they come back. */
    private var askedForAllFiles = false

    private val pickVoiceStyle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importVoiceStyle(uri)
    }

    private val pickVoicePack = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            toast("음성 팩을 설치하는 중…")
            toast(app.importVoicePack(uri) ?: "음성 팩을 설치했어요. 대사만 다른 목소리로 들으려면 책을 연 뒤 설정 → 낭독에서 고르세요.")
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
        findViewById<View>(R.id.emptyFind).setOnClickListener { findOnDevice() }
        findViewById<View>(R.id.emptyPick).setOnClickListener { importBook() }

        // New EPUBs on the device show up by themselves (quietly, only if file access was granted).
        if (savedInstanceState == null) app.startDeviceScan(announce = false)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.scanProgress.collect { found ->
                        // Text only, no spinner: a spinner is a refresh per frame on e-ink. The list redraws once at the end.
                        if (found == null) refresh() else adapter.notifyItemChanged(0)
                    }
                }
                // A finished download may have set the first narrating voice, which the header's voice button shows.
                app.packDownload.drop(1).collect { if (it == null) refresh() else adapter.notifyItemChanged(0) }
            }
        }

        // Keep the "continue" card's play/pause icon in step with the player.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.tts.flatMapLatest { it?.playback ?: flowOf(null) }.collect {
                    if (adapter.items.getOrNull(1) == Item.Continue) adapter.notifyItemChanged(1)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (askedForAllFiles && app.canReadAllFiles()) {
            askedForAllFiles = false
            app.startDeviceScan(announce = true)
        }
    }

    private fun refresh() {
        books = app.books.sortedWith(compareByDescending<Book> { it.opened }.thenBy { it.title })
        adapter.items = buildList {
            add(Item.Header)
            if (books.isEmpty()) return@buildList
            add(Item.Continue)
            add(Item.Tabs)
            if (app.showShelves) addAll(shelves()) else books.mapTo(this) { Item.Pin(it) }
        }
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.empty).isVisible = books.isEmpty()
    }

    /** 읽는 중 (automatic), the user's shelves, then whatever isn't on a shelf yet. */
    private fun shelves(): List<Item> = buildList {
        val reading = books.filter { app.progress(it) in 1..99 }
        if (reading.isNotEmpty()) add(Item.ShelfRow("reading", "읽는 중", reading, null))
        app.shelves.forEach { shelf -> add(Item.ShelfRow(shelf.id, shelf.name, books.filter { it.id in shelf.bookIds }, shelf)) }
        val shelved = app.shelves.flatMapTo(HashSet()) { it.bookIds }
        val loose = books.filter { it.id !in shelved }
        if (loose.isNotEmpty()) add(Item.ShelfRow("loose", if (app.shelves.isEmpty()) "모든 책" else "분류 안 된 책", loose, null))
        add(Item.NewShelf)
    }

    // Some file pickers label .epub as octet-stream.
    private fun importBook() = pickBook.launch(arrayOf("application/epub+zip", "application/octet-stream"))

    private fun showAddSheet() {
        Sheet(this, "책 추가").apply {
            row(
                "기기에서 모두 찾기",
                if (app.canReadAllFiles()) "기기 안의 EPUB을 모두 찾아 모아요. 파일은 복사하지 않아요."
                else "‘모든 파일에 접근’을 허용하면 기기 안의 EPUB을 모두 찾아요",
                icon = R.drawable.ic_book,
            ) { findOnDevice() }
            row("파일 하나 고르기", "고른 EPUB을 서재로 복사해요", icon = R.drawable.ic_add) { importBook() }
        }.show()
    }

    private fun findOnDevice() {
        if (app.canReadAllFiles()) return app.startDeviceScan(announce = true)
        MaterialAlertDialogBuilder(this)
            .setTitle("기기의 EPUB 찾기")
            .message(
                "기기 안의 EPUB을 모두 찾으려면 ‘모든 파일에 접근’ 권한이 필요해요.\n\n" +
                    "책 파일을 읽기만 하고, 고치거나 지우거나 기기 밖으로 보내지 않아요. " +
                    "다음 화면에서 허용한 뒤 돌아오면 바로 찾기 시작해요.",
            )
            .setPositiveButton("설정 열기") { _, _ -> requestAllFiles() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT < 30) return storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        askedForAllFiles = true
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) // some devices only have the list
        }
    }

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

    // --- Book and shelf menus ---

    /** Long-press on a book. */
    private fun showBookActions(book: Book) {
        val onShelves = app.shelves.filter { book.id in it.bookIds }.joinToString { it.name }
        Sheet(this, book.title, book.author.ifBlank { null }).apply {
            row("책장 정하기", onShelves.ifEmpty { "아직 어느 책장에도 없어요" }, icon = R.drawable.ic_shelf, more = true) { pickShelves(book) }
            row(if (book.path != null) "서재에서 빼기" else "서재에서 삭제", icon = R.drawable.ic_delete) { confirmDelete(book) }
        }.show()
    }

    private fun pickShelves(book: Book) {
        Sheet(this, "책장 정하기", "이 책을 넣을 책장을 모두 고르세요").apply {
            app.shelves.forEach { shelf ->
                check(shelf.name, "${shelf.bookIds.size}권", book.id in shelf.bookIds) { on ->
                    if (on) shelf.bookIds += book.id else shelf.bookIds.remove(book.id)
                    app.saveShelves()
                }
            }
            button("새 책장 만들어 넣기") { nameShelf(null, withBook = book) }
            onDismiss { refresh() }
        }.show()
    }

    private fun showShelfActions(shelf: Shelf) {
        Sheet(this, shelf.name, "${shelf.bookIds.size}권").apply {
            row("책 넣고 빼기", icon = R.drawable.ic_check_box, more = true) { pickBooks(shelf) }
            row("이름 바꾸기", icon = R.drawable.ic_text) { nameShelf(shelf) }
            row("책장 지우기", "책은 서재에 그대로 남아요", icon = R.drawable.ic_delete) { confirmDeleteShelf(shelf) }
        }.show()
    }

    private fun pickBooks(shelf: Shelf) {
        Sheet(this, shelf.name, "이 책장에 둘 책을 모두 고르세요").apply {
            books.forEach { book ->
                check(book.title, book.author.ifBlank { null }, book.id in shelf.bookIds) { on ->
                    if (on) shelf.bookIds += book.id else shelf.bookIds.remove(book.id)
                    app.saveShelves()
                }
            }
            onDismiss { refresh() }
        }.show()
    }

    /** Creates a shelf (optionally putting [withBook] on it), or renames [shelf]. */
    private fun nameShelf(shelf: Shelf?, withBook: Book? = null) {
        val field = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "책장 이름 (예: 신앙 서적, 출퇴근용)"
            addView(TextInputEditText(context).apply {
                setText(shelf?.name.orEmpty())
                textSize = 18f
                isSingleLine = true
            })
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (shelf == null) "새 책장" else "책장 이름 바꾸기")
            .setView(box)
            .setPositiveButton(if (shelf == null) "만들기" else "바꾸기") { _, _ ->
                val name = field.editText?.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (shelf != null) {
                    shelf.name = name
                    app.saveShelves()
                } else {
                    val created = app.addShelf(name)
                    withBook?.let {
                        created.bookIds += it.id
                        app.saveShelves()
                    }
                }
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
        field.editText?.requestFocus()
    }

    private fun confirmDeleteShelf(shelf: Shelf) {
        MaterialAlertDialogBuilder(this)
            .setTitle("‘${shelf.name}’ 지우기")
            .message("책장만 지우고, 그 안의 책은 서재에 그대로 남아요.")
            .setPositiveButton("지우기") { _, _ ->
                app.deleteShelf(shelf)
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmDelete(book: Book) {
        // No particle right after the title in these texts: it would depend on the title's last syllable.
        val message = if (book.path != null) {
            "서재에서 이 책을 뺄까요? 기기의 파일은 그대로 두고, 다음 찾기에서도 다시 넣지 않아요."
        } else {
            "서재에서 이 책을 지울까요? 읽던 위치와 이 책의 발음 사전도 함께 지워져요."
        }
        // A book read in place is only taken off the list (its file stays); a copied one is deleted. Say which.
        val action = if (book.path != null) "빼기" else "삭제"
        MaterialAlertDialogBuilder(this)
            .setTitle(if (book.path != null) "‘${book.title}’ 서재에서 빼기" else "‘${book.title}’ 삭제")
            .message(message)
            .setPositiveButton(action) { _, _ ->
                app.deleteBook(book)
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** The narration voice for every book: the system TTS, or a voice of an installed pack. */
    private fun showVoices() {
        Sheet(this, "목소리", "모든 책을 읽어 줄 목소리예요. 대사만 다른 목소리로 들으려면 책을 연 뒤 설정 → 낭독 → 대사 목소리에서 고르세요.").apply {
            row("기기 기본 음성", "이 기기에 설치된 음성", icon = R.drawable.ic_voice, checked = app.activeVoice == null) {
                app.activeVoice = null
                refresh()
            }
            val packs = app.voicePacks()
            packs.forEach { pack ->
                val current = pack == app.activeVoice
                row(
                    voiceLabel(pack),
                    listOfNotNull(
                        if (current) "지금: ${app.narrationVoiceName}" else "목소리 ${app.voicesOf(pack).size}개",
                        DOWNLOADABLE_PACKS.find { it.name == pack }?.note,
                    ).joinToString(" · "),
                    icon = R.drawable.ic_headphones,
                    checked = current,
                    more = true,
                ) { pickNarrationVoice(pack) }
            }
            DOWNLOADABLE_PACKS.filter { it.name !in packs }.forEach { source ->
                val downloading = app.packDownload.value?.first == source.name
                row(
                    "${voiceLabel(source.name)} 받기",
                    if (downloading) "받는 중…" else "약 ${source.bytes / 1_000_000}MB · ${source.note}",
                    icon = R.drawable.ic_add,
                ) { if (!downloading) confirmDownload(source) }
            }
            button("음성 팩 파일로 설치") { confirmConsent { pickVoicePack.launch(arrayOf("application/zip", "application/octet-stream")) } }
            button("직접 만든 목소리 넣기") {
                confirmConsent { pickVoiceStyle.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            }
        }.show()
    }

    private fun confirmDownload(source: PackSource) {
        MaterialAlertDialogBuilder(this)
            .setTitle("${voiceLabel(source.name)} 받기")
            .message(
                "인터넷(Hugging Face)에서 음성 팩 약 ${source.bytes / 1_000_000}MB를 받아요. Wi-Fi에서 받기를 권해요.\n\n" +
                    "받는 동안 서재 위쪽에 진행률이 보여요.",
            )
            .setPositiveButton("받기") { _, _ -> app.downloadVoicePack(source) }
            .setNegativeButton("취소", null)
            .show()
    }

    /** The voices of [pack]; each tap selects it and says a sentence in it. */
    private fun pickNarrationVoice(pack: String) {
        val selected = if (app.activeVoice == pack) app.activeSpeaker else -1
        Sheet(this, voiceLabel(pack), "누르면 그 목소리로 고르고 들려 드려요").apply {
            pick(app.voicesOf(pack), selected) { sid ->
                app.setNarrationVoice(pack, sid)
                app.previewVoice(pack, sid)
            }
            onDismiss { refresh() }
        }.show()
    }

    /** A voice file made elsewhere goes into one installed pack: the one whose model version made it. */
    private fun importVoiceStyle(uri: Uri) {
        val packs = app.voicePacks()
        fun into(pack: String) = lifecycleScope.launch {
            val error = app.importVoiceStyle(uri, pack)
            if (error != null) return@launch toast(error)
            toast("목소리를 넣었어요. 목록 맨 아래에 있어요.")
            pickNarrationVoice(pack)
        }
        when (packs.size) {
            0 -> toast("목소리 파일을 넣을 Supertonic 음성 팩을 먼저 가져와 주세요")
            1 -> into(packs.single())
            else -> Sheet(this, "어느 음성 팩에 넣을까요?", "목소리 파일을 만든 모델 버전과 같은 음성 팩을 고르세요 (예: Supertonic 2용 파일은 Supertonic 2에).").apply {
                packs.forEach { pack -> row(voiceLabel(pack), "목소리 ${app.voicesOf(pack).size}개", icon = R.drawable.ic_headphones) { into(pack) } }
            }.show()
        }
    }

    // PRD ethics rule: only voices that are the user's own or used with the speaker's explicit consent.
    private fun confirmConsent(then: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("목소리 사용 동의")
            .message(
                "본인의 목소리이거나, 화자에게 이 앱에서 쓰는 것에 대해 명시적인 동의를 받은 목소리만 가져올 수 있어요.\n\n" +
                    "실존 인물(성우·배우·가수 등)의 목소리를 동의 없이 복제한 음성 팩이나 목소리 파일은 가져오지 마세요.",
            )
            .setPositiveButton("동의하고 파일 고르기") { _, _ -> then() }
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

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var items: List<Item> = emptyList()

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = when (items[position]) {
            Item.Header -> R.layout.library_header
            Item.Continue -> R.layout.library_continue
            Item.Tabs -> R.layout.library_tabs
            Item.NewShelf -> R.layout.library_new_shelf
            is Item.ShelfRow -> R.layout.library_shelf
            is Item.Pin -> R.layout.library_item
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = inflater.inflate(viewType, parent, false)
            if (viewType != R.layout.library_item) {
                // From the MarginLayoutParams, or the layout's margins are dropped and neighbors touch.
                view.layoutParams = StaggeredGridLayoutManager.LayoutParams(view.layoutParams as ViewGroup.MarginLayoutParams)
                    .apply { isFullSpan = true }
            }
            if (viewType == R.layout.library_shelf) {
                val covers = view.findViewById<LinearLayout>(R.id.covers)
                val titles = view.findViewById<LinearLayout>(R.id.titles)
                repeat(PER_SHELF_PAGE) {
                    covers.addView(inflater.inflate(R.layout.shelf_cover, covers, false))
                    titles.addView(TextView(parent.context).apply {
                        textSize = 15f
                        setTextColor(getColor(R.color.ink))
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(5), 0, dp(5), 0)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            when (val item = items[position]) {
                Item.Header -> bindHeader(view)
                Item.Continue -> bindContinue(view, books.first())
                Item.Tabs -> bindTabs(view)
                Item.NewShelf -> view.findViewById<View>(R.id.newShelf).setOnClickListener { nameShelf(null) }
                is Item.ShelfRow -> bindShelf(view, item, position)
                is Item.Pin -> bindPin(view, item.book)
            }
        }

        private fun bindHeader(view: View) {
            view.findViewById<TextView>(R.id.count).text = app.scanProgress.value?.let { "찾는 중… 새 책 ${it}권" }
                ?: app.packDownload.value?.let { (pack, percent) -> "${voiceLabel(pack)} 받는 중 $percent%" }
                ?: if (books.isEmpty()) "" else "${books.size}권"
            view.findViewById<View>(R.id.actions).isVisible = books.isNotEmpty() // the empty state has its own buttons
            view.findViewById<MaterialButton>(R.id.voice).apply {
                text = "목소리 · ${app.narrationVoiceName}"
                setOnClickListener { showVoices() }
            }
            view.findViewById<View>(R.id.add).setOnClickListener { showAddSheet() }
        }

        private fun bindContinue(view: View, book: Book) {
            val progress = app.progress(book)
            val playing = app.book?.id == book.id && app.tts.value?.playback?.value?.playWhenReady == true
            bindCover(book, view.findViewById(R.id.cover))
            view.findViewById<TextView>(R.id.label).text = when {
                playing -> "지금 듣는 중"
                progress == null -> "읽기 시작"
                else -> "이어 듣기"
            }
            view.findViewById<TextView>(R.id.title).text = book.title.keepWords()
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

        private fun bindTabs(view: View) {
            view.findViewById<MaterialButton>(R.id.tabAll).text = "모든 책 ${books.size}"
            view.findViewById<MaterialButtonToggleGroup>(R.id.tabs).apply {
                clearOnButtonCheckedListeners()
                check(if (app.showShelves) R.id.tabShelves else R.id.tabAll)
                addOnButtonCheckedListener { _, id, checked ->
                    if (checked && (id == R.id.tabShelves) != app.showShelves) {
                        app.showShelves = id == R.id.tabShelves
                        post { refresh() } // not from inside this bind
                    }
                }
            }
        }

        private fun bindShelf(view: View, row: Item.ShelfRow, position: Int) {
            val lastPage = ((row.books.size - 1) / PER_SHELF_PAGE).coerceAtLeast(0)
            val page = (shelfPages[row.key] ?: 0).coerceIn(0, lastPage)
            val shown = row.books.drop(page * PER_SHELF_PAGE).take(PER_SHELF_PAGE)
            view.findViewById<TextView>(R.id.name).text = row.title
            view.findViewById<TextView>(R.id.count).text =
                if (lastPage == 0) "${row.books.size}권"
                else "${row.books.size}권 중 ${page * PER_SHELF_PAGE + 1}~${page * PER_SHELF_PAGE + shown.size}번째"
            fun turn(delta: Int) {
                shelfPages[row.key] = page + delta
                notifyItemChanged(position) // only this shelf redraws
            }
            view.findViewById<View>(R.id.prevPage).apply {
                isInvisible = page == 0
                setOnClickListener { turn(-1) }
            }
            view.findViewById<View>(R.id.nextPage).apply {
                isInvisible = page == lastPage
                setOnClickListener { turn(1) }
            }
            view.findViewById<View>(R.id.more).apply {
                isVisible = row.shelf != null
                setOnClickListener { row.shelf?.let(::showShelfActions) }
            }
            view.findViewById<View>(R.id.empty).isVisible = row.books.isEmpty()
            val covers = view.findViewById<LinearLayout>(R.id.covers)
            val titles = view.findViewById<LinearLayout>(R.id.titles)
            for (i in 0 until PER_SHELF_PAGE) {
                val slot = covers.getChildAt(i)
                val title = titles.getChildAt(i) as TextView
                val book = shown.getOrNull(i)
                slot.isInvisible = book == null
                title.isInvisible = book == null
                if (book == null) continue
                bindCover(book, slot.findViewById(R.id.cover), slot.findViewById(R.id.placeholder))
                title.text = book.title.keepWords()
                slot.contentDescription = book.title
                slot.setOnClickListener { open(book) }
                slot.setOnLongClickListener {
                    showBookActions(book)
                    true
                }
            }
        }

        private fun bindPin(view: View, book: Book) {
            bindCover(book, view.findViewById(R.id.cover), view.findViewById(R.id.placeholder))
            view.findViewById<TextView>(R.id.title).text = book.title.keepWords()
            view.findViewById<TextView>(R.id.meta).text = meta(book)
            view.setOnClickListener { open(book) }
            view.setOnLongClickListener {
                showBookActions(book)
                true
            }
        }
    }
}
