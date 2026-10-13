package app.voicereader

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceScanTest {

    @Test fun findsBooksButNotAppDataOrHiddenFolders() {
        val root = createTempDirectory("storage").toFile().apply { deleteOnExit() }
        fun touch(path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText("x") }
        touch("a.epub")
        touch("Books/Novels/b.EPUB")
        touch("Download/notes.txt")
        touch(".trash/c.epub")
        touch("Android/data/some.app/files/d.epub")
        touch("Documents/Android/e.epub") // only the volume's own Android/ folder is skipped

        val found = findEpubs(listOf(root, root)).map { it.relativeTo(root).path }.sorted()

        assertEquals(listOf("Books/Novels/b.EPUB", "Documents/Android/e.epub", "a.epub"), found)
        root.deleteRecursively()
    }
}
