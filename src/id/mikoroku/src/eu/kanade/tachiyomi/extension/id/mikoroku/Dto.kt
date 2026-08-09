package eu.kanade.tachiyomi.extension.id.mikoroku

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class MangaEntry(
    val title: String = "",
    val altTitle: String = "",
    val slug: String = "",
    val img: String = "",
    val desc: String = "",
    val genres: List<String> = emptyList(),
    val rating: Double = 0.0,
    val status: String = "",
    val isUp: Boolean = false,
    val type: String = "",
    val author: String = "",
    val artist: String = "",
) {
    fun toSManga(resolveCover: (String) -> String) = SManga.create().apply {
        url = "/detail.html?slug=$slug"
        title = this@MangaEntry.title
        thumbnail_url = resolveCover(img)
        val typeLabel = if (this@MangaEntry.type.isNotBlank()) listOf(this@MangaEntry.type.replaceFirstChar { it.uppercase() }) else emptyList()
        genre = (genres + typeLabel).joinToString()
        description = buildString {
            append(desc)
            if (altTitle.isNotBlank()) {
                append("\n\nJudul Alternatif:\n")
                append(altTitle.replace("; ", "\n"))
            }
        }
        author = this@MangaEntry.author
        artist = this@MangaEntry.artist
        status = parseStatus(this@MangaEntry.status)
        initialized = true
    }

    private fun parseStatus(s: String): Int = when (s.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "dropped" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }
}

@Serializable
class BloggerFeed(
    val feed: BloggerFeedContent,
)

@Serializable
class BloggerFeedContent(
    @SerialName("entry") val entries: List<BloggerEntry>? = null,
)

@Serializable
class BloggerEntry(
    val title: BloggerText,
    val published: BloggerText,
    val link: List<BloggerLink> = emptyList(),
) {
    val links get() = link
}

@Serializable
class BloggerText(
    @SerialName("\$t") val value: String = "",
)

@Serializable
class BloggerLink(
    val rel: String = "",
    val href: String = "",
)
