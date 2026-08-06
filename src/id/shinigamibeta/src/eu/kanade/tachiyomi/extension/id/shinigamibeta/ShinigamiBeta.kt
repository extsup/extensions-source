package eu.kanade.tachiyomi.extension.id.shinigamibeta

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
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class ShinigamiBeta :
    HttpSource(),
    ConfigurableSource {

    private val apiUrl = "https://api.shngm.io"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    private val resizeUrl: String
        get() = preferences.getString(PREF_RESIZE_URL_KEY, "")!!

    private val apiHeaders: Headers by lazy { apiHeadersBuilder().build() }

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val req = chain.request()
            val headers = req.headers.newBuilder()
                .removeAll("X-Requested-With")
                .build()
            chain.proceed(req.newBuilder().headers(headers).build())
        }
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("X-Requested-With", randomString((10..20).random()))

    private fun randomString(length: Int) = buildString {
        val pool = ('a'..'z') + ('A'..'Z')
        repeat(length) { append(pool.random()) }
    }

    private fun apiHeadersBuilder(): Headers.Builder = headersBuilder()
        .add("Accept", "application/json")
        .add("DNT", "1")
        .add("Origin", baseUrl)
        .add("Sec-GPC", "1")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/v1/manga/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "30")
            .addQueryParameter("sort", "popularity")
            .build()
        return GET(url, apiHeaders)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val root = response.parseAs<JsonObject>()
        val data = root["data"]!!.jsonArray
        val meta = root["meta"]!!.jsonObject
        val mangas = data.mapNotNull { el ->
            val obj = el.jsonObject
            val genres = obj["taxonomy"]?.jsonObject
                ?.get("Genre")?.jsonArray
                ?.map { it.jsonObject["slug"]!!.jsonPrimitive.content.lowercase() }
                ?: emptyList()
            if (genres.any { it in BLACKLISTED_GENRES }) return@mapNotNull null
            SManga.create().apply {
                title = obj["title"]?.jsonPrimitive?.content ?: obj["manga_id"]!!.jsonPrimitive.content
                thumbnail_url = obj["cover_image_url"]?.jsonPrimitive?.content
                url = obj["manga_id"]!!.jsonPrimitive.content
            }
        }
        val page = meta["page"]?.jsonPrimitive?.intOrNull ?: 1
        val totalPage = meta["total_page"]?.jsonPrimitive?.intOrNull
        val hasNext = totalPage?.let { page < it } ?: false
        return MangasPage(mangas, hasNext)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/v1/manga/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "30")
            .addQueryParameter("sort", "latest")
            .build()
        return GET(url, apiHeaders)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/v1/manga/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "30")
        if (query.isNotEmpty()) url.addQueryParameter("q", query)
        return GET(url.build(), apiHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun getFilterList(): FilterList = FilterList()

    // ============================== Details ===============================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request {
        if (manga.url.startsWith("/series/")) {
            throw Exception("Migrate dari $name ke $name (ekstensi yang sama)")
        }
        return GET("$apiUrl/v1/manga/detail/${manga.url}", apiHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val root = response.parseAs<JsonObject>()
        val dto = root["data"]!!.jsonObject
        val taxonomy = dto["taxonomy"]?.jsonObject ?: JsonObject(emptyMap())

        fun taxNames(key: String) = taxonomy[key]?.jsonArray
            ?.joinToString { it.jsonObject["name"]!!.jsonPrimitive.content }
            .orEmpty()

        return SManga.create().apply {
            title = dto["title"]?.jsonPrimitive?.content.orEmpty()
            author = taxNames("Author")
            artist = taxNames("Artist")
            status = when (dto["status"]?.jsonPrimitive?.intOrNull) {
                1 -> SManga.ONGOING
                2 -> SManga.COMPLETED
                3 -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            val altTitle = dto["alternative_title"]?.jsonPrimitive?.content
            description = buildString {
                append(
                    dto["description"]?.jsonPrimitive?.content
                        ?.replace("&#x20;", "")
                        ?.replace(Regex("""[\\*]+"""), "")
                        ?.replace(Regex("<([^>]+)>"), "$1")
                        ?.replace(Regex("""\n{2,}"""), "\n\n")
                        ?.trim()
                        .orEmpty(),
                )
                if (!altTitle.isNullOrBlank()) append("\n\nAlt title: $altTitle")
            }
            val genres = taxNames("Genre")
            val type = taxNames("Format")
            genre = listOf(genres, type).filter { it.isNotBlank() }.joinToString()
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl/v1/chapter/${manga.url}/list?page_size=3000", apiHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val root = response.parseAs<JsonObject>()
        val data = root["data"]!!.jsonArray
        return data.map { el ->
            val obj = el.jsonObject
            SChapter.create().apply {
                date_upload = dateFormat.tryParse(obj["release_date"]?.jsonPrimitive?.content)
                val num = obj["chapter_number"]?.jsonPrimitive?.doubleOrNull
                    ?.toString()?.replace(".0", "") ?: ""
                val title = obj["chapter_title"]?.jsonPrimitive?.content
                name = "Chapter $num ${title ?: ""}".trim()
                url = obj["chapter_id"]!!.jsonPrimitive.content
            }
        }
    }

    // ============================== Pages =================================

    override fun pageListRequest(chapter: SChapter): Request {
        if (chapter.url.startsWith("/series/")) {
            throw Exception("Migrate dari $name ke $name (ekstensi yang sama)")
        }
        return GET("$apiUrl/v1/chapter/detail/${chapter.url}", apiHeaders)
    }

    override fun pageListParse(response: Response): List<Page> {
        val root = response.parseAs<JsonObject>()
        val data = root["data"]!!.jsonObject
        val baseImgUrl = data["base_url"]!!.jsonPrimitive.content
        val chapter = data["chapter"]!!.jsonObject
        val path = chapter["path"]!!.jsonPrimitive.content
        val pages = chapter["data"]!!.jsonArray

        return pages
            .map { it.jsonPrimitive.content }
            .filter { !it.startsWith("99") }
            .mapIndexed { index, imageName ->
                val originalUrl = "$baseImgUrl$path$imageName"
                val finalUrl = resizeUrl.takeIf { it.isNotBlank() }?.let { "$it$originalUrl" } ?: originalUrl
                Page(index = index, imageUrl = finalUrl)
            }
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .add("DNT", "1")
            .add("Referer", "$baseUrl/")
            .add("Sec-GPC", "1")
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    // ============================== Preferences ===========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Domain URL"
            summary = "Sekarang: $baseUrl"
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            dialogTitle = "Masukkan domain"
            dialogMessage = "Contoh: https://11.shinigami.asia"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_DOMAIN_KEY, (newValue as String).trimEnd('/')).apply()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_RESIZE_URL_KEY
            title = "URL Resize"
            summary = "Prefix URL resize. Kosongkan untuk nonaktifkan."
            dialogTitle = "Masukkan prefix URL resize"
            dialogMessage = "Contoh: https://resize.example.com?url="
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_RESIZE_URL_KEY, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)
    }

    // ============================== Companion =============================

    companion object {
        private val BLACKLISTED_GENRES = setOf(
            "josei-genre",
            "smut",
            "gender-bender",
            "boys-love",
            "bl",
            "yaoi",
            "yuri",
            "girls-love",
            "shounen-ai",
            "shoujo-ai",
            "shoujo",
        )

        private const val PREF_DOMAIN_KEY = "pref_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://09.shinigami.asia"
        private const val PREF_RESIZE_URL_KEY = "pref_resize_url"

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH)
    }
}
