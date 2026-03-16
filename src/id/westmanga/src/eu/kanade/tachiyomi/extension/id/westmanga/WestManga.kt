package eu.kanade.tachiyomi.extension.id.westmanga

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WestManga :
    HttpSource(),
    ConfigurableSource {

    override val name = "West Manga"
    override val lang = "id"
    override val id = 8883916630998758688
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN, DEFAULT_BASE_URL)!!.trimEnd('/')

    private val apiUrl: String
        get() = preferences.getString(PREF_API_URL, DEFAULT_API_URL)!!.trimEnd('/')

    private val imageResizeProxy: String
        get() = preferences.getString(PREF_RESIZE_PROXY, "")!!.trimEnd('/')

    // ──────────────────────────────────────────────
    //  PreferenceScreen
    // ──────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN
            title = "Override Base URL"
            summary = "Domain utama WestManga (default: $DEFAULT_BASE_URL)\nNilai saat ini: ${baseUrl}"
            setDefaultValue(DEFAULT_BASE_URL)
            dialogTitle = "Base URL"
            dialogMessage = "Contoh: https://westmanga.tv"
            setOnPreferenceChangeListener { _, newValue ->
                val v = (newValue as String).trim()
                preferences.edit().putString(PREF_DOMAIN, v.ifBlank { DEFAULT_BASE_URL }).apply()
                summary = "Domain utama WestManga (default: $DEFAULT_BASE_URL)\nNilai saat ini: $v"
                true
            }
        }.also { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = PREF_API_URL
            title = "Override API URL"
            summary = "Domain API WestManga (default: $DEFAULT_API_URL)\nNilai saat ini: ${apiUrl}"
            setDefaultValue(DEFAULT_API_URL)
            dialogTitle = "API URL"
            dialogMessage = "Contoh: https://data.westmanga.tv"
            setOnPreferenceChangeListener { _, newValue ->
                val v = (newValue as String).trim()
                preferences.edit().putString(PREF_API_URL, v.ifBlank { DEFAULT_API_URL }).apply()
                summary = "Domain API WestManga (default: $DEFAULT_API_URL)\nNilai saat ini: $v"
                true
            }
        }.also { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = PREF_RESIZE_PROXY
            title = "Image Resize Proxy (opsional)"
            summary = "URL proxy resize gambar untuk halaman chapter.\n" +
                "Kosongkan untuk memakai URL asli.\n" +
                "Nilai saat ini: ${imageResizeProxy.ifBlank { "(tidak aktif)" }}"
            setDefaultValue("")
            dialogTitle = "Image Resize Proxy"
            dialogMessage =
                "Masukkan base URL proxy kamu. URL gambar asli akan diteruskan sebagai parameter 'url'.\n" +
                    "Contoh: https://myproxy.example.com/resize"
            setOnPreferenceChangeListener { _, newValue ->
                val v = (newValue as String).trim()
                preferences.edit().putString(PREF_RESIZE_PROXY, v).apply()
                summary = "URL proxy resize gambar untuk halaman chapter.\n" +
                    "Kosongkan untuk memakai URL asli.\n" +
                    "Nilai saat ini: ${v.ifBlank { "(tidak aktif)" }}"
                true
            }
        }.also { screen.addPreference(it) }
    }

    // ──────────────────────────────────────────────
    //  Helper: proxy image URL
    // ──────────────────────────────────────────────

    private fun proxiedImageUrl(original: String): String {
        val proxy = imageResizeProxy
        if (proxy.isBlank()) return original
        val base = if (proxy.endsWith("/")) proxy else "$proxy/"
        return "$base$original"
    }

    private fun coverUrl(original: String): String {
        if (original.isBlank()) return original
        return "https://wsrv.nl/?w=110&h=150&url=$original"
    }
    // ──────────────────────────────────────────────
    //  Headers
    // ──────────────────────────────────────────────

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ──────────────────────────────────────────────
    //  Popular / Latest / Search
    // ──────────────────────────────────────────────

    override fun popularMangaRequest(page: Int) = searchMangaRequest(page, "", SortFilter.popular)

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int) = searchMangaRequest(page, "", SortFilter.latest)

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addPathSegment("contents")
            if (query.isNotBlank()) addQueryParameter("q", query)
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", "20")
            addQueryParameter("type", "Comic")
            filters.filterIsInstance<UrlFilter>().forEach { it.addToUrl(this) }
        }.build()

        return apiRequest(url)
    }

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        StatusFilter(),
        CountryFilter(),
        ColorFilter(),
        GenreFilter(),
    )

    override fun searchMangaParse(response: Response): MangasPage {
        val data = response.parseAs<PaginatedData<BrowseManga>>()

        val entries = data.data.map {
            SManga.create().apply {
                setUrlWithoutDomain(
                    baseUrl.toHttpUrl().newBuilder()
                        .addPathSegment("manga")
                        .addPathSegment(it.slug)
                        .addPathSegment("")
                        .toString(),
                )
                title = it.title
                thumbnail_url = coverUrl(it.cover.orEmpty())
            }
        }

        return MangasPage(entries, data.paginator.hasNextPage())
    }

    // ──────────────────────────────────────────────
    //  Manga Details
    // ──────────────────────────────────────────────

    override fun mangaDetailsRequest(manga: SManga): Request {
        val path = "$baseUrl${manga.url}".toHttpUrl().pathSegments
        assert(path.size == 3) { "Migrate from $name to $name" }
        val slug = path[1]

        val url = apiUrl.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("comic")
            .addPathSegment(slug)
            .build()

        return apiRequest(url)
    }

    override fun getMangaUrl(manga: SManga): String {
        val slug = "$baseUrl${manga.url}".toHttpUrl().pathSegments[1]
        return baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("comic")
            .addPathSegment(slug)
            .build()
            .toString()
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = response.parseAs<Data<Manga>>().data

        return SManga.create().apply {
            setUrlWithoutDomain(
                baseUrl.toHttpUrl().newBuilder()
                    .addPathSegment("manga")
                    .addPathSegment(data.slug)
                    .addPathSegment("")
                    .toString(),
            )
            title = data.title
            thumbnail_url = coverUrl(data.cover.orEmpty())
            author = data.author
            status = when (data.status) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            genre = buildList {
                when (data.country) {
                    "JP" -> add("Manga")
                    "CN" -> add("Manhua")
                    "KR" -> add("Manhwa")
                }
                if (data.color == true) add("Colored")
                data.genres.forEach { add(it.name) }
            }.joinToString()
            description = buildString {
                data.synopsis?.let {
                    append(Jsoup.parseBodyFragment(it).wholeText().trim())
                }
                data.alternativeName?.let {
                    append("\n\nAlternative Name: ")
                    append(it.trim())
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Chapter List
    // ──────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<Data<Manga>>().data

        return data.chapters.map {
            SChapter.create().apply {
                setUrlWithoutDomain(
                    baseUrl.toHttpUrl().newBuilder()
                        .addPathSegment(it.slug)
                        .addPathSegment("")
                        .toString(),
                )
                name = "Chapter ${it.number}"
                date_upload = it.updatedAt.time * 1000
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Page List
    // ──────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request {
        val path = "$baseUrl${chapter.url}".toHttpUrl().pathSegments
        assert(path.size == 2) { "Refresh Chapter List" }
        val slug = path[0]

        val url = apiUrl.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("v")
            .addPathSegment(slug)
            .build()

        return apiRequest(url)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val slug = "$baseUrl${chapter.url}".toHttpUrl().pathSegments[0]
        return baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("view")
            .addPathSegment(slug)
            .toString()
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<Data<ImageList>>().data

        return data.images.mapIndexed { idx, img ->
            Page(idx, imageUrl = proxiedImageUrl(img))
        }
    }

    // ──────────────────────────────────────────────
    //  API Request (HMAC signing)
    // ──────────────────────────────────────────────

    private fun apiRequest(url: HttpUrl): Request {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val message = "wm-api-request"
        val key = timestamp + "GET" + url.encodedPath + ACCESS_KEY + SECRET_KEY
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKeySpec)
        val hash = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        val signature = hash.joinToString("") { "%02x".format(it) }

        val apiHeaders = headersBuilder()
            .set("x-wm-request-time", timestamp)
            .set("x-wm-accses-key", ACCESS_KEY)
            .set("x-wm-request-signature", signature)
            .build()

        return GET(url, apiHeaders)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private const val DEFAULT_BASE_URL = "https://westmanga.tv"
        private const val DEFAULT_API_URL = "https://data.westmanga.tv"

        private const val PREF_DOMAIN = "pref_base_url"
        private const val PREF_API_URL = "pref_api_url"
        private const val PREF_RESIZE_PROXY = "pref_resize_proxy"
    }
}

private const val ACCESS_KEY = "WM_WEB_FRONT_END"
private const val SECRET_KEY = "xxxoidj"
