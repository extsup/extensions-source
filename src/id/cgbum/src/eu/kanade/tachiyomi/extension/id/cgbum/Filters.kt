package eu.kanade.tachiyomi.extension.id.cgbum

import eu.kanade.tachiyomi.source.model.Filter

class TypeFilter : Filter.Select<String>(
    "Tipe",
    arrayOf("Semua", "Manga", "Manhwa", "Manhua"),
) {
    val options = arrayOf("", "manga", "manhwa", "manhua")
}

class StatusFilter : Filter.Select<String>(
    "Status",
    arrayOf("Semua", "Ongoing", "Tamat"),
) {
    val options = arrayOf("", "ongoing", "tamat")
}

class SortFilter : Filter.Select<String>(
    "Urutan",
    arrayOf("Update Terbaru", "Komik Terbaru", "Komik Terlama", "Banyak Views"),
) {
    val options = arrayOf("latest", "newest", "oldest", "views")
}

class GenreFilter(name: String) : Filter.CheckBox(name)

class GenreGroup : Filter.Group<GenreFilter>(
    "Genre",
    listOf(
        "action", "adaptation", "adventure", "age gap", "ahegao", "aliens", "anal",
        "animals", "anthology", "bdsm", "beasts", "big ass", "big breast", "big breasts",
        "big penis", "bisexual", "blackmail", "bloody", "blowjob", "body swap", "bodyswap",
        "bondage", "business suit", "cheating", "cheating infidelity", "childhood friends",
        "collar", "college life", "comedy", "condom", "cooking", "crime", "cunnilingus",
        "curse", "dark skin", "defloration", "delinquents", "demon girl", "demons",
        "double penetration", "doujinshi", "drama", "dungeons", "ecchi", "elf",
        "exhibitionism", "fantasy", "femdom", "fetish", "ffm threesome", "filming",
        "fingering", "footjob", "full color", "futanari", "game", "gender bender",
        "genderswap", "ghost", "ghosts", "glasses", "gore", "group", "gyaru", "handjob",
        "harem", "hentai", "historical", "horror", "horns", "huge breast", "humiliation",
        "inseki", "isekai", "josei", "josei w", "lactation", "lingerie", "lolicon",
        "magic", "maid", "manga", "manhwa", "manhua", "martial arts", "masturbation",
        "mature", "medical", "military", "milf", "mind break", "mind control",
        "mmf threesome", "monster girls", "monsters", "mother", "music", "mystery",
        "nakadashi", "netorare", "non human", "ntr", "obsessive male lead",
        "office workers", "omegaverse", "oneshot", "paizuri", "police", "pregnant",
        "psychological", "rape", "regression", "reincarnation", "revenge",
        "reverse harem", "rofan", "romance", "royal family", "royalty", "runaway",
        "school life", "sci fi", "seinen", "seinen m", "sex toys", "shoujo ai",
        "shoujo g", "shounen ai", "shounen b", "showbiz", "slice of life", "small breast",
        "smut", "sole female", "sole male", "space", "sports", "stocking", "story arc",
        "super power", "supernatural", "survival", "thriller", "time travel", "tomboy",
        "tower climbing", "traditional games", "tragedy", "transmigration", "twintails",
        "unusual pupils", "vampires", "video games", "villainess", "violence", "virginity",
        "virtual reality", "webtoon", "wuxia", "yakuzas", "yaoi bl", "yuri gl", "zombies",
    ).map { GenreFilter(it) },
)
