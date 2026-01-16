package eu.kanade.tachiyomi.extension.id.komikcast

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class KomikCast : ConfigurableSource, ParsedHttpSource() {
    override val name: String = "KomikCast"
    override val baseUrl: String = "https://komikcast.com"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true

    // Rate limit dan client
    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Android)")

    // ------------------ Popular / Latest / Search ------------------
    override fun popularMangaRequest(page: Int): Request {
        val url = HttpUrl.parse("$baseUrl/manga")?.newBuilder()
            ?.addQueryParameter("page", page.toString())
            ?.build() ?: throw IllegalStateException("Invalid URL")
        return GET(url.toString(), headers)
    }

    override fun popularMangaSelector(): String = "div.listupd > article"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val titleEl = element.selectFirst("h3.entry-title a")
        manga.title = titleEl?.text()?.trim() ?: ""
        manga.setUrlWithoutDomain(titleEl?.attr("href") ?: "")
        val img = element.selectFirst("img")
        manga.thumbnail_url = img?.attr("data-src") ?: img?.attr("src")
        return manga
    }

    override fun popularMangaNextPageSelector(): String? = "a.next"

    override fun latestUpdatesRequest(page: Int): Request {
        val url = HttpUrl.parse(baseUrl)?.newBuilder()
            ?.addPathSegment("page")
            ?.addPathSegment(page.toString())
            ?.build() ?: throw IllegalStateException("Invalid URL")
        return GET(url.toString(), headers)
    }

    override fun latestUpdatesSelector(): String = "div.listupd > article"

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = "a.next"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = HttpUrl.parse("$baseUrl/page/1/")?.newBuilder()
            ?.addQueryParameter("s", query)
            ?.build() ?: throw IllegalStateException("Invalid URL")
        return GET(url.toString(), headers)
    }

    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector(): String? = popularMangaNextPageSelector()

    // ------------------ Manga details / chapters / pages ------------------
    override fun mangaDetailsRequest(mangaUrl: String): Request = GET(baseUrl + mangaUrl, headers)

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        manga.description = document.selectFirst("div.entry-content p")?.text()?.trim() ?: ""
        manga.thumbnail_url = document.selectFirst("div.thumb img")?.attr("src")
        return manga
    }

    override fun chapterListSelector(): String = "ul.lst > li"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val a = element.selectFirst("a")
        chapter.setUrlWithoutDomain(a?.attr("href") ?: "")
        chapter.name = a?.text()?.trim() ?: ""
        val dateText = element.selectFirst("span.date")?.text()?.trim() ?: ""
        chapter.date_upload = parseChapterDate(dateText)
        return chapter
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        val imgs = document.select("div.reading-content img")
        imgs.forEachIndexed { i, img ->
            val url = img.attr("data-src").ifEmpty { img.attr("src") }
            pages.add(Page(i, document.location(), url))
        }
        return pages
    }

    override fun imageUrlRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String = response.request().url().toString()

    // ------------------ Utilities ------------------
    // PASTIKAN HANYA ADA SATU fungsi parseChapterDate di file ini
    private fun parseChapterDate(date: String): Long {
        if (date.isBlank()) return 0L

        // Coba beberapa format yang umum dipakai di situs Indonesia/Internasional
        val formats = arrayOf(
            "dd MMM yyyy",
            "dd MMMM yyyy",
            "d MMM yyyy",
            "yyyy-MM-dd",
            "dd/MM/yyyy"
        )

        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale("id"))
                sdf.isLenient = true
                val d: Date? = sdf.parse(date)
                if (d != null) return d.time
            } catch (e: ParseException) {
                // lanjut ke format berikutnya
            } catch (e: Exception) {
                // ignore
            }
        }

        // Jika masih gagal, kembalikan 0
        return 0L
    }

    // ------------------ Configurable source (contoh sederhana) ------------------
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val pref = EditTextPreference(screen.context).apply {
            key = "komikcast_custom_base"
            title = "Custom base URL (opsional)"
            summary = "Ganti base URL jika mirror berbeda"
            dialogTitle = "Base URL KomikCast"
            setDefaultValue(baseUrl)
        }
        screen.addPreference(pref)
    }

    override fun getFilterList(): FilterList = FilterList()
}
