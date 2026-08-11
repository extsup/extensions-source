package eu.kanade.tachiyomi.extension.id.hwago

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

@Source
abstract class Hwago : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2)
    }

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get("$baseUrl/browse?sort=popular&page=$page", headers)
        return parseMangaList(response.body.string())
    }

    // ============================== Latest ==============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.get("$baseUrl/browse?sort=latest&page=$page", headers)
        return parseMangaList(response.body.string())
    }

    // ============================== Search ==============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val response = client.get("$baseUrl/api/search?q=${query.trim()}", headers)
            val dto = response.parseAs<SearchResponseDto>()
            return MangasPage(dto.results.map { it.toSManga(baseUrl) }, false)
        }

        val url = "$baseUrl/browse".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            filters.filterIsInstance<UriFilter>().forEach { it.addToUri(this) }
        }.build()

        val response = client.get(url, headers)
        return parseMangaList(response.body.string())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (!path.startsWith("/comic/")) return null
        return SManga.create().apply {
            this.url = "/comic/${path.removePrefix("/comic/").trimEnd('/') }"
        }
    }

    // ============================== Details + Chapters ==============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get("$baseUrl${manga.url}", headers)
        val doc = Jsoup.parse(response.body.string())

        val updatedManga = if (fetchDetails) {
            SManga.create().apply {
                url = manga.url
                title = doc.selectFirst("h1")?.text()?.trim() ?: ""
                thumbnail_url = doc.selectFirst("img[src*='imgsvr.my.id'][src*='/cover_'][width]")?.absUrl("src")
                description = doc.selectFirst("[data-sr]")?.attr("data-sr")?.let {
                    runCatching { String(java.util.Base64.getDecoder().decode(it)) }.getOrNull()
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
                author = doc.select("span.text-xs:contains(Author) ~ span").firstOrNull()?.text()?.trim()
                artist = doc.select("span.text-xs:contains(Artist) ~ span").firstOrNull()?.text()?.trim()
                val type = doc.selectFirst("span.uppercase.text-primary-400")?.text()?.trim()
                genre = buildList {
                    if (!type.isNullOrBlank()) add(type)
                    addAll(doc.select("a[href*='genre=']").map { it.text().trim() })
                }.joinToString()
            }
        } else {
            manga
        }

        val chapterList = if (fetchChapters) {
            doc.select("a[data-chapter]").map { el ->
                SChapter.create().apply {
                    url = el.attr("href")
                    val num = el.attr("data-chapter").trim().toFloatOrNull() ?: 0f
                    name = "Chapter ${if (num == num.toLong().toFloat()) num.toLong() else num}"
                    date_upload = el.selectFirst("span.tabular-nums")?.text()?.trim()
                        ?.let { parseChapterDate(it) } ?: 0L
                }
            }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, chapterList)
    }

    // ============================== Pages ==============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get("$baseUrl${chapter.url}", headers)
        val doc = Jsoup.parse(response.body.string())
        return doc.select("img[src*='hwg.imgsvr.my.id'][src*='/chapter-']")
            .mapIndexed { i, el -> Page(i, imageUrl = el.absUrl("src")) }
    }

    // ============================== Filters ==============================

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        MinChaptersFilter(),
        GenreFilter(),
    )

    // ============================== Utils ==============================

    private fun parseMangaList(html: String): MangasPage {
        val doc = Jsoup.parse(html)
        val mangas = doc.select("a[href^='/comic/']:has(img)").map { el ->
            SManga.create().apply {
                url = el.attr("href")
                title = el.selectFirst("img")?.attr("alt")?.trim() ?: ""
                thumbnail_url = el.selectFirst("img")?.absUrl("src")
            }
        }.distinctBy { it.url }
        val hasNextPage = doc.selectFirst("a[aria-label='Selanjutnya']") != null
        return MangasPage(mangas, hasNextPage)
    }

    private val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.ROOT)

    private fun parseChapterDate(text: String): Long {
        val t = text.trim().lowercase()
        val now = System.currentTimeMillis()
        if (t == "baru saja" || t == "baru" || t == "just now") return now
        val parts = t.split(" ")
        if (parts.size >= 2) {
            val num = parts[0].toLongOrNull()
            if (num != null) {
                return when {
                    parts[1].startsWith("detik") -> now - num * 1_000
                    parts[1].startsWith("menit") -> now - num * 60_000
                    parts[1].startsWith("jam")   -> now - num * 3_600_000
                    parts[1].startsWith("hari")  -> now - num * 86_400_000
                    parts[1].startsWith("minggu")-> now - num * 604_800_000
                    parts[1].startsWith("bulan") -> now - num * 2_592_000_000
                    parts[1].startsWith("tahun") -> now - num * 31_536_000_000
                    else -> 0L
                }
            }
        }
        return dateFormat.tryParse(t)
    }
}
