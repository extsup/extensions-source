package eu.kanade.tachiyomi.extension.id.komikucom

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// ── Popular / Latest / Search ────────────────────────────────────────────────

@Serializable
class ComicsResponse(
    val data: List<ComicItem>,
    val meta: Meta,
)

@Serializable
class Meta(
    @SerialName("total_count") val totalCount: Int,
    @SerialName("current_page") val currentPage: Int,
    @SerialName("per_page") val perPage: Int,
    @SerialName("total_pages") val totalPages: Int,
)

@Serializable
class ComicItem(
    val id: Int,
    val slug: String,
    val title: String,
    @SerialName("cover_image") private val coverImage: String?,
    private val synopsis: String? = null,
    private val genres: List<GenreItem>? = null,
    private val status: String? = null,
    private val author: String? = null,
    private val type: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@ComicItem.title
        thumbnail_url = coverImage
    }

    fun toSMangaFull() = SManga.create().apply {
        url = slug
        title = this@ComicItem.title
        thumbnail_url = coverImage
        description = synopsis
        genre = buildList {
            type?.let { add(it) }
            genres?.forEach { add(it.name) }
        }.joinToString()
        author = this@ComicItem.author
        this.status = when (this@ComicItem.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }
}

@Serializable
class GenreItem(val name: String)

// ── Detail ────────────────────────────────────────────────────────────────────

@Serializable
class ComicDetailResponse(val data: ComicItem)

// ── Chapters ─────────────────────────────────────────────────────────────────

@Serializable
class ChaptersResponse(val data: List<ChapterItem>)

@Serializable
class ChapterItem(
    private val id: Int,
    @SerialName("chapter_number") private val chapterNumber: String,
    @SerialName("chapter_title") private val chapterTitle: String?,
    @SerialName("comic_slug") private val comicSlug: String,
    @SerialName("created_at") private val createdAt: String?,
) {
    fun toSChapter() = SChapter.create().apply {
        // url encodes both slug and chapter metadata needed to build the reader URL
        // format: {comicSlug}|{chapterId}|{chapterNumber}
        url = "$comicSlug|$id|$chapterNumber"
        name = buildString {
            append("Chapter $chapterNumber")
            if (!chapterTitle.isNullOrBlank()) append(": $chapterTitle")
        }
        date_upload = createdAt?.let {
            runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrDefault(0L)
        } ?: 0L
        chapter_number = chapterNumber.toFloatOrNull() ?: -1f
    }
}
