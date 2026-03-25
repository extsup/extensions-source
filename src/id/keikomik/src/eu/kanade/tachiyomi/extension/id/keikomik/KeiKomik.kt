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
import org.jsoup.Jsoup
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

    // ── buildId dari __NEXT_DATA__ ────────────────────────────
    private fun buildIdFrom(html: String): String {
        val match = Regex(""""buildId"\s*:\s*"([^"]+)"""").find(html)
            ?: throw Exception("buildId tidak ditemukan")
        return match.groupValues[1]
    }

    private fun nextDataUrl(buildId: String, path: String) =
        "$baseUrl/_next/data/$buildId/$path.json"

    // ── Helper: parse SManga dari card HTML ───────────────────
    private fun cardToManga(el: org.jsoup.nodes.Element): SManga? {
        val a = el.selectFirst("a[href*=/komik/]") ?: return null
        val href = a.attr("href") // /komik/{slug}
        if (href.isBlank()) return null
        return SManga.create().apply {
            url = href
            title = a.attr("title")
                .ifBlank { el.selectFirst("h2, h3, .title, [class*=title]")?.text().orEmpty() }
                .ifBlank { href.substringAfterLast("/") }
            thumbnail_url = (el.selectFirst("img[src]")?.attr("abs:src")
                ?: el.selectFirst("img[data-src]")?.attr("abs:data-src"))
                ?.ifBlank { null }
        }
    }

    // ── Popular — scrape HTML homepage ────────────────────────
    override fun popularMangaRequest(page: Int): Request =
        GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        // ambil semua card yang punya link /komik/
        val cards = doc.select("a[href*=/komik/]")
            .map { it.closest("div, li, article") ?: it.parent() ?: it }
            .distinctBy { it.selectFirst("a[href*=/komik/]")?.attr("href") }

        val mangas = cards.mapNotNull { runCatching { cardToManga(it) }.getOrNull() }
            .filter { it.title.isNotBlank() }
            .distinctBy { it.url }

        return MangasPage(mangas, false)
    }

    // ── Latest — sama dengan popular (homepage sorted by update)
    override fun latestUpdatesRequest(page: Int): Request =
        GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        popularMangaParse(response)

    // ── Search ────────────────────────────────────────────────
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/?s=${query.replace(" ", "+")}", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        val mangas = doc.select("a[href*=/komik/]")
            .map { it.closest("div, li, article") ?: it.parent() ?: it }
            .distinctBy { it.selectFirst("a[href*=/komik/]")?.attr("href") }
            .mapNotNull { runCatching { cardToManga(it) }.getOrNull() }
            .filter { it.title.isNotBlank() }
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    // ── Manga Detail ──────────────────────────────────────────
    // manga.url = "/komik/{slug}"
    // Fetch halaman HTML dulu untuk ambil buildId, lalu fetch _next/data
    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val html = response.body.string()
        val buildId = buildIdFrom(html)
        val slug = response.request.url.pathSegments.last()

        val dataResp = client.newCall(
            GET(nextDataUrl(buildId, "komik/$slug"), headers),
        ).execute()

        val item = json.parseToJsonElement(dataResp.body.string())
            .jsonObject["pageProps"]?.jsonObject
            ?.get("item")?.jsonObject ?: return SManga.create()

        return itemToManga(item)
    }

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
                item["rate"]?.jsonPrimitive?.content?.let { appendLine("⭐ $it") }
                val type = str("type")
                if (type.isNotEmpty()) appendLine("Type: $type")
                val rilis = str("rilis")
                if (rilis.isNotEmpty()) appendLine("Rilis: $rilis")
            }.trim()
        }
    }

    // ── Chapter List + Pages — semua dari 1 request ───────────
    // komikList = [{id, img[], UpdateAt, ...}] — tiap item = 1 chapter + gambarnya
    // Simpan img per chapter di scanlator field (workaround) — tidak, lebih baik
    // simpan di url sebagai slug+chId saja, fetch ulang saat pageList

    override fun chapterListRequest(manga: SManga): Request =
        GET("$baseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body.string()
        val buildId = buildIdFrom(html)
        val slug = response.request.url.pathSegments.last()

        val dataResp = client.newCall(
            GET(nextDataUrl(buildId, "komik/$slug"), headers),
        ).execute()

        val pageProps = json.parseToJsonElement(dataResp.body.string())
            .jsonObject["pageProps"]?.jsonObject ?: return emptyList()

        val komikList = pageProps["komikList"]?.jsonArray ?: return emptyList()

        return komikList
            .sortedByDescending {
                it.jsonObject["id"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            }
            .map { ch ->
                val chObj = ch.jsonObject
                val chId = chObj["id"]?.jsonPrimitive?.content ?: ""
                SChapter.create().apply {
                    // url = "/komik/{slug}/{chId}" — encode slug+chId
                    url = "/komik/$slug/$chId"
                    name = "Chapter $chId"
                    date_upload = runCatching {
                        chObj["UpdateAt"]?.jsonPrimitive?.content
                            ?.let { dateFormat.parse(it)?.time } ?: 0L
                    }.getOrDefault(0L)
                }
            }
    }

    // ── Page List ─────────────────────────────────────────────
    // chapter.url = "/komik/{slug}/{chId}"
    // chId di-inject via X-Chapter-Id header supaya bisa dibaca di pageListParse
    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.removePrefix("/komik/").split("/")
        val slug = parts[0]
        val chId = parts.getOrElse(1) { "" }
        return GET(
            "$baseUrl/komik/$slug",
            headers.newBuilder().add("X-Chapter-Id", chId).build(),
        )
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val buildId = buildIdFrom(html)
        val slug = response.request.url.pathSegments.last()

        // ambil chId dari request URL — tidak bisa dari response
        // simpan di custom header
        val chId = response.request.header("X-Chapter-Id") ?: return emptyList()

        val dataResp = client.newCall(
            GET(nextDataUrl(buildId, "komik/$slug"), headers),
        ).execute()

        val komikList = json.parseToJsonElement(dataResp.body.string())
            .jsonObject["pageProps"]?.jsonObject
            ?.get("komikList")?.jsonArray ?: return emptyList()

        val chObj = komikList.firstOrNull {
            it.jsonObject["id"]?.jsonPrimitive?.content == chId
        }?.jsonObject ?: return emptyList()

        return chObj["img"]?.jsonArray?.mapIndexedNotNull { i, v ->
            val url = v.jsonPrimitive.content
            if (url.isNotEmpty()) Page(i, imageUrl = url) else null
        } ?: emptyList()
    }



    // ── Image ─────────────────────────────────────────────────
    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request =
        GET(
            page.imageUrl!!,
            headers.newBuilder().add("Referer", "$baseUrl/").build(),
        )
}
