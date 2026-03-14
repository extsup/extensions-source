package eu.kanade.tachiyomi.extension.id.softkomik

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class Softkomik :
    HttpSource(),
    ConfigurableSource {

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

    // ======================== Source Info ========================
    override val name = "Softkomik"
    override val baseUrl get() = prefDomain
    override val lang = "id"
    override val supportsLatest = true

    companion object {
        private const val COVER_URL = "https://cover.softdevices.my.id/softkomik-cover"
        private const val IMAGE_URL = "https://cd1.softkomik.online/softkomik"
        private const val CHAPTER_URL = "https://v2.softdevices.my.id"

        private const val DEFAULT_DOMAIN = "https://softkomik.co"
        private const val PREF_DOMAIN_KEY = "pref_custom_domain"
        private const val PREF_IMAGE_PROXY_KEY = "pref_image_proxy"
    }

    private fun coverUrl(gambar: String): String {
        val original = "$COVER_URL/${gambar.removePrefix("/")}"
        return "https://wsrv.nl/?url=$original&w=110&h=150&fit=cover"
    }

    private val sessionClient = network.cloudflareClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(::buildIdOutdatedInterceptor)
        .addInterceptor(::sessionInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ======================== Build ID ========================
    @Volatile
    private var buildId = ""
        get() = field.ifEmpty {
            synchronized(this) {
                field.ifEmpty { fetchBuildId().also { field = it } }
            }
        }

    private fun fetchBuildId(document: Document? = null): String {
        val doc = document
            ?: sessionClient.newCall(GET(baseUrl, headers)).execute().use { it.asJsoup() }
        val nextData = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Could not find __NEXT_DATA__")
        return nextData.parseAs<NextDataDto>().buildId
    }

    private fun buildIdOutdatedInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (
            response.code == 404 &&
            request.url.run {
                host == baseUrl.removePrefix("https://") &&
                    pathSegments.getOrNull(0) == "_next" &&
                    pathSegments.getOrNull(1) == "data" &&
                    fragment != "DO_NOT_RETRY"
            } &&
            response.header("Content-Type")?.contains("text/html") != false
        ) {
            val document = response.asJsoup()
            val newBuildId = fetchBuildId(document)
            synchronized(this) { buildId = newBuildId }

            val newUrl = request.url.newBuilder()
                .setPathSegment(2, newBuildId)
                .fragment("DO_NOT_RETRY")
                .build()
            return chain.proceed(request.newBuilder().url(newUrl).build())
        }
        return response
    }

    // ======================== Session ========================
    @Volatile
    private var session: SessionDto? = null
        get() {
            val current = field
            return if (current == null || System.currentTimeMillis() >= current.ex - 60_000) {
                synchronized(this) {
                    val recheck = field
                    if (recheck == null || System.currentTimeMillis() >= recheck.ex - 60_000) {
                        fetchSession().also { field = it }
                    } else recheck
                }
            } else current
        }

    private fun fetchSession(): SessionDto {
        return sessionClient.newCall(
            GET("$baseUrl/api/sessions", headers),
        ).execute().use { it.parseAs<SessionDto>() }
    }

    private fun sessionInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code == 401 && request.url.host == "v2.softdevices.my.id") {
            response.close()
            synchronized(this) { session = fetchSession().also { session = it } }
            val sess = session!!
            val newRequest = request.newBuilder()
                .header("X-Token", sess.token)
                .header("X-Sign", sess.sign)
                .build()
            return chain.proceed(newRequest)
        }
        return response
    }

    private fun chapterHeaders(): Headers {
        val sess = session ?: fetchSession().also { session = it }
        return headersBuilder()
            .add("X-Token", sess.token)
            .add("X-Sign", sess.sign)
            .build()
    }

    // ======================== Popular ========================
    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/_next/data/$buildId/komik/library.json".toHttpUrl().newBuilder()
            .addQueryParameter("sortBy", "popular")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    // ======================== Latest ========================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/_next/data/$buildId/komik/library.json".toHttpUrl().newBuilder()
            .addQueryParameter("sortBy", "new")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    // ======================== Search ========================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotEmpty()) {
            "$CHAPTER_URL/komik".toHttpUrl().newBuilder()
                .addQueryParameter("name", query)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", "24")
        } else {
            "$baseUrl/_next/data/$buildId/komik/library.json".toHttpUrl().newBuilder()
                .addQueryParameter("search", "")
                .addQueryParameter("page", page.toString())
        }

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> url.addQueryParameter("status", filter.selected)
                is TypeFilter -> url.addQueryParameter("type", filter.selected)
                is GenreFilter -> url.addQueryParameter("genre", filter.selected)
                is SortFilter -> url.addQueryParameter("sortBy", filter.selected)
                is MinChapterFilter -> url.addQueryParameter("min", filter.selected)
                else -> {}
            }
        }

        return if (query.isNotEmpty()) {
            GET(url.build(), chapterHeaders())
        } else {
            GET(url.build(), headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val isApiSearch = response.request.url.host == "v2.softdevices.my.id"
        return if (isApiSearch) {
            val dto = response.parseAs<LibDataDto>()
            val mangas = dto.data.map { manga ->
                SManga.create().apply {
                    setUrlWithoutDomain("/komik/${manga.title_slug}")
                    title = manga.title
                    thumbnail_url = coverUrl(manga.gambar)
                }
            }
            MangasPage(mangas, dto.page < dto.maxPage)
        } else {
            val dto = response.parseAs<LibraryDto>()
            val mangas = dto.pageProps.libData.data.map { manga ->
                SManga.create().apply {
                    setUrlWithoutDomain("/komik/${manga.title_slug}")
                    title = manga.title
                    thumbnail_url = coverUrl(manga.gambar)
                }
            }
            MangasPage(mangas, dto.pageProps.libData.page < dto.pageProps.libData.maxPage)
        }
    }

    // ======================== Details ========================
    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl/_next/data/$buildId${manga.url}.json", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = response.parseAs<DetailsDto>()
        val manga = dto.pageProps.data
        val slug = response.request.url.pathSegments.lastOrNull()!!.removeSuffix(".json")
        return SManga.create().apply {
            setUrlWithoutDomain("/komik/$slug")
            title = manga.title
            author = manga.author
            description = manga.sinopsis
            genre = manga.Genre?.joinToString()
            status = when (manga.status?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "tamat" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = coverUrl(manga.gambar)
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    // ======================== Chapters ========================
    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/komik/")
        return GET("$CHAPTER_URL/komik/$slug/chapter?limit=9999999", chapterHeaders())
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val dto = response.parseAs<ChapterListDto>()
        val slug = response.request.url.pathSegments[1]
        return dto.chapter.map { chapter ->
            val chapterNum = chapter.chapter.toFloatOrNull() ?: -1f
            val displayNum = formatChapterDisplay(chapter.chapter)
            SChapter.create().apply {
                url = "/komik/$slug/chapter/${chapter.chapter}"
                name = "Chapter $displayNum"
                chapter_number = chapterNum
            }
        }.sortedByDescending { it.chapter_number }
    }

    private fun formatChapterDisplay(chapterStr: String): String {
        val floatVal = chapterStr.toFloatOrNull() ?: return chapterStr
        return if (floatVal == floatVal.toLong().toFloat()) {
            floatVal.toLong().toString()
        } else {
            floatVal.toString().trimEnd('0').trimEnd('.')
        }
    }

    // ======================== Pages ========================
    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl/_next/data/$buildId${chapter.url}.json", headers)

    override fun pageListParse(response: Response): List<Page> {
        val dto = response.parseAs<ChapterPageDto>()
        val chapterData = dto.pageProps.data.data

        val slug = response.request.url.pathSegments[3]
        val chapterNum = response.request.url.pathSegments.lastOrNull()
            ?.removeSuffix(".json")
            ?: throw Exception("Could not get chapter number")

        val imageResponse = client.newCall(
            GET(
                "$CHAPTER_URL/komik/$slug/chapter/$chapterNum/img/${chapterData.id}",
                chapterHeaders(),
            ),
        ).execute()

        val imageDto = imageResponse.parseAs<ChapterPageImagesDto>()
        val proxy = prefImageProxy

        return imageDto.imageSrc.mapIndexed { i, img ->
            val originalUrl = "$IMAGE_URL/$img"
            val finalUrl = if (proxy.isBlank()) originalUrl else "$proxy$originalUrl"
            Page(i, imageUrl = finalUrl)
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    // ======================== Filters ========================
    override fun getFilterList() = FilterList(
        Filter.Header("Filter tidak bisa digabungkan dengan pencarian teks."),
        Filter.Separator(),
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
        MinChapterFilter(),
    )

    // ======================== Preference Screen ========================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Domain Softkomik"
            summary = prefDomain
            dialogTitle = "Domain Softkomik"
            dialogMessage = "Masukkan domain baru (contoh: https://softkomik.com)\nKosongkan untuk kembali ke default."
            setDefaultValue(DEFAULT_DOMAIN)
            setOnPreferenceChangeListener { pref, newValue ->
                val value = (newValue as? String)?.trimEnd('/') ?: DEFAULT_DOMAIN
                pref.summary = value.ifEmpty { DEFAULT_DOMAIN }
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
}