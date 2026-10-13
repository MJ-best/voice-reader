package app.voicereader

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class LibraryActivity : AppCompatActivity() {

    private val app get() = application as App
    private lateinit var adapter: ArrayAdapter<Book>

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            app.import(uri)?.let { toast(it) }
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.library)

        adapter = object : ArrayAdapter<Book>(this, 0) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.library_item, parent, false)
                val book = getItem(position)!!
                view.findViewById<TextView>(R.id.title).text = book.title
                view.findViewById<TextView>(R.id.author).text = book.author
                view.findViewById<ImageView>(R.id.cover).setImageBitmap(BitmapFactory.decodeFile(app.cover(book).path))
                return view
            }
        }
        val list = findViewById<ListView>(R.id.list)
        list.emptyView = findViewById(R.id.empty)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> open(adapter.getItem(position)!!) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.clear()
        adapter.addAll(app.books.sortedByDescending { it.opened })
    }

    private fun open(book: Book) = lifecycleScope.launch {
        if (app.openBook(book)) startActivity(Intent(this@LibraryActivity, ReaderActivity::class.java))
        else toast("책을 열 수 없습니다")
    }

    private val pickVoicePack = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            toast("음성 팩을 설치하는 중…")
            toast(app.importVoicePack(uri) ?: "음성 팩을 설치했습니다: ${app.activeVoice}")
        }
    }

    private fun showVoices() {
        val packs = app.voicePacks()
        val labels = listOf("기기 기본 음성 (Android TTS)") + packs
        AlertDialog.Builder(this)
            .setTitle("낭독 음성")
            .setSingleChoiceItems(labels.toTypedArray(), packs.indexOf(app.activeVoice) + 1) { dialog, i ->
                app.activeVoice = packs.getOrNull(i - 1)
                dialog.dismiss()
            }
            .setPositiveButton("음성 팩 가져오기") { _, _ -> confirmVoiceConsent() }
            .setNegativeButton("닫기", null)
            .show()
    }

    // PRD ethics rule: only voices that are the user's own or used with the speaker's explicit consent.
    private fun confirmVoiceConsent() {
        AlertDialog.Builder(this)
            .setTitle("음성 팩 가져오기")
            .setMessage(
                "본인의 목소리이거나, 화자에게 이 앱에서 쓰는 것에 대해 명시적인 동의를 받은 목소리만 가져올 수 있습니다.\n\n" +
                    "실존 인물(성우·배우·가수 등)의 목소리를 동의 없이 복제한 음성 팩은 가져오지 마세요.",
            )
            .setPositiveButton("동의하고 zip 선택") { _, _ ->
                pickVoicePack.launch(arrayOf("application/zip", "application/octet-stream"))
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add("음성")
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
            .setOnMenuItemClickListener { showVoices(); true }
        menu.add("가져오기")
            .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
            .setOnMenuItemClickListener {
                // Some file pickers label .epub as octet-stream.
                pick.launch(arrayOf("application/epub+zip", "application/octet-stream"))
                true
            }
        return true
    }
}
