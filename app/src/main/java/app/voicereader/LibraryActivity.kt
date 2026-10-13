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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
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
