package eu.kanade.tachiyomi.extension.id.voratoon

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
class SeriesListDto(
    val data: List<SeriesItemDto>,
)

@Serializable
class SeriesItemDto(
    val id: Int,
    @SerialName("data") private val info: SeriesDataDto,
) {
    fun toSManga() = SManga.create().apply {
        url = info.slug
        title = info.title
        thumbnail_url = info.coverImage
        author = info.author
        status = when (info.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "dropped" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class SeriesDataDto(
    val title: String,
    val slug: String,
    val coverImage: String,
    val author: String? = null,
    val status: String? = null,
    val synopsis: String? = null,
    val format: String? = null,
    val nativeTitle: String? = null,
    val genres: List<GenreItemDto>? = null,
)

@Serializable
class SeriesDetailDto(
    @SerialName("data") val item: SeriesDetailItemDto,
)

@Serializable
class SeriesDetailItemDto(
    @SerialName("data") private val info: SeriesDataDto,
) {
    fun toSManga() = SManga.create().apply {
        url = info.slug
        title = info.title
        thumbnail_url = info.coverImage
        author = info.author
        description = buildString {
            info.nativeTitle?.let { append("Alt title: $it\n\n") }
            info.synopsis?.let { append(it) }
        }
        genre = buildList {
            info.genres?.forEach { add(it.data.name) }
            info.format?.let { add(it.replaceFirstChar { c -> c.uppercase() }) }
        }.joinToString(", ")
        status = when (info.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "dropped" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class GenreItemDto(
    val id: Int,
    @SerialName("data") val data: GenreDataDto,
)

@Serializable
class GenreDataDto(
    val name: String,
)

@Serializable
class GenreListDto(
    val data: List<GenreItemDto>,
)

@Serializable
class ChapterListDto(
    val data: List<ChapterItemDto>,
)

@Serializable
class ChapterItemDto(
    val id: Int,
    val createdAt: String,
    @SerialName("data") private val info: ChapterDataDto,
) {
    fun toSChapter(seriesSlug: String) = SChapter.create().apply {
        val indexStr = if (info.index % 1f == 0f) {
            info.index.toInt().toString()
        } else {
            info.index.toString()
        }
        url = "$seriesSlug/$indexStr"
        name = info.title ?: "Chapter $indexStr"
        chapter_number = info.index
        date_upload = Instant.parseOrNull(createdAt)?.toEpochMilliseconds() ?: 0L
    }
}

@Serializable
class ChapterDataDto(
    val index: Float,
    val title: String? = null,
)

@Serializable
class ChapterDetailDto(
    @SerialName("data") val item: ChapterDetailItemDto,
)

@Serializable
class ChapterDetailItemDto(
    @SerialName("data") val info: ChapterPagesDto,
)

@Serializable
class ChapterPagesDto(
    val images: List<String>,
)
