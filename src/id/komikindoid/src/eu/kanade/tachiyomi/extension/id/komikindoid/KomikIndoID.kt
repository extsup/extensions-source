package eu.kanade.tachiyomi.extension.id.komikindoid

import eu.kanade.tachiyomi.network.GET

import eu.kanade.tachiyomi.source.model.Page

import eu.kanade.tachiyomi.source.model.SChapter

import eu.kanade.tachiyomi.source.model.SManga

import eu.kanade.tachiyomi.source.online.ParsedHttpSource

import okhttp3.HttpUrl.Companion.toHttpUrl

import okhttp3.OkHttpClient

import okhttp3.Request

import org.jsoup.nodes.Document

import org.jsoup.nodes.Element

import java.text.SimpleDateFormat

import java.util.Calendar

import java.util.Locale

import android.content.SharedPreferences

import androidx.preference.PreferenceScreen

import androidx.preference.EditTextPreference

import eu.kanade.tachiyomi.source.ConfigurableSource

import uy.kohesive.injekt.injectLazy

class KomikIndoID : ParsedHttpSource(), ConfigurableSource {

    override val name = "KomikIndoID"

    override val baseUrl by lazy { preferences.getString(DOMAIN_PREF, DEFAULT_BASE_URL)!! }

    override val lang = "id"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    private val preferences: SharedPreferences by injectLazy()

    private val resizeService by lazy { preferences.getString(RESIZE_PREF, "")!! }

    companion object {

        private const val DOMAIN_PREF = "domain"

        private const val DEFAULT_BASE_URL = "https://komikindo.ch"

        private const val RESIZE_PREF = "resize_service"

    }

    // similar/modified theme of "https://bacakomik.my"

    override fun popularMangaRequest(page: Int): Request {

        return GET("$baseUrl/daftar-manga/page/$page/?order=popular", headers)

    }

    override fun latestUpdatesRequest(page: Int): Request {

        return GET("$baseUrl/daftar-manga/page/$page/?order=update", headers)

    }

