package eu.kanade.tachiyomi.extension.id.komikucom

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

// ── Popular / Latest / Search ─────────────────────────────────────────────────

@Serializable
class ComicsResponse(
    val items: List<ComicItem>,
    val totalPages: Int,
)

@Serializable
class ComicItem(
    val id: Int,
    val slug: String,
    val title: String,
    val coverUrl: String? = null,
    private val alt: String? = null,
    private val genres: List<String>? = null,
    private val status: String? = null,
    private val author: String? = null,
    private val artist: String? = null,
    private val type: String? = null,
    private val synopsis: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@ComicItem.title
        thumbnail_url = coverUrl
    }

    fun toSMangaFull() = SManga.create().apply {
        url = slug
        title = this@ComicItem.title
        thumbnail_url = coverUrl
        description = buildString {
            synopsis?.let { append(it) }
            alt?.let {
                if (synopsis != null) append("\n\n")
                append("Judul Alternatif: $it")
            }
        }
        genre = buildList {
            genres?.forEach { add(it) }
            type?.let { add(it) }
        }.joinToString()
        author = this@ComicItem.author
        artist = this@ComicItem.artist
        artist = this@ComicItem.artist
        this.status = when (this@ComicItem.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }
}

// ── Detail ────────────────────────────────────────────────────────────────────

// Detail endpoint returns a flat ComicItem directly (no wrapper)

// ── Chapters ──────────────────────────────────────────────────────────────────

// Chapters endpoint returns a plain array of ChapterItem

@Serializable
class ChapterItem(
    @SerialName("n") private val chapterNumber: Float,
    private val title: String? = null,
    private val releasedLabel: String? = null,
    private val id: Int,
) {
    fun toSChapter(comicSlug: String) = SChapter.create().apply {
        url = "$comicSlug|$id|$chapterNumber"
        name = title ?: "Chapter $chapterNumber"
        date_upload = releasedLabel?.let {
            runCatching {
                SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).parse(it)?.time
            }.getOrNull()
        } ?: 0L
        chapter_number = chapterNumber
    }
}
