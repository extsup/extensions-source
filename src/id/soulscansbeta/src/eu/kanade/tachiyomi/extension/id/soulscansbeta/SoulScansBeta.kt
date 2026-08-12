package eu.kanade.tachiyomi.extension.id.soulscansbeta

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.time.Instant

@Source
abstract class SoulScansBeta : HttpSource() {

    override val supportsLatest = true

    private val apiUrl = "https://img.soulscans.org/api"

    private val homeSectionsUrl = "$apiUrl/comic/home-sections"

    private var cachedLatestUpdates: List<SManga>? = null

    // ==================== POPULAR ====================

    override fun popularMangaRequest(page: Int): Request = GET(
        "$apiUrl/search?type=COMIC&limit=24&page=$page&sort=views&order=desc",
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val obj = response.parseAs<JsonObject>()

        val data = requireNotNull(obj["data"]) {
            "Missing 'data' field"
        }.jsonArray

        val totalPages = requireNotNull(obj["total_pages"]) {
            "Missing 'total_pages' field"
        }.jsonPrimitive.int

        val page = requireNotNull(obj["page"]) {
            "Missing 'page' field"
        }.jsonPrimitive.int

        val mangas = data.map { item ->
            parseMangaFromList(item.jsonObject)
        }

        return MangasPage(
            mangas = mangas,
            hasNextPage = page < totalPages,
        )
    }

    // ==================== LATEST ====================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$homeSectionsUrl?updateLimit=216&sections=latest_comic_updates")

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        if (page == 1) cachedLatestUpdates = null

