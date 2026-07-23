package eu.kanade.tachiyomi.extension.id.mgkomikweb

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import keiyoushi.annotation.Source
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class MGKomikWeb : ParsedHttpSource() {

    override val supportsLatest = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Android 13; Mobile; rv:109.0) Gecko/109.0 Firefox/109.0")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ==================== POPULAR ====================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/komik/?order_by=trending&page=$page", headers)

    override fun popularMangaSelector() = "div.manga-card"

    override fun popularMangaFromElement(element: Element): SManga {
        val anchor = element.selectFirst("a[href]")!!
        return SManga.create().apply {
            setUrlWithoutDomain(anchor.attr("href"))
            thumbnail_url = element.selectFirst("img.manga-cover")?.attr("src")
            title = element.selectFirst("img.manga-cover")?.attr("alt")?.trim() ?: ""
        }
    }

    override fun popularMangaNextPageSelector() = "a.page-link:contains(Next)"

    // ==================== LATEST ====================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/komik/?order_by=latest&page=$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ==================== SEARCH ====================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaSelector() = "a.manga-card"

    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img.manga-cover")?.attr("src")
        title = element.selectFirst("img.manga-cover")?.attr("alt")?.trim() ?: ""
    }

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ==================== MANGA DETAIL ====================

    override fun mangaDetailsParse(document: Document): SManga {
        val typeKeywords = setOf("manga", "manhwa", "manhua", "webtoon")

        val metaItems = document.select("div.meta-item:not(.status-badge)")
            .map { it.text().trim() }

        val types = metaItems.filter { it.lowercase() in typeKeywords }

        val authorText = metaItems
            .firstOrNull { it.startsWith("Author:", ignoreCase = true) }
            ?.removePrefix("Author:")
            ?.trim()
            .takeIf { it?.isNotBlank() == true }

        val genres = document.select("div.genre-list a.genre-tag")
            .map { it.text().trim() }
            .filter { it.lowercase() !in typeKeywords }

        val genreString = (genres + types).joinToString(", ")

        val statusText = document.selectFirst("div.meta-item.status-badge")
            ?.text()?.trim() ?: ""
        val status = when {
            statusText.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("completed", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        return SManga.create().apply {
            title = document.selectFirst("h1.manga-title")?.text()?.trim() ?: ""
            thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            description = document.selectFirst("div.manga-description")?.text()?.trim()
            genre = genreString
            author = authorText
            this.status = status
        }
    }

    // ==================== CHAPTER LIST ====================

    override fun chapterListSelector() = "li.chapter-list-item"

    override fun chapterFromElement(element: Element): SChapter {
        val anchor = element.selectFirst("a.chapter-link")!!
        return SChapter.create().apply {
            setUrlWithoutDomain(anchor.attr("href"))
            name = element.selectFirst("span.chapter-number")?.text()?.trim() ?: ""
            date_upload = parseDate(
                element.selectFirst("span.chapter-date")?.text()?.trim() ?: "",
            )
        }
    }

    private fun parseDate(text: String): Long {
        if (text.isBlank()) return 0L

        val now = System.currentTimeMillis()

        Regex("""(\d+)\s*detik""").find(text)?.let { return now - it.groupValues[1].toLong() * 1_000 }
        Regex("""(\d+)\s*menit""").find(text)?.let { return now - it.groupValues[1].toLong() * 60_000 }
        Regex("""(\d+)\s*jam""").find(text)?.let { return now - it.groupValues[1].toLong() * 3_600_000 }
        Regex("""(\d+)\s*hari""").find(text)?.let { return now - it.groupValues[1].toLong() * 86_400_000 }
        Regex("""(\d+)\s*minggu""").find(text)?.let { return now - it.groupValues[1].toLong() * 7 * 86_400_000 }
        Regex("""(\d+)\s*bulan""").find(text)?.let { return now - it.groupValues[1].toLong() * 30 * 86_400_000 }
        Regex("""(\d+)\s*tahun""").find(text)?.let { return now - it.groupValues[1].toLong() * 365 * 86_400_000 }

        Regex("""(\d+)\s*second""").find(text)?.let { return now - it.groupValues[1].toLong() * 1_000 }
        Regex("""(\d+)\s*minute""").find(text)?.let { return now - it.groupValues[1].toLong() * 60_000 }
        Regex("""(\d+)\s*hour""").find(text)?.let { return now - it.groupValues[1].toLong() * 3_600_000 }
        Regex("""(\d+)\s*day""").find(text)?.let { return now - it.groupValues[1].toLong() * 86_400_000 }
        Regex("""(\d+)\s*week""").find(text)?.let { return now - it.groupValues[1].toLong() * 7 * 86_400_000 }
        Regex("""(\d+)\s*month""").find(text)?.let { return now - it.groupValues[1].toLong() * 30 * 86_400_000 }
        Regex("""(\d+)\s*year""").find(text)?.let { return now - it.groupValues[1].toLong() * 365 * 86_400_000 }

        return listOf(
            SimpleDateFormat("dd MMM yyyy", Locale("id")),
            SimpleDateFormat("dd MMM yy", Locale("id")),
            SimpleDateFormat("dd MMMM yyyy", Locale("id")),
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT),
            SimpleDateFormat("dd/MM/yyyy", Locale.ROOT),
        ).firstNotNullOfOrNull {
            runCatching { it.parse(text)?.time }.getOrNull()?.takeIf { t -> t > 0L }
        } ?: 0L
    }

    // ==================== PAGE LIST ====================

    override fun pageListParse(document: Document): List<Page> {
        return document.select("img[data-page]").mapIndexed { index, img ->
            Page(
                index = index,
                imageUrl = img.attr("src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-src"),
            )
        }
    }

    override fun imageUrlParse(document: Document) = ""

    // ==================== IMAGE REQUEST ====================

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headersBuilder()
            .set("Referer", baseUrl)
            .build()
        return GET(page.imageUrl!!, imgHeaders)
    }
}
