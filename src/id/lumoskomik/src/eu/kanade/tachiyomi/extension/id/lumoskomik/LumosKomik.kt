package eu.kanade.tachiyomi.extension.id.lumoskomik

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

@Source
abstract class LumosKomik : KeiSource() {

    override fun getMangaUrl(manga: SManga) = "$baseUrl/comic/${manga.url}"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/read/${chapter.url}"

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get("$baseUrl/api/popular?tab=komik&period=all&perPage=24")
        val dto = response.parseAs<PopularDto>()
        return MangasPage(dto.data.map { it.toSManga(baseUrl) }, hasNextPage = false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.get("$baseUrl/api/popular?tab=komik&period=weekly&perPage=24")
        val dto = response.parseAs<PopularDto>()
        return MangasPage(dto.data.map { it.toSManga(baseUrl) }, hasNextPage = false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val response = client.get("$baseUrl/api/search?q=${query.trim()}")
        val dto = response.parseAs<SearchDto>()
        return MangasPage(dto.results.map { it.toSManga(baseUrl) }, hasNextPage = false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.pathSegments.lastOrNull { it.isNotEmpty() } ?: return null
        val response = client.get("$baseUrl/comic/$slug")
        return parseMangaDetail(Jsoup.parse(response.body.string()), slug)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get("$baseUrl/comic/${manga.url}")
        val doc = Jsoup.parse(response.body.string())
        return SMangaUpdate(
            manga = if (fetchDetails) parseMangaDetail(doc, manga.url) else null,
            chapters = if (fetchChapters) parseChapterList(doc) else null,
        )
    }

    private fun parseMangaDetail(doc: Document, slug: String): SManga {
        val ldJson = doc.select("script[type='application/ld+json']")
            .map { it.data() }
            .firstOrNull { it.contains("ComicSeries") }
            ?.parseAs<List<LdJsonDto>>()
            ?.firstOrNull { it.type == "ComicSeries" }

        val genres = ldJson?.genre
            ?.map { it.lowercase().replaceFirstChar(Char::uppercaseChar) }
            ?.joinToString(", ")

        val altName = ldJson?.alternateName?.takeIf { it != ldJson.name }

        return SManga.create().apply {
            url = slug
            title = ldJson?.name ?: doc.title().substringBefore(" | ")
            thumbnail_url = ldJson?.image
            description = buildString {
                altName?.let { append("Alt title: $it\n\n") }
                ldJson?.description?.let { append(it) }
            }.ifEmpty { null }
            author = ldJson?.author?.name
            artist = ldJson?.illustrator?.name
            genre = genres
            status = when (
                doc.select("span.text-green-400, span.text-red-400")
                    .firstOrNull()?.text()?.lowercase()
            ) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    private fun parseChapterList(doc: Document): List<SChapter> = doc.select("a[id^=chapter-][data-chapter]").map { el ->
        val chapterNum = el.attr("data-chapter").trim()
        val href = el.attr("href") // e.g. "/read/comic-slug/chapter-116"
        val dateText = el.select("span.tabular-nums").text().trim()
        SChapter.create().apply {
            url = href.removePrefix("/read/")
            name = "Chapter ${chapterNum.trimEnd('0').trimEnd('.')}"
            chapter_number = chapterNum.toFloatOrNull() ?: -1f
            date_upload = parseRelativeDate(dateText)
        }
    }

    private fun parseRelativeDate(text: String): Long {
        val now = System.currentTimeMillis()
        val parts = text.trim().split(" ")
        if (parts.size < 3) return 0L
        val amount = parts[0].toLongOrNull() ?: return 0L
        return when (parts[1]) {
            "detik" -> now - TimeUnit.SECONDS.toMillis(amount)
            "menit" -> now - TimeUnit.MINUTES.toMillis(amount)
            "jam" -> now - TimeUnit.HOURS.toMillis(amount)
            "hari" -> now - TimeUnit.DAYS.toMillis(amount)
            "minggu" -> now - TimeUnit.DAYS.toMillis(amount * 7)
            "bulan" -> now - TimeUnit.DAYS.toMillis(amount * 30)
            "tahun" -> now - TimeUnit.DAYS.toMillis(amount * 365)
            else -> 0L
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get("$baseUrl/read/${chapter.url}")
        val doc = Jsoup.parse(response.body.string())

        val pages = doc.select("#reader-pages img[src]")
            .mapIndexed { i, el -> Page(i, imageUrl = el.absUrl("src")) }

        if (pages.isNotEmpty()) return pages

        val parts = chapter.url.split("/")
        val comicSlug = parts.dropLast(1).joinToString("/")
        val chapterSlug = parts.last()
        val prevNum = chapterSlug.removePrefix("chapter-").toIntOrNull()?.minus(1)
        if (prevNum != null && prevNum >= 1) {
            val dto = client.get(
                "$baseUrl/api/chapters/chapter-$prevNum/next-pages?comic=$comicSlug",
            ).parseAs<NextPagesDto>()
            return dto.pages.mapIndexed { i, url -> Page(i, imageUrl = url) }
        }

        return emptyList()
    }
}