    override fun popularMangaSelector() = "div.animepost"

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun popularMangaNextPageSelector() = "a.next.page-numbers"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaFromElement(element: Element): SManga {

        val manga = SManga.create()

        val thumbUrl = element.select("div.limit img").attr("src")

        manga.thumbnail_url = getResizedUrl(thumbUrl)

        manga.title = element.select("div.tt h4").text()

        element.select("div.animposx > a").first()!!.let {

            manga.setUrlWithoutDomain(it.attr("href"))

        }

        return manga

    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {

        return GET("$baseUrl/daftar-manga/page/$page/?title=$query", headers)

    }

    override fun mangaDetailsParse(document: Document): SManga {

        val infoElement = document.select("div.infoanime").first()!!

        val descElement = document.select("div.desc > .entry-content.entry-content-single").first()!!

        val manga = SManga.create()

        // need authorCleaner to take "pengarang:" string to remove it from author

        val authorCleaner = document.select(".infox .spe b:contains(Pengarang)").text()

        manga.author = document.select(".infox .spe span:contains(Pengarang)").text().substringAfter(authorCleaner)

        val artistCleaner = document.select(".infox .spe b:contains(Ilustrator)").text()

        manga.artist = document.select(".infox .spe span:contains(Ilustrator)").text().substringAfter(artistCleaner)

        val genres = mutableListOf<String>()

        infoElement.select(".infox .genre-info a, .infox .spe span:contains(Grafis:) a, .infox .spe span:contains(Tema:) a, .infox .spe span:contains(Konten:) a, .infox .spe span:contains(Jenis Komik:) a").forEach { element ->

            val genre = element.text()

            genres.add(genre)

        }

        manga.genre = genres.joinToString(", ")

        manga.status = parseStatus(infoElement.select(".infox > .spe > span:nth-child(2)").text())

        manga.description = descElement.select("p").text().substringAfter("bercerita tentang ")

        // Add alternative name to manga description

        val altName = document.selectFirst(".infox > .spe > span:nth-child(1)")?.text().takeIf { it.isNullOrBlank().not() }

        altName?.let {

            manga.description = manga.description + "\n\n$altName"

        }

        val thumbUrl = document.select(".thumb > img:nth-child(1)").attr("src").substringBeforeLast("?")

        manga.thumbnail_url = getResizedUrl(thumbUrl)

        return manga

    }

    private fun parseStatus(element: String): Int = when {

        element.contains("berjalan", true) -> SManga.ONGOING

        element.contains("tamat", true) -> SManga.COMPLETED

        else -> SManga.UNKNOWN

    }

    override fun chapterListSelector() = "#chapter_list li"

    override fun chapterFromElement(element: Element): SChapter {

        val urlElement = element.select(".lchx a").first()!!

        val chapter = SChapter.create()

        chapter.setUrlWithoutDomain(urlElement.attr("href"))

        chapter.name = urlElement.text()

        chapter.date_upload = element.select(".dt a").first()?.text()?.let { parseChapterDate(it) } ?: 0

        return chapter

    }

    private fun parseChapterDate(date: String): Long {

        return if (date.contains("yang lalu")) {

            val value = date.split(' ')[0].toInt()

            when {

                "detik" in date -> Calendar.getInstance().apply {

                    add(Calendar.SECOND, -value)

                }.timeInMillis

                "menit" in date -> Calendar.getInstance().apply {

                    add(Calendar.MINUTE, -value)

                }.timeInMillis

                "jam" in date -> Calendar.getInstance().apply {

                    add(Calendar.HOUR_OF_DAY, -value)

                }.timeInMillis

                "hari" in date -> Calendar.getInstance().apply {

                    add(Calendar.DATE, -value)

                }.timeInMillis

                "minggu" in date -> Calendar.getInstance().apply {

                    add(Calendar.DATE, -value * 7)

                }.timeInMillis

                "bulan" in date -> Calendar.getInstance().apply {

                    add(Calendar.MONTH, -value)

                }.timeInMillis

                "tahun" in date -> Calendar.getInstance().apply {

                    add(Calendar.YEAR, -value)

                }.timeInMillis

                else -> {

                    0L

                }

            }

        } else {

            try {

                dateFormat.parse(date)?.time ?: 0

            } catch (_: Exception) {

                0L

            }

        }

    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {

        val basic = Regex("""Chapter\s([0-9]+)""")

        when {

            basic.containsMatchIn(chapter.name) -> {

                basic.find(chapter.name)?.let {

                    chapter.chapter_number = it.groups[1]?.value!!.toFloat()

                }

            }

        }

    }

    override fun pageListParse(document: Document): List<Page> {

        val pages = mutableListOf<Page>()

        var i = 0

        document.select("div.img-landmine img").forEach { element ->

            var url = element.attr("onError").substringAfter("src='").substringBefore("';")

            url = getResizedUrl(url)

            i++

            if (url.isNotEmpty()) {

                pages.add(Page(i, "", url))

            }

        }

        return pages

    }

    private fun getResizedUrl(url: String): String {

        return if (resizeService.isNotEmpty()) "$resizeService?url=$url" else url

    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {

        EditTextPreference(screen.context).apply {

            key = DOMAIN_PREF

            title = "Domain"

            setDefaultValue(DEFAULT_BASE_URL)

            dialogTitle = "Domain"

            dialogMessage = "Ubah domain situs (contoh: https://komikindo.ch)"

            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->

                val new = newValue as String

                preferences.edit().putString(DOMAIN_PREF, new).commit()

                true

            }

        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {

            key = RESIZE_PREF

            title = "Layanan Resize Gambar"

            setDefaultValue("")

            dialogTitle = "Layanan Resize"

            dialogMessage = "Masukkan URL prefix layanan resize, contoh: https://resize.example.com\nAkan digunakan sebagai <layanan>?url=<gambar>"

            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->

                val new = newValue as String

                preferences.edit().putString(RESIZE_PREF, new).commit()

                true

            }

        }.also(screen::addPreference)

    }

}