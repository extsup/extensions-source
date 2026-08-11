package eu.kanade.tachiyomi.extension.id.hwago

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter : Filter.Select<String>(
    "Sort",
    arrayOf("Latest", "Popular", "Rating", "A-Z"),
    1,
) {
    val values = arrayOf("latest", "popular", "rating", "az")
    fun selectedValue() = values[state]
}

class StatusFilter : Filter.Select<String>(
    "Status",
    arrayOf("All", "Ongoing", "Completed", "Hiatus"),
) {
    val values = arrayOf("", "ongoing", "completed", "hiatus")
    fun selectedValue() = values[state]
}

class TypeFilter : Filter.Select<String>(
    "Type",
    arrayOf("All", "Manga", "Manhwa", "Manhua"),
) {
    val values = arrayOf("", "manga", "manhwa", "manhua")
    fun selectedValue() = values[state]
}

class MinChaptersFilter : Filter.Select<String>(
    "Min Chapters",
    arrayOf("Any", "5+", "10+", "25+", "50+", "100+"),
) {
    val values = arrayOf("", "5", "10", "25", "50", "100")
    fun selectedValue() = values[state]
}

class GenreFilter : Filter.Group<GenreCheckBox>(
    "Genre",
    genres.map { GenreCheckBox(it) },
)

class GenreCheckBox(name: String) : Filter.CheckBox(name)

private val genres = listOf(
    "action", "adaptation", "adult", "adventure", "age gap",
    "aliens", "animals", "anthology", "boys' love", "cheating",
    "childhood friends", "college life", "comedy", "cooking", "crime",
    "crossdressing", "delinquents", "demon", "demons", "drama",
    "dungeons", "ecchi", "fantasy", "femdom", "fight",
    "full color", "game", "gender bender", "ghosts", "girls love",
    "gore", "gyaru", "harem", "hentai", "historical",
    "horror", "harlequin", "huge breasts", "incest", "isekai",
    "josei", "kids", "long strip", "magic", "magical girls",
    "martial arts", "mature", "mecha", "medical", "military",
    "milf", "monster girls", "monsters", "murim", "music",
    "mystery", "netorare", "ninja", "non-human", "ntr",
    "office workers", "omegaverse", "one-shot", "parody", "philosophical",
    "police", "post-apocalyptic", "pregnant", "psychological", "regression",
    "reincarnation", "revenge", "reverse harem", "reverse isekai", "rofan",
    "romance", "royal family", "royalty", "samurai", "school life",
    "sci-fi", "seinen", "shotacon", "shoujo", "shoujo ai",
    "shounen", "shounen ai", "showbiz", "slice of life", "smut",
    "space", "sports", "super power", "superhero", "supernatural",
    "survival", "thriller", "time travel", "tower climbing", "tragedy",
    "transmigration", "vampires", "video games", "villainess", "violence",
    "virtual reality", "web comic", "webtoon", "wuxia", "xianxia",
    "xuanhuan", "yaoi", "yuri", "zombies",
)
