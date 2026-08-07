package eu.kanade.tachiyomi.extension.id.komikavbeta

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

@Source
abstract class KomikavBeta : HttpSource() {

    override val supportsLatest = true

    override fun popularMangaRequest(page: Int): Request = Request.Builder().url("$baseUrl/popular/?page=$page").build()

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        val mangas = doc.select("div.flex.overflow-hidden.rounded-md.bg-white").map { parseMangaItem(it) }
        return MangasPage(mangas, doc.select("a.next").isNotEmpty())
    }

    override fun latestUpdatesRequest(page: Int): Request = Request.Builder().url("$baseUrl/?page=$page").build()

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("page", page.toString())
            .build()
        return Request.Builder().url(url).build()
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        return SManga.create().apply {
            title = doc.select("h1.text-2xl.font-bold").first()?.text() ?: doc.title()
            thumbnail_url = doc.select("div.aspect-\\[5\\/7\\] img").first()?.attr("src") ?: ""
            description = doc.select("div.prose p").text()
            status = when {
                doc.text().lowercase().contains("ongoing") -> SManga.ONGOING
                doc.text().lowercase().contains("completed") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            genre = doc.select("div.genre a").joinToString { it.text() }
            initialized = true
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        return doc.select("div.chapter-list a, div.list-chapter a, div.grid.gap-2 a")
            .mapNotNull { el ->
                val link = el.absUrl("href")
                val name = el.text()
                if (link.isBlank() || name.isBlank()) return@mapNotNull null
                SChapter.create().apply {
                    url = link
                    this.name = name
                    date_upload = parseDate(el.select("span.float-right").text())
                }
            }.reversed()
    }

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        return doc.select("div.chapter-content img, div.reader-area img")
            .mapIndexed { index, img ->
                Page(index, imageUrl = img.attr("src").ifEmpty { img.attr("data-src") })
            }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun parseMangaItem(element: Element): SManga = SManga.create().apply {
        url = element.select("a").first()?.absUrl("href") ?: ""
        title = element.select("h2").first()?.text() ?: ""
        thumbnail_url = element.select("img").first()?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: ""
        genre = element.select("div.z-100.absolute.left-0.top-0").text().takeIf { it.isNotEmpty() }
        initialized = true
    }

    private val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)

    private fun parseDate(text: String): Long = when {
        text.contains("jam lalu") -> System.currentTimeMillis() - 3_600_000L
        text.contains("hari lalu") -> {
            val n = text.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
            System.currentTimeMillis() - n * 86_400_000L
        }
        text.contains("mgg lalu") -> {
            val n = text.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
            System.currentTimeMillis() - n * 7 * 86_400_000L
        }
        text.contains("bln lalu") -> {
            val n = text.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
            System.currentTimeMillis() - n * 30 * 86_400_000L
        }
        else -> 0L
    }
}
