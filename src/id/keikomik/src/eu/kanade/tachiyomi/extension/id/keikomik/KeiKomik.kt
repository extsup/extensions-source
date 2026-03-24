package eu.kanade.tachiyomi.extension.id.keikomik

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class KeiKomik : HttpSource() {

    override val name = "KeiKomik"
    override val baseUrl = "https://keikomik.web.id"
    override val lang = "id"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json by lazy { Json { ignoreUnknownKeys = true } }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).also {
        it.timeZone = TimeZone.getTimeZone("UTC")
    }

    // ── buildId dari homepage __NEXT_DATA__ ───────────────────
    private var cachedBuildId: String? = null

    private fun buildId(): String {
        cachedBuildId?.let { return it }
        val html = client.newCall(GET(baseUrl, headers)).execute().body.string()
        val match = Regex(""""buildId"\s*:\s*"([^"]+)"""").find(html)
            ?: throw Exception("buildId tidak ditemukan")
        return match.groupValues[1].also { cachedBuildId = it }
    }

    private fun nextDataUrl(path: String) =
        "$baseUrl/_next/data/${buildId()}/$path.json"

    // ── Helper: JsonObject → SManga ───────────────────────────
    private fun itemToManga(item: JsonObject): SManga {
        fun str(key: String) = item[key]?.jsonPrimitive?.content ?: ""
        return SManga.create().apply {
            url = "/komik/${str("slug")}"
            title = str("name")
            thumbnail_url = str("image")
            author = str("author")
            genre = item["genre"]?.jsonArray
                ?.joinToString { it.jsonPrimitive.content }
            status = when (str("status")) {
                "Ongoing" -> SManga.ONGOING
                "Completed" -> SManga.COMPLETED
                "Hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            description = buildString {
                val desc = str("description")
                if (desc.isNotEmpty()) appendLine(desc)
                item["rate"]?.jsonPrimitive?.content
                    ?.let { appendLine("⭐ $it") }
                val type = str("type")
                if (type.isNotEmpty()) appendLine("Type: $type")
                val rilis = str("rilis")
                if (rilis.isNotEmpty()) appendLine("Rilis: $rilis")
            }.trim()
        }
    }

    // ── Helper: parse list dari response /list ────────────────
    private fun parseListResponse(response: Response, page: Int): MangasPage {
        val pageProps = json.parseToJsonElement(response.body.string())
            .jsonObject["pageProps"]?.jsonObject
            ?: return MangasPage(emptyList(), false)

        // data bisa berupa array atau object tergantung versi site
        val allMangas = pageProps["data"]?.let { data ->
            when {
                data is kotlinx.serialization.json.JsonArray ->
                    data.mapNotNull { runCatching { itemToManga(it.jsonObject) }.getOrNull() }
                data is JsonObject && data.containsKey("data") ->
                    data["data"]?.jsonArray
                        ?.mapNotNull { runCatching { itemToManga(it.jsonObject) }.getOrNull() }
                else -> null
            }
        } ?: return MangasPage(emptyList(), false)

        val start = (page - 1) * PAGE_SIZE
        if (start >= allMangas.size) return MangasPage(emptyList(), false)
        val slice = allMangas.subList(start, minOf(start + PAGE_SIZE, allMangas.size))
        return MangasPage(slice, start + PAGE_SIZE < allMangas.size)
    }

    // ── Popular ───────────────────────────────────────────────
    override fun popularMangaRequest(page: Int): Request =
        GET(nextDataUrl("list"), headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseListResponse(response, 1)

    // ── Latest ────────────────────────────────────────────────
    override fun latestUpdatesRequest(page: Int): Request =
        GET(nextDataUrl("list"), headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseListResponse(response, 1)

    // ── Search ────────────────────────────────────────────────
    // Query dipass lewat custom header karena searchMangaParse
    // tidak punya parameter query
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET(
            nextDataUrl("list"),
            headers.newBuilder().add("X-Search-Query", query).build(),
        )

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.header("X-Search-Query") ?: ""
        val pageProps = json.parseToJsonElement(response.body.string())
            .jsonObject["pageProps"]?.jsonObject
            ?: return MangasPage(emptyList(), false)

        val allMangas = pageProps["data"]?.let { data ->
            when {
                data is kotlinx.serialization.json.JsonArray ->
                    data.mapNotNull { runCatching { itemToManga(it.jsonObject) }.getOrNull() }
                data is JsonObject && data.containsKey("data") ->
                    data["data"]?.jsonArray
                        ?.mapNotNull { runCatching { itemToManga(it.jsonObject) }.getOrNull() }
                else -> null
            }
        } ?: return MangasPage(emptyList(), false)

        val filtered = allMangas.filter {
            it.title.contains(query, ignoreCase = true)
        }
        return MangasPage(filtered, false)
    }

    // ── Manga Detail ──────────────────────────────────────────
    // manga.url = "/komik/{slug}"
    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(nextDataUrl("komik/${manga.url.removePrefix("/komik/")}"), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val item = json.parseToJsonElement(response.body.string())
            .jsonObject["pageProps"]?.jsonObject
            ?.get("item")?.jsonObject
            ?: return SManga.create()
        return itemToManga(item)
    }

    // ── Chapter List ──────────────────────────────────────────
    // Data chapter ada di dalam item.Komik — reuse mangaDetailsRequest
    override fun chapterListRequest(manga: SManga): Request =
        mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val item = json.parseToJsonElement(response.body.string())
            .jsonObject["pageProps"]?.jsonObject
            ?.get("item")?.jsonObject
            ?: return emptyList()

        val slug = item["slug"]?.jsonPrimitive?.content ?: return emptyList()
        val komik = item["Komik"]?.jsonObject ?: return emptyList()

        return komik.entries
            .sortedByDescending { it.key.toDoubleOrNull() ?: 0.0 }
            .mapNotNull { (chId, chData) ->
                val chObj = chData.jsonObject
                val hasImages = chObj["img"]?.jsonArray
                    ?.any { it.jsonPrimitive.content.isNotEmpty() } ?: false

                SChapter.create().apply {
                    url = "/chapter/$slug-chapter-$chId"
                    name = "Chapter $chId"
                    date_upload = runCatching {
                        chObj["UpdateAt"]?.jsonPrimitive?.content
                            ?.let { dateFormat.parse(it)?.time } ?: 0L
                    }.getOrDefault(0L)
                    scanlator = if (!hasImages) "⚠ Belum ada gambar" else null
                }
            }
    }

    // ── Page List ─────────────────────────────────────────────
    // chapter.url = "/chapter/{slug}-chapter-{chId}"
    override fun pageListRequest(chapter: SChapter): Request =
        GET(nextDataUrl("chapter/${chapter.url.removePrefix("/chapter/")}"), headers)

    override fun pageListParse(response: Response): List<Page> {
        val data = json.parseToJsonElement(response.body.string())
            .jsonObject["pageProps"]?.jsonObject
            ?.get("data")?.jsonObject
            ?: return emptyList()

        val imgArray = data["img"]?.jsonArray ?: return emptyList()

        return imgArray.mapIndexedNotNull { i, v ->
            val url = v.jsonPrimitive.content
            if (url.isNotEmpty()) Page(i, imageUrl = url) else null
        }
    }

    // ── Image ─────────────────────────────────────────────────
    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request =
        GET(
            page.imageUrl!!,
            headers.newBuilder().add("Referer", "$baseUrl/").build(),
        )

    companion object {
        private const val PAGE_SIZE = 50
    }
}
