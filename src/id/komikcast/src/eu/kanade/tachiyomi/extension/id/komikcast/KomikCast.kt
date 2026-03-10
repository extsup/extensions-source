package eu.kanade.tachiyomi.extension.id.komikcast

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KomikCast :
    HttpSource(),
    ConfigurableSource {

    // Formerly "Komik Cast (WP Manga Stream)"
    override val id = 972717448578983812

    override val name = "Komik Cast"

    override val lang = "id"

    override val supportsLatest = true

    // ======================== Preferences ========================
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val prefDomain: String
        get() = preferences.getString(PREF_DOMAIN_KEY, DEFAULT_DOMAIN)
            ?.trimEnd('/')
            ?.ifEmpty { DEFAULT_DOMAIN }
            ?: DEFAULT_DOMAIN

    private val prefImageProxy: String
        get() = preferences.getString(PREF_IMAGE_PROXY_KEY, "")?.trim() ?: ""

    private val prefCoverProxy: String
        get() = preferences.getString(PREF_COVER_PROXY_KEY, DEFAULT_COVER_PROXY)?.trim() ?: DEFAULT_COVER_PROXY

    companion object {
        private const val DEFAULT_DOMAIN = "https://v1.komikcast.fit"
        private const val PREF_DOMAIN_KEY = "pref_custom_domain"
        private const val PREF_IMAGE_PROXY_KEY = "pref_image_proxy"
        private const val PREF_COVER_PROXY_KEY = "pref_cover_proxy"
        private const val DEFAULT_COVER_PROXY = "https://proxygambar.vercel.app/api/gambar?w=110&h=150&url="
    }

    override val baseUrl get() = prefDomain

    private val apiUrl = "https://be.komikcast.fit"

    // Cover di-resize via proxy yang bisa dikonfigurasi di preferences
    private fun coverUrl(originalUrl: String?): String? {
        if (originalUrl.isNullOrBlank()) return null
        val proxy = prefCoverProxy
        return if (proxy.isBlank()) originalUrl else "$proxy${java.net.URLEncoder.encode(originalUrl, "UTF-8")}"
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(1, 3, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json")
        .add("Accept-language", "en-US,en;q=0.9,id;q=0.8")

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("includeMeta", "true")
            .addQueryParameter("sort", "popularity")
            .addQueryParameter("sortOrder", "desc")
            .addQueryParameter("take", "12")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseSeriesListResponse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("includeMeta", "true")
            .addQueryParameter("sort", "latest")
            .addQueryParameter("sortOrder", "desc")
            .addQueryParameter("take", "12")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseSeriesListResponse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("includeMeta", "true")
            .addQueryParameter("take", "12")
            .addQueryParameter("page", page.toString())

        if (query.isNotEmpty()) {
            url.addQueryParameter("filter", "title=like=\"$query\",nativeTitle=like=\"$query\"")
        }

        filters.filterIsInstance<UriFilter>().forEach {
            it.addToUri(url)
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseSeriesListResponse(response)

    override fun getMangaUrl(manga: SManga): String {
        val path = "$baseUrl${manga.url}".toHttpUrl().pathSegments
        val slug = path[1]
        return "$baseUrl/series/$slug"
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val path = "$baseUrl${manga.url}".toHttpUrl().pathSegments
        val slug = path[1]
        return GET("$apiUrl/series/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<SeriesDetailResponse>()
        return result.data.toSManga().apply {
            thumbnail_url = coverUrl(result.data.data.coverImage)
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val path = "$baseUrl${manga.url}".toHttpUrl().pathSegments
        val slug = path[1]
        return GET("$apiUrl/series/$slug/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<ChapterListResponse>()
        val slug = response.request.url.pathSegments[1]
        return result.data.map { it.toSChapter(slug) }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        if (chapter.url.startsWith("/chapter/")) {
            val slug = chapter.url.substringAfter("/chapter/").substringBefore("-chapter-")
            val chapterIndex = chapter.url.substringAfter("-chapter-").substringBefore("-bahasa-")
            return GET("$apiUrl/series/$slug/chapters/$chapterIndex", headers)
        }

        val path = "$baseUrl${chapter.url}".toHttpUrl().pathSegments
        val slug = path[1]
        val chapterIndex = path[3]
        return GET("$apiUrl/series/$slug/chapters/$chapterIndex", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<ChapterDetailResponse>()
        val images = result.data.data.images ?: emptyList()

        if (images.isEmpty()) {
            throw Exception("Page list is empty - No images found in chapter")
        }

        val proxy = prefImageProxy
        return images.mapIndexed { index, imageUrl ->
            val finalUrl = if (proxy.isBlank()) imageUrl else "$proxy$imageUrl"
            Page(index, "", finalUrl)
        }
    }

    private fun parseSeriesListResponse(response: Response): MangasPage {
        val result = response.parseAs<SeriesListResponse>()
        val mangas = result.data.map { item ->
            item.toSManga().apply {
                thumbnail_url = coverUrl(item.data.coverImage)
            }
        }
        val hasNextPage = result.meta?.let { it.page ?: 0 < (it.lastPage ?: 0) } ?: false
        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList(): FilterList {
        return FilterList(
            SortFilter(),
            SortOrderFilter(),
            StatusFilter(),
            FormatFilter(),
            TypeFilter(),
            GenreFilter(getGenres()),
        )
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    // ======================== Preference Screen ========================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Domain Komik Cast"
            summary = prefDomain
            dialogTitle = "Domain Komik Cast"
            dialogMessage = "Masukkan domain baru (contoh: https://v2.komikcast.fit)\nKosongkan untuk kembali ke default."
            setDefaultValue(DEFAULT_DOMAIN)
            setOnPreferenceChangeListener { pref, newValue ->
                val value = (newValue as? String)?.trimEnd('/') ?: DEFAULT_DOMAIN
                pref.summary = value.ifEmpty { DEFAULT_DOMAIN }
                true
            }
            screen.addPreference(this)
        }

        EditTextPreference(screen.context).apply {
            key = PREF_COVER_PROXY_KEY
            title = "Proxy Resize Cover"
            summary = buildCoverProxySummary(prefCoverProxy)
            dialogTitle = "Proxy Resize Cover"
            dialogMessage = "Prefix URL proxy untuk resize cover manga.\nKosongkan untuk nonaktif."
            setDefaultValue(DEFAULT_COVER_PROXY)
            setOnPreferenceChangeListener { pref, newValue ->
                val value = (newValue as? String)?.trim() ?: ""
                pref.summary = buildCoverProxySummary(value)
                true
            }
            screen.addPreference(this)
        }

        EditTextPreference(screen.context).apply {
            key = PREF_IMAGE_PROXY_KEY
            title = "Proxy Resize Gambar"
            summary = buildProxySummary(prefImageProxy)
            dialogTitle = "Proxy Resize Gambar"
            dialogMessage = "Masukkan prefix URL proxy.\nContoh: https://wsrv.nl/?url=\nKosongkan untuk nonaktif."
            setDefaultValue("")
            setOnPreferenceChangeListener { pref, newValue ->
                val value = (newValue as? String)?.trim() ?: ""
                pref.summary = buildProxySummary(value)
                true
            }
            screen.addPreference(this)
        }
    }

    private fun buildProxySummary(proxy: String): String =
        if (proxy.isBlank()) "Nonaktif (gambar asli)" else proxy

    private fun buildCoverProxySummary(proxy: String): String =
        if (proxy.isBlank()) "Nonaktif (gambar asli)" else proxy
}
