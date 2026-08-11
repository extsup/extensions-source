package eu.kanade.tachiyomi.extension.id.hwago

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

interface UriFilter {
    fun addToUri(builder: HttpUrl.Builder)
}

class SortFilter :
    UriPartFilter(
        name = "Sort",
        param = "sort",
        vals = arrayOf(
            "Latest" to "latest",
            "Popular" to "popular",
            "Rating" to "rating",
            "A-Z" to "az",
        ),
    )

class StatusFilter :
    UriPartFilter(
        name = "Status",
        param = "status",
        vals = arrayOf(
            "All" to "",
            "Ongoing" to "ongoing",
            "Completed" to "completed",
            "Hiatus" to "hiatus",
        ),
    )

class TypeFilter :
    UriPartFilter(
        name = "Type",
        param = "type",
        vals = arrayOf(
            "All" to "",
            "Manga" to "manga",
            "Manhwa" to "manhwa",
            "Manhua" to "manhua",
        ),
    )

class MinChaptersFilter :
    UriPartFilter(
        name = "Min Chapters",
        param = "minChapters",
        vals = arrayOf(
            "Any" to "",
            "5+" to "5",
            "10+" to "10",
            "25+" to "25",
            "50+" to "50",
            "100+" to "100",
        ),
    )

class GenreFilter :
    UriMultiSelectFilter(
        name = "Genre",
        param = "genre",
        vals = genres.map { it to it }.toTypedArray(),
    )

open class UriPartFilter(
    name: String,
    private val param: String,
    private val vals: Array<Pair<String, String>>,
) : Filter.Select<String>(
    name,
    vals.map { it.first }.toTypedArray(),
),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        val value = vals[state].second
        if (value.isNotBlank()) {
            builder.addQueryParameter(param, value)
        }
    }
}

open class UriMultiSelectOption(name: String, val value: String) : Filter.CheckBox(name)

open class UriMultiSelectFilter(
    name: String,
    private val param: String,
    vals: Array<Pair<String, String>>,
) : Filter.Group<UriMultiSelectOption>(
    name,
    vals.map { UriMultiSelectOption(it.first, it.second) },
),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        val checked = state.filter { it.state }.map { it.value }
        if (checked.isNotEmpty()) {
            builder.addQueryParameter(param, checked.joinToString(","))
        }
    }
}

private val genres = listOf(
    "action",
    "adaptation",
    "adult",
    "adventure",
    "age gap",
    "aliens",
    "animals",
    "anthology",
    "boys' love",
    "cheating",
    "childhood friends",
    "college life",
    "comedy",
    "cooking",
    "crime",
    "crossdressing",
    "delinquents",
    "demon",
    "demons",
    "drama",
    "dungeons",
    "ecchi",
    "fantasy",
    "femdom",
    "fight",
    "full color",
    "game",
    "gender bender",
    "ghosts",
    "girls love",
    "gore",
    "gyaru",
    "harem",
    "hentai",
    "historical",
    "horror",
    "harlequin",
    "huge breasts",
    "incest",
    "isekai",
    "josei",
    "kids",
    "long strip",
    "magic",
    "magical girls",
    "martial arts",
    "mature",
    "mecha",
    "medical",
    "military",
    "milf",
    "monster girls",
    "monsters",
    "murim",
    "music",
    "mystery",
    "netorare",
    "ninja",
    "non-human",
    "ntr",
    "office workers",
    "omegaverse",
    "one-shot",
    "parody",
    "philosophical",
    "police",
    "post-apocalyptic",
    "pregnant",
    "psychological",
    "regression",
    "reincarnation",
    "revenge",
    "reverse harem",
    "reverse isekai",
    "rofan",
    "romance",
    "royal family",
    "royalty",
    "samurai",
    "school life",
    "sci-fi",
    "seinen",
    "shotacon",
    "shoujo",
    "shoujo ai",
    "shounen",
    "shounen ai",
    "showbiz",
    "slice of life",
    "smut",
    "space",
    "sports",
    "super power",
    "superhero",
    "supernatural",
    "survival",
    "thriller",
    "time travel",
    "tower climbing",
    "tragedy",
    "transmigration",
    "vampires",
    "video games",
    "villainess",
    "violence",
    "virtual reality",
    "web comic",
    "webtoon",
    "wuxia",
    "xianxia",
    "xuanhuan",
    "yaoi",
    "yuri",
    "zombies",
)
