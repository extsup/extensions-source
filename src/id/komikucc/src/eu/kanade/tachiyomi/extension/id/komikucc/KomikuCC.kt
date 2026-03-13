package eu.kanade.tachiyomi.extension.id.komikucc

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomikuCC : ParsedHttpSource(), ConfigurableSource {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val name = "Komiku CC"
    override val lang = "id"
    override val supportsLatest = true

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN, DEFAULT_DOMAIN)!!.trimEnd('/')

    private val resizeUrl: String
        get() = preferences.getString(PREF_RESIZE_URL, DEFAULT_RESIZE_URL)!!

    private val coverResizeUrl: String
        get() = preferences.getString(PREF_COVER_RESIZE_URL, DEFAULT_COVER_RESIZE_URL)!!

    // ==================== Preference Screen ====================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN
            title = "Domain Override"
            summary = "Ganti base URL sumber. Default: $DEFAULT_DOMAIN"
            setDefaultValue(DEFAULT_DOMAIN)
            dialogTitle = "Domain"
            dialogMessage = "Contoh: https://komiku.cc"
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_RESIZE_URL
            title = "Resize Service URL (Halaman)"
            summary = "Prefix URL proxy untuk gambar halaman baca. Default: $DEFAULT_RESIZE_URL"
            setDefaultValue(DEFAULT_RESIZE_URL)
            dialogTitle = "Resize URL Halaman"
            dialogMessage = "Contoh: https://wsrv.nl/?url= — kosongkan untuk tanpa proxy"
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_COVER_RESIZE_URL
            title = "Resize Service URL (Cover)"
            summary = "Prefix URL proxy untuk cover manga. Default: $DEFAULT_COVER_RESIZE_URL"
            setDefaultValue(DEFAULT_COVER_RESIZE_URL)
            dialogTitle = "Resize URL Cover"
            dialogMessage = "Contoh: https://wsrv.nl/?url= — kosongkan untuk tanpa proxy"
        }.also(screen::addPreference)
    }

    private fun proxiedPageUrl(originalUrl: String): String {
        val prefix = resizeUrl.trim()
        return if (prefix.isBlank()) originalUrl else "$prefix$originalUrl"
    }

    private fun proxiedCoverUrl(originalUrl: String): String {
        val prefix = coverResizeUrl.trim()
        return if (prefix.isBlank()) originalUrl else "$prefix$originalUrl"
    }

    // ==================== Popular ====================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/list?order=popular&page=$page", headers)

    override fun popularMangaSelector() = "div.grid a[href*='/komik/']"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("h3")?.text() ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("src")
            ?.let { proxiedCoverUrl(it) }
    }

    override fun popularMangaNextPageSelector() =
        "a[href*='page=']:has(button), a[href*='page='][class*='next']"

    // ==================== Latest ====================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/list?order=update&page=$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ==================== Search ====================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/search?q=${query.trim()}&page=$page", headers)
        } else {
            val url = "$baseUrl/list".toHttpUrl().newBuilder()
            filters.forEach { filter ->
                when (filter) {
                    is StatusFilter -> if (filter.state > 0) url.addQueryParameter("status", filter.values[filter.state])
                    is TypeFilter -> if (filter.state > 0) url.addQueryParameter("type", filter.values[filter.state])
                    is OrderFilter -> if (filter.state > 0) url.addQueryParameter("order", filter.values[filter.state])
                    else -> {}
                }
            }
            url.addQueryParameter("page", page.toString())
            GET(url.build().toString(), headers)
        }
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ==================== Manga Details ====================

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        thumbnail_url = document.selectFirst("img[src*='cdn.komiku.cc/uploads']")?.attr("src")
            ?.let { proxiedCoverUrl(it) }
        title = document.selectFirst("h1.text-xl")?.text() ?: ""
        author = document.selectFirst("li:has(span.font-medium:contains(Author:)) span.text-sm:last-child")?.text()

        val genres = document.select("ul.flex li.bg-zinc-700").map { it.text() }
        val type = document.selectFirst("li:has(span.font-medium:contains(Type:)) span.text-sm:last-child")?.text()
        genre = (genres + listOfNotNull(type)).joinToString(", ")

        description = document.selectFirst("p.line-clamp-4")?.text()

        val statusText = document.selectFirst("span.bg-gray-100")?.text()?.lowercase() ?: ""
        status = when {
            statusText.contains("ongoing") -> SManga.ONGOING
            statusText.contains("selesai") || statusText.contains("completed") -> SManga.COMPLETED
            statusText.contains("hiatus") -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    // ==================== Chapter List ====================

    override fun chapterListSelector() = "div.chapterlists div.w-full a[href]"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("span.chaptertitle")?.text() ?: element.text()
        date_upload = parseRelativeDate(element.selectFirst("span.text-zinc-400")?.text() ?: "")
    }

    // ==================== Page List ====================

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.touch-manipulation img[src*='cdn.komiku.cc/images']")
            .mapIndexed { index, img ->
                Page(index, "", proxiedPageUrl(img.attr("src")))
            }
    }

    override fun imageUrlParse(document: Document) = ""

    // ==================== Date Parsing ====================

    private fun parseRelativeDate(text: String): Long {
        if (text.isBlank()) return 0L
        val now = System.currentTimeMillis()
        val parts = text.trim().split(" ")
        if (parts.size < 2) return 0L
        val value = parts[0].toLongOrNull() ?: return 0L
        return when {
            parts[1].startsWith("menit") -> now - value * 60 * 1000
            parts[1].startsWith("jam") -> now - value * 60 * 60 * 1000
            parts[1].startsWith("hari") -> now - value * 24 * 60 * 60 * 1000
            parts[1].startsWith("minggu") -> now - value * 7 * 24 * 60 * 60 * 1000
            parts[1].startsWith("bulan") -> now - value * 30 * 24 * 60 * 60 * 1000
            parts[1].startsWith("tahun") -> now - value * 365 * 24 * 60 * 60 * 1000
            else -> 0L
        }
    }

    // ==================== Filters ====================

    override fun getFilterList() = FilterList(
        Filter.Header("Filter tidak berlaku saat pencarian teks"),
        StatusFilter(),
        TypeFilter(),
        OrderFilter(),
    )

    class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("Semua", "ongoing", "selesai", "hiatus"),
        0,
    )

    class TypeFilter : Filter.Select<String>(
        "Type",
        arrayOf("Semua", "manga", "manhwa", "manhua"),
        0,
    )

    class OrderFilter : Filter.Select<String>(
        "Order",
        arrayOf("Semua", "az", "za", "update", "latest", "popular"),
        0,
    )

    // ==================== Companion ====================

    companion object {
        private const val PREF_DOMAIN = "pref_domain"
        private const val PREF_RESIZE_URL = "pref_resize_url"
        private const val PREF_COVER_RESIZE_URL = "pref_cover_resize_url"

        private const val DEFAULT_DOMAIN = "https://komiku.cc"
        private const val DEFAULT_RESIZE_URL = "https://wsrv.nl/?url="
        private const val DEFAULT_COVER_RESIZE_URL = "https://wsrv.nl/?url="
    }
}