package eu.kanade.tachiyomi.extension.id.mgkomikwebbeta

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class MGKomikWebBeta : HttpSource() {

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Android 13; Mobile; rv:109.0) Gecko/109.0 Firefox/109.0")
        .add("Referer", "$baseUrl/")

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(4, 1)
        .build()

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/komik/?order_by=trending&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body!!.string())
        val mangas = document.select("div.manga-card").map { parseMangaFromElement(it) }
        val hasNext = document.selectFirst("a.page-link:contains(Next)") != null
        return MangasPage(mangas, hasNext)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/komik/?order_by=latest&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()

        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body!!.string())
        val mangas = document.select("a.manga-card").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                thumbnail_url = element.selectFirst("img.manga-cover")?.attr("src")
                title = element.selectFirst("img.manga-cover")?.attr("alt")?.trim().orEmpty()
            }
        }
        val hasNext = document.selectFirst("a.page-link:contains(Next)") != null
        return MangasPage(mangas, hasNext)
    }

    override fun getFilterList(): FilterList = FilterList()

    // ============================== Details ===============================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body!!.string())
        return parseMangaDetails(document)
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body!!.string())
        return parseChapterList(document)
    }

    // ============================== Pages =================================

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = Jsoup.parse(response.body!!.string())
        return document.select("img[data-page]").mapIndexed { index, img ->
            Page(
                index = index,
                imageUrl = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src"),
            )
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Helpers ===============================

    private fun parseMangaFromElement(element: Element): SManga {
        val anchor = element.selectFirst("a[href]")!!
        return SManga.create().apply {
            setUrlWithoutDomain(anchor.attr("href"))
            thumbnail_url = element.selectFirst("img.manga-cover")?.attr("src")
            title = element.selectFirst("img.manga-cover")?.attr("alt")?.trim().orEmpty()
        }
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.manga-title")?.text().orEmpty()
        thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
        description = document.selectFirst("div.manga-description")?.text()

        val statusBadge = document.selectFirst("div.meta-item.status-badge")?.text().orEmpty()
        status = when {
            statusBadge.contains("ongoing", true) -> SManga.ONGOING
            statusBadge.contains("completed", true) -> SManga.COMPLETED
            statusBadge.contains("hiatus", true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        val metaItems = document.select("div.meta-item:not(.status-badge)").map { it.text().trim() }

        author = metaItems.firstOrNull { it.contains("Author", true) }
            ?.substringAfter(":")?.trim()

        val typeKeywords = setOf("manga", "manhwa", "manhua", "webtoon")
        val types = metaItems.filter { it.lowercase() in typeKeywords }

        val genresList = document.select("div.genre-list a.genre-tag")
            .map { it.text().trim() }
            .filterNot { it.lowercase() in typeKeywords }

        genre = (genresList + types).joinToString(", ")
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select("li.chapter-list-item").map { element ->
        val anchor = element.selectFirst("a.chapter-link")!!
        SChapter.create().apply {
            setUrlWithoutDomain(anchor.attr("href"))
            name = element.selectFirst("span.chapter-number")?.text().orEmpty()
            date_upload = parseDate(element.selectFirst("span.chapter-date")?.text().orEmpty())
        }
    }

    private fun parseDate(text: String): Long {
        if (text.isBlank()) return 0L

        relativeDateRegex.find(text)?.let { match ->
            val (amountStr, unit) = match.destructured
            val amount = amountStr.toLongOrNull() ?: return@let

            val multiplier = when (unit.lowercase()) {
                "second" -> 1_000L
                "minute" -> 60_000L
                "hour" -> 3_600_000L
                "day" -> 86_400_000L
                "week" -> 604_800_000L
                "month" -> 2_592_000_000L
                "year" -> 31_536_000_000L
                else -> 0L
            }
            return System.currentTimeMillis() - (amount * multiplier)
        }

        val dateFormats = arrayOf(
            SimpleDateFormat("dd MMM yyyy", Locale("id")),
        )

        return dateFormats.firstNotNullOfOrNull { formatter ->
            runCatching { formatter.parse(text)?.time }.getOrNull()?.takeIf { t -> t > 0L }
        } ?: 0L
    }

    companion object {
        private val relativeDateRegex = Regex("""(\d+)\s*(\w+)s?""", RegexOption.IGNORE_CASE)
    }
}
