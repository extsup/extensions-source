package eu.kanade.tachiyomi.extension.id.komikucom

import eu.kanade.tachiyomi.source.model.Filter

abstract class SelectFilter(
    name: String,
    private val options: Array<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    fun selectedValue() = options[state].second
}

class StatusFilter : SelectFilter(
    "Status",
    arrayOf(
        Pair("Semua", ""),
        Pair("Ongoing", "Ongoing"),
        Pair("Completed", "Completed"),
    ),
)

class TypeFilter : SelectFilter(
    "Type",
    arrayOf(
        Pair("Semua", ""),
        Pair("Manga", "Manga"),
        Pair("Manhwa", "Manhwa"),
        Pair("Manhua", "Manhua"),
        Pair("Mangatoon", "Mangatoon"),
    ),
)

class OrderFilter : SelectFilter(
    "Urutkan",
    arrayOf(
        Pair("Views", "views"),
        Pair("Update Terbaru", "updated_at"),
        Pair("Ditambahkan", "created_at"),
    ),
)

class OrderDirFilter : SelectFilter(
    "Urutan",
    arrayOf(
        Pair("Descending", "desc"),
        Pair("Ascending", "asc"),
    ),
)

class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

class GenreFilter : Filter.Group<GenreCheckBox>(
    "Genre",
    GENRES.map { GenreCheckBox(it.first, it.second) },
)

val GENRES = listOf(
    Pair("4-Koma", "4-Koma"),
    Pair("Action", "Action"),
    Pair("Adaptation", "Adaptation"),
    Pair("Adventure", "Adventure"),
    Pair("Apocalypse", "apocalypse"),
    Pair("Comedy", "Comedy"),
    Pair("Cooking", "Cooking"),
    Pair("Crime", "Crime"),
    Pair("Demon", "Demon"),
    Pair("Drama", "Drama"),
    Pair("Ecchi", "Ecchi"),
    Pair("Fantasy", "Fantasy"),
    Pair("Game", "Game"),
    Pair("Gender Bender", "Gender bender"),
    Pair("Harem", "Harem"),
    Pair("Historical", "Historical"),
    Pair("Horror", "Horror"),
    Pair("Isekai", "Isekai"),
    Pair("Josei", "Josei"),
    Pair("Magic", "Magic"),
    Pair("Martial Arts", "Martial Arts"),
    Pair("Mature", "Mature"),
    Pair("Mecha", "Mecha"),
    Pair("Medical", "Medical"),
    Pair("Military", "Military"),
    Pair("Monsters", "Monsters"),
    Pair("Murim", "murim"),
    Pair("Mystery", "Mystery"),
    Pair("One-Shot", "One-Shot"),
    Pair("Police", "Police"),
    Pair("Psychological", "Psychological"),
    Pair("Regression", "Regression"),
    Pair("Reincarnation", "Reincarnation"),
    Pair("Revenge", "Revenge"),
    Pair("Romance", "Romance"),
    Pair("School", "School"),
    Pair("School Life", "School Life"),
    Pair("Sci-Fi", "Sci-Fi"),
    Pair("Seinen", "Seinen"),
    Pair("Shoujo", "Shoujo"),
    Pair("Shoujo Ai", "Shoujo Ai"),
    Pair("Shounen", "Shounen"),
    Pair("Shounen Ai", "Shounen Ai"),
    Pair("Slice of Life", "Slice of Life"),
    Pair("Sports", "Sports"),
    Pair("Super Power", "Super Power"),
    Pair("Superhero", "Superhero"),
    Pair("Supernatural", "Supernatural"),
    Pair("Survival", "Survival"),
    Pair("System", "System"),
    Pair("Thriller", "Thriller"),
    Pair("Tragedy", "Tragedy"),
    Pair("Vampire", "Vampire"),
    Pair("Webtoons", "Webtoons"),
    Pair("Wuxia", "Wuxia"),
)
