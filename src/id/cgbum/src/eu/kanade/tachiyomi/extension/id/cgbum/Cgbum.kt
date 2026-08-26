package eu.kanade.tachiyomi.extension.id.cgbum

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.setUrlWithoutDomain
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.api.get

@Source
abstract class Cgbum :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val showAdult get() = preferences.getBoolean(PREF_SHOW_ADULT, false)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val doc = client.get("$baseUrl/populer?page=$page").asJsoup()
        return parseMangaList(doc)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val doc = client.get("$baseUrl/last-update?page=$page").asJsoup()
        return parseMangaList(doc)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/daftar-komik".toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) addQueryParameter("search", query)
            filters.firstInstanceOrNull<TypeFilter>()?.let {
                if (it.state != 0) addQueryParameter("type", it.values[it.state])
            }
            filters.firstInstanceOrNull<StatusFilter>()?.let {
                if (it.state != 0) addQueryParameter("status", it.values[it.state])
            }
            filters.firstInstanceOrNull<SortFilter>()?.let {
                addQueryParameter("sort", it.values[it.state])
            }
            filters.firstInstanceOrNull<GenreGroup>()?.let { group ->
                // Site only supports up to 3 genres
                group.state.filter { it.state }.take(3).forEach {
                    addQueryParameter("genres[]", it.name)
                }
            }
            addQueryParameter("page", page.toString())
        }.build()

        val doc = client.get(url).asJsoup()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): MangasPage {
        val mangas = doc.select("article.comic-card")
            .filter { showAdult || it.attr("data-adult") != "1" }
            .map { parseMangaFromElement(it) }
        val hasNext = doc.selectFirst("a.page-nav-next") != null
        return MangasPage(mangas, hasNext)
    }

    private fun parseMangaFromElement(el: Element): SManga = SManga.create().apply {
        val coverLink = el.selectFirst("a.comic-card-cover")!!
        setUrlWithoutDomain(coverLink.attr("abs:href"))
        title = el.selectFirst("h3.comic-card-title a")!!.text()
        thumbnail_url = coverLink.selectFirst("img")?.attr("abs:src")
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get(baseUrl + manga.url).asJsoup()

        val updatedManga = SManga.create().apply {
            title = doc.selectFirst("h1")?.text() ?: manga.title
            thumbnail_url = doc.selectFirst("div.comic-cover img")?.attr("abs:src") ?: manga.thumbnail_url
            description = doc.selectFirst("div.comic-synopsis")?.text()
            genre = doc.select("a.genre-pill").joinToString { it.text() }
            author = doc.select("div.meta-row")
                .firstOrNull { it.selectFirst("span.meta-label")?.text() == "Author" }
                ?.selectFirst("span.meta-value")
                ?.text()
            status = doc.selectFirst("span.badge-status")?.text().orEmpty().lowercase().let {
                when {
                    it.contains("ongoing") -> SManga.ONGOING
                    it.contains("tamat") || it.contains("completed") -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }
        }

        val chapterList = doc.select("a.ch-grid-item").map { el ->
            SChapter.create().apply {
                setUrlWithoutDomain(el.attr("abs:href"))
                name = el.attr("title").ifEmpty { el.text() }
            }
        }

        return SMangaUpdate(updatedManga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get(baseUrl + chapter.url).asJsoup()
        return doc.select("div.page-container[data-url]").mapIndexed { index, el ->
            Page(index, imageUrl = el.attr("data-url"))
        }
    }

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Genre: pilih maksimal 3"),
        TypeFilter(),
        StatusFilter(),
        SortFilter(),
        GenreGroup(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_ADULT
            title = "Tampilkan komik dewasa"
            summaryOff = "Komik 18+ disembunyikan dari daftar"
            summaryOn = "Semua komik ditampilkan termasuk 18+"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_ADULT = "show_adult"
    }
}
