package eu.kanade.tachiyomi.extension.id.komikcast

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar
import java.util.Locale

class KomikCast : MangaThemesia("Komik Cast", "https://komikcast03.com", "id", "/daftar-komik") {

    // Formerly "Komik Cast (WP Manga Stream)"
    override val id = 972717448578983812

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
        .add("Accept-language", "en-US,en;q=0.9,id;q=0.8")

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    override fun popularMangaRequest(page: Int) = customPageRequest(page, "orderby", "popular")
    override fun latestUpdatesRequest(page: Int) = customPageRequest(page, "sortby", "update")

    private fun customPageRequest(page: Int, filterKey: String, filterValue: String): Request {
        val pagePath = if (page > 1) "page/$page/" else ""

        return GET("$baseUrl$mangaUrlDirectory/$pagePath?$filterKey=$filterValue", headers)
    }

    override fun searchMangaSelector() = "div.list-update_item"

    override fun searchMangaFromElement(element: Element) = super.searchMangaFromElement(element).apply {
        title = element.selectFirst("h3.title")!!.ownText()
    }

    override val seriesDetailsSelector = "div.komik_info:has(.komik_info-content)"
    override val seriesTitleSelector = "h1.komik_info-content-body-title"
    override val seriesDescriptionSelector = ".komik_info-description-sinopsis"
    override val seriesAltNameSelector = ".komik_info-content-native"
    override val seriesGenreSelector = ".komik_info-content-genre a"
    override val seriesThumbnailSelector = ".komik_info-content-thumbnail img"
    override val seriesStatusSelector = ".komik_info-content-info:contains(Status)"

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        document.selectFirst(seriesDetailsSelector)?.let { seriesDetails ->
            title = seriesDetails.selectFirst(seriesTitleSelector)?.text()
                ?.replace("bahasa indonesia", "", ignoreCase = true)?.trim().orEmpty()
            artist = seriesDetails.selectFirst(seriesArtistSelector)?.ownText().removeEmptyPlaceholder()
            author = seriesDetails.selectFirst(seriesAuthorSelector)?.ownText().removeEmptyPlaceholder()
            description = seriesDetails.select(seriesDescriptionSelector).joinToString("\n") { it.text() }.trim()
            // Add alternative name to manga description
            val altName = seriesDetails.selectFirst(seriesAltNameSelector)?.ownText().takeIf { it.isNullOrBlank().not() }
            altName?.let {
                description = "$description\n\n$altNamePrefix$altName".trim()
            }
            val genres = seriesDetails.select(seriesGenreSelector).map { it.text() }.toMutableList()
            // Add series type (manga/manhwa/manhua/other) to genre
            seriesDetails.selectFirst(seriesTypeSelector)?.ownText().takeIf { it.isNullOrBlank().not() }?.let { genres.add(it) }
            genre = genres.map { genre ->
                genre.lowercase(Locale.forLanguageTag(lang)).replaceFirstChar { char ->
                    if (char.isLowerCase()) {
                        char.titlecase(Locale.forLanguageTag(lang))
                    } else {
                        char.toString()
                    }
                }
            }
                .joinToString { it.trim() }

            status = seriesDetails.selectFirst(seriesStatusSelector)?.text().parseStatus()
            thumbnail_url = seriesDetails.select(seriesThumbnailSelector).imgAttr()
        }
    }

        // Parsing daftar chapter
    override fun chapterListSelector() = "div.komik_info-chapters li"
    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        name = element.select(".chapter-link-item").text()

        // Prioritaskan ISO timestamp yang tersembunyi
        val createdAtIso = element.selectFirst(".chapter-link-time .chapter-created-at")
            ?.text()
            ?.trim()

        date_upload = if (!createdAtIso.isNullOrEmpty()) {
            parseIsoDate(createdAtIso)
        } else {
            // fallback: hanya teks visible tanpa span tersembunyi
            val visibleTime = element.selectFirst(".chapter-link-time")?.ownText()?.trim().orEmpty()
            parseChapterDate(visibleTime)
        }
    }

    // Untuk parsing ISO timestamp seperti: 2022-02-13T06:21:18+07:00
    private fun parseIsoDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        return try {
            // Coba tanpa milidetik
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            sdf.parse(date)?.time ?: run {
                // Coba dengan milidetik jika ada fractional seconds
                val sdfMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                sdfMs.parse(date)?.time ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("id"))

    private fun parseChapterDate(date: String): Long {
        val txt = date.trim().lowercase(Locale.US)

        if (txt.isEmpty()) return 0L

        // beberapa varian cepat
        if (txt.contains("just now") || txt == "now" || txt.contains("baru saja") || txt == "baru") {
            return System.currentTimeMillis()
        }

        // cari angka dalam teks (mis. "1 month ago" -> 1). jika tidak ada, cek 'a'/'one'/'an'
        val number = Regex("""\d+""").find(txt)?.value?.toIntOrNull()
            ?: if (txt.startsWith("a ") || txt.startsWith("one ") || txt.startsWith("an ") || txt.startsWith("sebuah ") ) 1 else null

        val cal = Calendar.getInstance()
        when {
            txt.contains("min") || txt.contains("menit") -> {
                val v = number ?: 1
                cal.add(Calendar.MINUTE, -v)
                return cal.timeInMillis
            }
            txt.contains("hour") || txt.contains("jam") -> {
                val v = number ?: 1
                cal.add(Calendar.HOUR_OF_DAY, -v)
                return cal.timeInMillis
            }
            txt.contains("day") || txt.contains("hari") -> {
                val v = number ?: 1
                cal.add(Calendar.DATE, -v)
                return cal.timeInMillis
            }
            txt.contains("week") || txt.contains("minggu") -> {
                val v = number ?: 1
                cal.add(Calendar.DATE, -v * 7)
                return cal.timeInMillis
            }
            txt.contains("month") || txt.contains("bulan") -> {
                val v = number ?: 1
                cal.add(Calendar.MONTH, -v)
                return cal.timeInMillis
            }
            txt.contains("year") || txt.contains("tahun") -> {
                val v = number ?: 1
                cal.add(Calendar.YEAR, -v)
                return cal.timeInMillis
            }
            else -> {
                // fallback: coba parse format tanggal seperti "12 Des 2025"
                return try {
                    dateFormat.parse(date)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            }
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div#chapter_body .main-reading-area img.size-full")
            .distinctBy { img -> img.imgAttr() }
            .mapIndexed { i, img -> Page(i, document.location(), img.imgAttr()) }
    }

    override val hasProjectPage: Boolean = true
    override val projectPageString = "/project-list"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()

        if (query.isNotEmpty()) {
            url.addPathSegments("page/$page/").addQueryParameter("s", query)
            return GET(url.build(), headers)
        }

        url.addPathSegment(mangaUrlDirectory.substring(1))
            .addPathSegments("page/$page/")

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> {
                    url.addQueryParameter("status", filter.selectedValue())
                }
                is TypeFilter -> {
                    url.addQueryParameter("type", filter.selectedValue())
                }
                is OrderByFilter -> {
                    url.addQueryParameter("orderby", filter.selectedValue())
                }
                is GenreListFilter -> {
                    filter.state
                        .filter { it.state != Filter.TriState.STATE_IGNORE }
                        .forEach {
                            val value = if (it.state == Filter.TriState.STATE_EXCLUDE) "-${it.value}" else it.value
                            url.addQueryParameter("genre[]", value)
                        }
                }
                // if site has project page, default value "hasProjectPage" = false
                is ProjectFilter -> {
                    if (filter.selectedValue() == "project-filter-on") {
                        url.setPathSegment(0, projectPageString.substring(1))
                    }
                }
                else -> { /* Do Nothing */ }
            }
        }
        return GET(url.build(), headers)
    }

    private class StatusFilter : SelectFilter(
        "Status",
        arrayOf(
            Pair("All", ""),
            Pair("Ongoing", "ongoing"),
            Pair("Completed", "completed"),
        ),
    )

    private class TypeFilter : SelectFilter(
        "Type",
        arrayOf(
            Pair("All", ""),
            Pair("Manga", "manga"),
            Pair("Manhwa", "manhwa"),
            Pair("Manhua", "manhua"),
        ),
    )

    private class OrderByFilter(defaultOrder: String? = null) : SelectFilter(
        "Sort By",
        arrayOf(
            Pair("Default", ""),
            Pair("A-Z", "titleasc"),
            Pair("Z-A", "titledesc"),
            Pair("Update", "update"),
            Pair("Popular", "popular"),
        ),
        defaultOrder,
    )

    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>(
            Filter.Separator(),
            StatusFilter(),
            TypeFilter(),
            OrderByFilter(),
            Filter.Header(intl["genre_exclusion_warning"]),
            GenreListFilter(intl["genre_filter_title"], getGenreList()),
            Filter.Separator(),
            Filter.Header(intl["project_filter_warning"]),
            Filter.Header(intl.format("project_filter_name", name)),
            ProjectFilter(intl["project_filter_title"], projectFilterOptions),
        )
        return FilterList(filters)
    }
}