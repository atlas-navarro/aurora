package com.aurora.music.data

data class LocalRescanResult(val added: Int, val removed: Int, val total: Int) {
    val changed: Boolean get() = added > 0 || removed > 0
}

object LocalRescan {
    val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "aiff", "aif", "wma", "alac", "ape", "dsf", "dff", "mka")

    fun result(before: Set<String>, after: Set<String>) =
        LocalRescanResult(added = (after - before).size, removed = (before - after).size, total = after.size)

    // the deepest matching rule wins; kept folders beat an excluded parent but not an exclusion of themselves
    fun inScope(path: String, folders: Collection<String>, excluded: Collection<String>, kept: Collection<String> = emptyList()): Boolean {
        if (folders.isEmpty() && excluded.isEmpty()) return true
        fun depth(roots: Collection<String>) = roots.map { it.trimEnd('/') }
            .filter { it.isNotBlank() && (path.equals(it, true) || path.startsWith("$it/", true)) }.maxOfOrNull { it.length } ?: -1
        val include = maxOf(depth(folders + kept), if (folders.isEmpty()) 0 else -1)
        return include >= 0 && include > depth(excluded)
    }

    fun roots(paths: Collection<String>): List<String> {
        val kept = ArrayList<String>()
        paths.map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct().sortedBy { it.length }.forEach { path ->
            if (kept.none { path == it || path.startsWith("$it/") }) kept += path
        }
        return kept
    }
}
