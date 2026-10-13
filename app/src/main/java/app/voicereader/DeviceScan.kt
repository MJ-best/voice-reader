package app.voicereader

import java.io.File

/**
 * Every .epub under [roots]. Skips hidden folders and the volume's Android/ folder (other apps' data and caches),
 * which are slow to walk and never hold the user's books. Unreadable folders are passed over.
 */
fun findEpubs(roots: List<File>): List<File> = roots.flatMap { root ->
    root.walkTopDown()
        .onEnter { dir -> dir == root || !(dir.name.startsWith(".") || dir.parentFile == root && dir.name == "Android") }
        .filter { it.isFile && it.extension.equals("epub", ignoreCase = true) }
        .toList()
}.distinctBy { it.canonicalPath }
