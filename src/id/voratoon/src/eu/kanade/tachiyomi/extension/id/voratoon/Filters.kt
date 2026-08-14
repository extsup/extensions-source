package eu.kanade.tachiyomi.extension.id.voratoon

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter :
    Filter.Select<String>(
        "Urutkan",
        arrayOf("Terbaru", "Terpopuler", "Nama A-Z"),
    ) {
    val apiValue get() = when (state) {
        0 -> "latest"
        1 -> "views"
        2 -> "title"
        else -> "latest"
    }
}

class StatusFilter :
    Filter.Select<String>(
        "Status",
        arrayOf("Semua", "Ongoing", "Completed", "Hiatus", "Dropped"),
    ) {
    val apiValue get() = when (state) {
        1 -> "ongoing"
        2 -> "completed"
        3 -> "hiatus"
        4 -> "dropped"
        else -> null
    }
}

class FormatFilter :
    Filter.Select<String>(
        "Format",
        arrayOf("Semua", "Manhwa", "Manga", "Manhua", "Webtoon"),
    ) {
    val apiValue get() = when (state) {
        1 -> "manhwa"
        2 -> "manga"
        3 -> "manhua"
        4 -> "webtoon"
        else -> null
    }
}

class GenreFilter(genres: List<GenreItemDto>) :
    Filter.Select<String>(
        "Genre",
        arrayOf("Semua") + genres.map { it.data.name }.toTypedArray(),
    ) {
    private val ids = listOf(null) + genres.map { it.id }
    val selectedId get() = ids[state]
}
