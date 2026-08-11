package eu.kanade.tachiyomi.extension.id.hwago

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

@Source
abstract class Hwago : HttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(2)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/browse?sort=popular&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        val mangas = doc.select("a[href^='/comic/']:has(img)").map { el ->
            SManga.create().apply {
                url = el.attr("href")
                title = el.selectFirst("img")?.attr("alt")?.trim() ?: ""
                thumbnail_url = el.selectFirst("img")?.absUrl("src")
            }
        }.distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/browse?sort=latest&page=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ============================== Search ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/api/search?q=${query.trim()}", headers)
        }

        val url = "$baseUrl/browse".toHttpUrl().newBuilder()
        url.addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> url.addQueryParameter("sort", filter.selectedValue())
                is StatusFilter -> filter.selectedValue().takeIf { it.isNotEmpty() }
                    ?.let { url.addQueryParameter("status", it) }
                is TypeFilter -> filter.selectedValue().takeIf { it.isNotEmpty() }
                    ?.let { url.addQueryParameter("type", it) }
                is MinChaptersFilter -> filter.selectedValue().takeIf { it.isNotEmpty() }
                    ?.let { url.addQueryParameter("minChapters", it) }
                is GenreFilter -> {
                    val selected = filter.state.filter { it.state }.map { it.name }
                    if (selected.isNotEmpty()) {
                        url.addQueryParameter("genre", selected.joinToString(","))
                    }
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url.toString()
        return if (url.contains("/api/search")) {
            val dto = response.parseAs<SearchResponseDto>()
            MangasPage(dto.results.map { it.toSManga(baseUrl) }, false)
        } else {
            popularMangaParse(response)
        }
    }

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga) =
        GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())
        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim() ?: ""
            thumbnail_url = doc.selectFirst("img[src*='imgsvr.my.id'][src*='/cover_']")?.absUrl("src")
            description = doc.selectFirst("[data-sr]")?.attr("data-sr")?.let {
                runCatching {
                    String(java.util.Base64.getDecoder().decode(it))
                }.getOrNull()
            }
            status = when (
                doc.selectFirst("span.text-green-400, span.text-red-400, span.text-yellow-400")
                    ?.text()?.lowercase()?.trim()
            ) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            genre = doc.select("a[href^='/browse?genre=']")
                .joinToString { it.text().trim() }
            author = doc.selectFirst(".j87549d span:last-child")?.text()?.trim()
            artist = doc.selectFirst(".ja5cc span:last-child")?.text()?.trim()
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga) =
        GET("$baseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())
        return doc.select("a[data-chapter]").map { el ->
            SChapter.create().apply {
                url = el.attr("href")
                val num = el.attr("data-chapter").trim().toFloatOrNull() ?: 0f
                name = "Chapter ${if (num == num.toLong().toFloat()) num.toLong() else num}"
                date_upload = el.selectFirst("span.tabular-nums")?.text()?.trim()
                    ?.let { parseRelativeDate(it) } ?: 0L
            }
        }
    }

    // ============================== Pages ==============================

    override fun pageListRequest(chapter: SChapter) =
        GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(response.body.string())
        return doc.select("img[src*='hwg.imgsvr.my.id'][src*='/chapter-']")
            .mapIndexed { i, el -> Page(i, imageUrl = el.absUrl("src")) }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList(
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        MinChaptersFilter(),
        GenreFilter(),
    )

    private fun parseRelativeDate(text: String): Long {
        val now = System.currentTimeMillis()
        val parts = text.trim().split(" ")
        if (parts.size < 2) return 0L
        val num = parts[0].toLongOrNull() ?: return 0L
        return when {
            parts[1].contains("detik") -> now - num * 1000
            parts[1].contains("menit") -> now - num * 60 * 1000
            parts[1].contains("jam") -> now - num * 60 * 60 * 1000
            parts[1].contains("hari") -> now - num * 24 * 60 * 60 * 1000
            parts[1].contains("minggu") -> now - num * 7 * 24 * 60 * 60 * 1000
            parts[1].contains("bulan") -> now - num * 30 * 24 * 60 * 60 * 1000
            parts[1].contains("tahun") -> now - num * 365 * 24 * 60 * 60 * 1000
            else -> 0L
        }
    }
}
