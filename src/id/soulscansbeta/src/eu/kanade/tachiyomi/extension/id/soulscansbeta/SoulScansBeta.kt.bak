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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class SoulScansBeta : HttpSource() {

    override val supportsLatest = true

    private val apiUrl = "https://img.soulscans.org/api"

    private val homeSectionsUrl = "$apiUrl/comic/home-sections"

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    // Cache untuk latest updates
    private var cachedLatestUpdates: List<kotlinx.serialization.json.JsonElement>? = null
    private var latestPage = 1

    // ==================== POPULAR ====================

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/search?type=COMIC&limit=24&page=$page&sort=views&order=desc")

    override fun popularMangaParse(response: Response): MangasPage {
        val obj = response.parseAs<JsonObject>()
        val data = requireNotNull(obj["data"]) { "Missing 'data' field" }.jsonArray
        val totalPages = requireNotNull(obj["total_pages"]) { "Missing 'total_pages' field" }.jsonPrimitive.int
        val page = requireNotNull(obj["page"]) { "Missing 'page' field" }.jsonPrimitive.int

        val mangas = data.map { parseMangaFromList(it.jsonObject) }
        return MangasPage(mangas, page < totalPages)
    }

    // ==================== LATEST ====================

    override fun latestUpdatesRequest(page: Int): Request {
        latestPage = page
        if (page == 1) {
            cachedLatestUpdates = null // Hapus cache agar data segar
        }
        if (cachedLatestUpdates == null) {
            return GET("$homeSectionsUrl?updateLimit=216&sections=latest_comic_updates")
        }
        // Halaman > 1 tidak perlu request
        return GET("$homeSectionsUrl?updateLimit=0&sections=latest_comic_updates")
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        if (cachedLatestUpdates == null) {
            val obj = response.parseAs<JsonObject>()
            cachedLatestUpdates = requireNotNull(obj["latest_comic_updates"]) { "Missing 'latest_comic_updates' field" }.jsonArray
        }

        val all = cachedLatestUpdates!!
        val start = (latestPage - 1) * 36
        val end = minOf(start + 36, all.size)
        val pageItems = all.subList(start, end)

        val mangas = pageItems.map { item ->
            val u = item.jsonObject
            SManga.create().apply {
                title = requireNotNull(u["series_title"]) { "Missing 'series_title'" }.jsonPrimitive.content
                url = requireNotNull(u["series_slug"]) { "Missing 'series_slug'" }.jsonPrimitive.content
                thumbnail_url = u["poster_image_url"]?.jsonPrimitive?.content
                status = when (u["series_status"]?.jsonPrimitive?.content) {
                    "ONGOING" -> SManga.ONGOING
                    "COMPLETED" -> SManga.COMPLETED
                    "HIATUS" -> SManga.ON_HIATUS
                    "DROPPED" -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }
        }

        val hasNextPage = end < all.size
        return MangasPage(mangas, hasNextPage)
    }

    // ==================== SEARCH ====================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$apiUrl/search?type=COMIC&limit=24&page=$page&sort=latest&order=desc&q=$query")

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== DETAIL ====================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/series/comic/${manga.url}")

    override fun mangaDetailsParse(response: Response): SManga {
        val obj = response.parseAs<JsonObject>()
        return SManga.create().apply {
            title = requireNotNull(obj["title"]) { "Missing 'title'" }.jsonPrimitive.content
            url = requireNotNull(obj["slug"]) { "Missing 'slug'" }.jsonPrimitive.content
            thumbnail_url = obj["poster_image_url"]?.jsonPrimitive?.content
            description = obj["synopsis"]?.jsonPrimitive?.content
            author = obj["author_name"]?.jsonPrimitive?.content
            artist = obj["artist_name"]?.jsonPrimitive?.content
            status = when (obj["comic_status"]?.jsonPrimitive?.content) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                "HIATUS" -> SManga.ON_HIATUS
                "DROPPED" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            genre = obj["genres"]?.jsonArray
                ?.joinToString { it.jsonObject["name"]!!.jsonPrimitive.content }
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/comic/${manga.url}"

    // ==================== CHAPTER LIST ====================

    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl/series/comic/${manga.url}")

    override fun chapterListParse(response: Response): List<SChapter> {
        val obj = response.parseAs<JsonObject>()
        val slug = requireNotNull(obj["slug"]) { "Missing 'slug'" }.jsonPrimitive.content
        val units = obj["units"]?.jsonArray ?: return emptyList()

        return units.map { unit ->
            val u = unit.jsonObject
            SChapter.create().apply {
                name = requireNotNull(u["title"]) { "Missing 'title'" }.jsonPrimitive.content
                url = "$slug/chapter/${requireNotNull(u["slug"]) { "Missing 'unit slug'" }.jsonPrimitive.content}"
                chapter_number = requireNotNull(u["number"]) { "Missing 'number'" }.jsonPrimitive.content.toFloatOrNull() ?: -1f
                date_upload = try {
                    LocalDateTime.parse(
                        requireNotNull(u["created_at"]) { "Missing 'created_at'" }.jsonPrimitive.content,
                        dateFormatter,
                    ).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    0L
                }
            }
        }.sortedByDescending { it.chapter_number }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/comic/${chapter.url}"

    // ==================== PAGES ====================

    override fun pageListRequest(chapter: SChapter): Request = GET("$apiUrl/series/comic/${chapter.url}")

    override fun pageListParse(response: Response): List<Page> {
        val obj = response.parseAs<JsonObject>()
        val chapter = requireNotNull(obj["chapter"]) { "Missing 'chapter'" }.jsonObject
        val pages = requireNotNull(chapter["pages"]) { "Missing 'pages'" }.jsonArray

        return pages.mapIndexed { index, page ->
            val p = page.jsonObject
            Page(
                index = index,
                imageUrl = requireNotNull(p["image_url"]) { "Missing 'image_url'" }.jsonPrimitive.content,
            )
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ==================== HELPERS ====================

    private fun parseMangaFromList(obj: JsonObject): SManga = SManga.create().apply {
        title = requireNotNull(obj["title"]) { "Missing 'title'" }.jsonPrimitive.content
        url = requireNotNull(obj["slug"]) { "Missing 'slug'" }.jsonPrimitive.content
        thumbnail_url = obj["poster_image_url"]?.jsonPrimitive?.content
        status = when (obj["comic_status"]?.jsonPrimitive?.content) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "HIATUS" -> SManga.ON_HIATUS
            "DROPPED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    override fun getFilterList() = FilterList()
}