        return if (cachedLatestUpdates == null) {
            super.fetchLatestUpdates(page)
        } else {
            Observable.just(paginateFromCache(page))
        }
    }

    private fun paginateFromCache(page: Int): MangasPage {
        val all = cachedLatestUpdates.orEmpty()
        val start = (page - 1) * 36
        val end = minOf(start + 36, all.size)

        val mangas = if (start < all.size) all.subList(start, end) else emptyList()

        val hasNextPage = end < all.size
        if (!hasNextPage) cachedLatestUpdates = null

        return MangasPage(mangas = mangas, hasNextPage = hasNextPage)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val obj = response.parseAs<JsonObject>()

        cachedLatestUpdates = requireNotNull(obj["latest_comic_updates"]) {
            "Missing 'latest_comic_updates' field"
        }.jsonArray.map { item ->
            val o = item.jsonObject
            SManga.create().apply {
                title = requireNotNull(o["series_title"]) { "Missing 'series_title'" }.jsonPrimitive.content
                url = "/" + requireNotNull(o["series_slug"]) { "Missing 'series_slug'" }.jsonPrimitive.content
                thumbnail_url = o["poster_image_url"]?.jsonPrimitive?.content
                status = when (o["series_status"]?.jsonPrimitive?.content) {
                    "ONGOING" -> SManga.ONGOING
                    "COMPLETED" -> SManga.COMPLETED
                    "HIATUS" -> SManga.ON_HIATUS
                    "DROPPED" -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }
        }

        return paginateFromCache(1)
    }

    // ==================== SEARCH ====================

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = GET(
        "$apiUrl/search?type=COMIC&limit=24&page=$page&sort=latest&order=desc&q=$query",
    )

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== DETAIL ====================

    override fun mangaDetailsRequest(manga: SManga): Request = comicRequest(manga)

    override fun getMangaUrl(manga: SManga): String =
        "$baseUrl/comic/${manga.url.trimStart('/')}"

    override fun getChapterUrl(chapter: SChapter): String =
        "$baseUrl/comic/${chapter.url.trimStart('/')}"

    override fun mangaDetailsParse(response: Response): SManga {
        val obj = response.parseAs<JsonObject>()

        return SManga.create().apply {
            title = requireNotNull(obj["title"]) {
                "Missing 'title'"
            }.jsonPrimitive.content

            url = "/" + requireNotNull(obj["slug"]) {
                "Missing 'slug'"
            }.jsonPrimitive.content

            thumbnail_url = obj["poster_image_url"]
                ?.jsonPrimitive
                ?.content

            description = obj["synopsis"]
                ?.jsonPrimitive
                ?.content

            author = obj["author_name"]
                ?.jsonPrimitive
                ?.content

            artist = obj["artist_name"]
                ?.jsonPrimitive
                ?.content

            status = when (
                obj["comic_status"]
                    ?.jsonPrimitive
                    ?.content
            ) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                "HIATUS" -> SManga.ON_HIATUS
                "DROPPED" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }

            genre = listOfNotNull(
                obj["comic_subtype"]?.jsonPrimitive?.content
                    ?.lowercase()
                    ?.replaceFirstChar { it.uppercase() },
                obj["genres"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
                    ?.joinToString(),
            ).filter { it.isNotEmpty() }
                .joinToString()
                .ifEmpty { null }
        }
    }

    // ==================== CHAPTER LIST ====================

    override fun chapterListRequest(manga: SManga): Request = comicRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val obj = response.parseAs<JsonObject>()

        val slug = requireNotNull(obj["slug"]) {
            "Missing 'slug'"
        }.jsonPrimitive.content

        val units = obj["units"]
            ?.jsonArray
            ?: return emptyList()

        return units
            .map { unit ->
                val chapter = unit.jsonObject

                SChapter.create().apply {
                    val num = requireNotNull(chapter["number"]) {
                        "Missing 'number'"
                    }.jsonPrimitive.content.toFloatOrNull() ?: -1f
                    name = if (num >= 0f) {
                        "Chapter ${num.toInt()}"
                    } else {
                        chapter["title"]?.jsonPrimitive?.content ?: "Chapter"
                    }

                    url = "$slug/chapter/${
                        requireNotNull(chapter["slug"]) {
                            "Missing 'unit slug'"
                        }.jsonPrimitive.content
                    }"

                    chapter_number = requireNotNull(chapter["number"]) {
                        "Missing 'number'"
                    }.jsonPrimitive.content
                        .toFloatOrNull()
                        ?: -1f

                    date_upload = parseDate(
                        chapter["created_at"]
                            ?.jsonPrimitive
                            ?.content,
                    )
                }
            }
            .sortedByDescending { it.chapter_number }
    }

    // ==================== PAGES ====================
    
    override fun pageListRequest(chapter: SChapter): Request =
    GET("$seriesUrl/${chapter.url.trimStart('/')}")

    override fun pageListParse(response: Response): List<Page> {
        val obj = response.parseAs<JsonObject>()

        val chapter = requireNotNull(obj["chapter"]) {
            "Missing 'chapter'"
        }.jsonObject

        val pages = requireNotNull(chapter["pages"]) {
            "Missing 'pages'"
        }.jsonArray

        return pages.mapIndexed { index, page ->
            val pageObject = page.jsonObject

            Page(
                index = index,
                imageUrl = requireNotNull(
                    pageObject["image_url"],
                ) {
                    "Missing 'image_url'"
                }.jsonPrimitive.content,
            )
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ==================== HELPERS ====================
    
    private val seriesUrl = "$apiUrl/series/comic"

    private fun comicRequest(manga: SManga): Request =
    GET("$seriesUrl/${manga.url.trimStart('/')}")

    private fun parseMangaFromList(obj: JsonObject): SManga = SManga.create().apply {
        title = requireNotNull(obj["title"]) {
            "Missing 'title'"
        }.jsonPrimitive.content

        url = "/" + requireNotNull(obj["slug"]) {
            "Missing 'slug'"
        }.jsonPrimitive.content

        thumbnail_url = obj["poster_image_url"]
            ?.jsonPrimitive
            ?.content

        status = when (
            obj["comic_status"]
                ?.jsonPrimitive
                ?.content
        ) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "HIATUS" -> SManga.ON_HIATUS
            "DROPPED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseDate(value: String?): Long {
        if (value == null) return 0L
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    override fun getFilterList() = FilterList()
}
