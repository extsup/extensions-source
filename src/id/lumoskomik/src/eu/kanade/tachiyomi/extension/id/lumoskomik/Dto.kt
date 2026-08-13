package eu.kanade.tachiyomi.extension.id.lumoskomik

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class PopularDto(val data: List<ComicDto>)

@Serializable
class SearchDto(val results: List<ComicDto>)

@Serializable
class ComicDto(
    private val slug: String,
    private val title: String,
    private val coverImage: String?,
    private val type: String? = null,
    private val status: String? = null,
    private val author: String? = null,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = slug
        this.title = this@ComicDto.title
        thumbnail_url = coverImage?.let {
            if (it.startsWith("/")) "$baseUrl$it" else it
        }
        this.author = this@ComicDto.author
        this.status = when (this@ComicDto.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class NextPagesDto(
    val pages: List<String>,
    val nextSlug: String? = null,
)

@Serializable
class LdJsonDto(
    @SerialName("@type") val type: String = "",
    val name: String? = null,
    val description: String? = null,
    val image: String? = null,
    val author: PersonDto? = null,
    val illustrator: PersonDto? = null,
    val genre: List<String>? = null,
)

@Serializable
class PersonDto(val name: String)
