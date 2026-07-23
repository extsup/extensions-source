package eu.kanade.tachiyomi.extension.id.soulscansbeta

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

@Source
class SoulScansBeta : HttpSource() {

    override val name = "Soul Scans Beta"
    override val baseUrl = "https://v1.soulscans.asia"
    override val lang = "id"
    override val supportsLatest = true

    private val apiUrl = "https://img.soulscans.asia/api"

    private val json: Json by injectLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    // ==================== POPULAR ====================

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/search?type=COMIC&limit=24&page=$page&sort=latest&order=desc")

    override fun popularMangaParse(response: Response): MangasPage {
        val obj = json.parseToJsonElement(response.body.string()).jsonObject
        val data = obj["data"]!!.jsonArray
        val totalPages = obj["total_pages"]!!.jsonPrimitive.int
        val page = obj["page"]!!.jsonPrimitive.int

        val mangas = data.map { parseMangaFromList(it.jsonObject) }
        return MangasPage(mangas, page < totalPages)
    }

    // ==================== LATEST ====================

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/search?type=COMIC&limit=24&page=$page&sort=latest&order=desc")

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== SEARCH ====================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$apiUrl/search?type=COMIC&limit=24&page=$page&sort=latest&order=desc&q=$query")

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== DETAIL ====================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/series/comic/${manga.url}")

    override fun mangaDetailsParse(response: Response): SManga {
        val obj = json.parseToJsonElement(response.body.string()).jsonObject
        return SManga.create().apply {
            title = obj["title"]!!.jsonPrimitive.content
            url = obj["slug"]!!.jsonPrimitive.content
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

    // ==================== CHAPTER LIST ====================

    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl/series/comic/${manga.url}")

    override fun chapterListParse(response: Response): List<SChapter> {
        val obj = json.parseToJsonElement(response.body.string()).jsonObject
        val slug = obj["slug"]!!.jsonPrimitive.content
        val units = obj["units"]?.jsonArray ?: return emptyList()

        return units.map { unit ->
            val u = unit.jsonObject
            SChapter.create().apply {
                name = u["title"]!!.jsonPrimitive.content
                url = "$slug/${u["slug"]!!.jsonPrimitive.content}"
                chapter_number = u["number"]!!.jsonPrimitive.content.toFloatOrNull() ?: -1f
                date_upload = runCatching {
                    dateFormat.parse(u["created_at"]!!.jsonPrimitive.content)!!.time
                }.getOrDefault(0L)
            }
        }
    }

    // ==================== PAGES ====================

    override fun pageListRequest(chapter: SChapter): Request = GET("$apiUrl/series/comic/${chapter.url}")

    override fun pageListParse(response: Response): List<Page> {
        val obj = json.parseToJsonElement(response.body.string()).jsonObject
        val pages = obj["chapter"]!!.jsonObject["pages"]!!.jsonArray

        return pages.mapIndexed { index, page ->
            val p = page.jsonObject
            Page(
                index = index,
                imageUrl = p["image_url"]!!.jsonPrimitive.content,
            )
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ==================== HELPERS ====================

    private fun parseMangaFromList(obj: JsonObject): SManga = SManga.create().apply {
        title = obj["title"]!!.jsonPrimitive.content
        url = obj["slug"]!!.jsonPrimitive.content
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
