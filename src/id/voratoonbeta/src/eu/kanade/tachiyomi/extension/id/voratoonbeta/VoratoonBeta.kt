package eu.kanade.tachiyomi.extension.id.voratoonbeta

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Source
abstract class VoratoonBeta :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val apiUrl = "https://api.voratoon.com"

    private val preferences by getPreferencesLazy()

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, DEFAULT_DOMAIN)!!

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(5)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json")
        .add("Accept-Language", "en-US,en;q=0.9,id;q=0.8")

    // ============================== Helper Extensions ==============================

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()

    private fun JsonObject.strOrNull(key: String) = this[key]?.jsonPrimitive?.content

    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

    private fun JsonObject.toSManga() = SManga.create().apply {
        title = str("title")
        thumbnail_url = strOrNull("coverImage") ?: strOrNull("thumbnail")
        url = str("slug")
    }

    private fun buildSeriesUrl(page: Int, sort: String) = "$apiUrl/series".toHttpUrl().newBuilder()
        .addQueryParameter("take", PAGE_SIZE.toString())
        .addQueryParameter("page", page.toString())
        .addQueryParameter("sort", sort)
        .addQueryParameter("sortOrder", "desc")
        .build()

    private fun parseSeriesResponse(response: Response): MangasPage {
        val root = response.parseAs<JsonObject>()
        val data = root["data"]!!.jsonArray
        val mangas = data.map { it.jsonObject.toSManga() }
        val hasMore = mangas.size >= PAGE_SIZE
        return MangasPage(mangas, hasMore)
    }

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = GET(buildSeriesUrl(page, "views").toString(), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseSeriesResponse(response)

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request = GET(buildSeriesUrl(page, "latest").toString(), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseSeriesResponse(response)

    // ============================== Search ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("take", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "latest")
            .addQueryParameter("sortOrder", "desc")
        if (query.isNotBlank()) url.addQueryParameter("title", query)
        return GET(url.build().toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseSeriesResponse(response)

    override fun getFilterList(): FilterList = FilterList()

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga) = "$baseUrl/series/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/series/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val item = response.parseAs<JsonObject>()["item"]!!.jsonObject
        return SManga.create().apply {
            title = item.str("title")
            thumbnail_url = item.strOrNull("coverImage") ?: item.strOrNull("thumbnail")
            description = item.str("synopsis")
            status = when (item.str("status").lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            genre = item["genres"]?.jsonArray
                ?.joinToString { it.jsonObject.str("name") }
                .orEmpty()
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl/series/${manga.url}/chapters", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val root = response.parseAs<JsonObject>()
        val data = root["data"]!!.jsonArray
        val slug = response.request.url.pathSegments[1]
        return data.map { el ->
            val obj = el.jsonObject
            val index = obj.int("index")
            SChapter.create().apply {
                url = "$slug/$index"
                name = "Chapter $index"
                date_upload = 0L
            }
        }
    }

    // ============================== Pages ==============================

    override fun getChapterUrl(chapter: SChapter): String {
        val (slug, index) = chapter.url.split("/", limit = 2)
        return "$baseUrl/manga/$slug/chapter-$index"
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val (slug, index) = chapter.url.split("/", limit = 2)
        return GET("$apiUrl/series/$slug/chapters/$index", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val root = response.parseAs<JsonObject>()
        val images = root["item"]!!.jsonObject["info"]!!.jsonObject["images"]?.jsonArray
            ?: return emptyList()
        return images.mapIndexed { index, el ->
            Page(index = index, imageUrl = el.jsonPrimitive.content)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Domain URL"
            summary = "Sekarang: $baseUrl"
            setDefaultValue(DEFAULT_DOMAIN)
            dialogTitle = "Masukkan domain"
            dialogMessage = "Masukkan Domain Baru"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit()
                    .putString(PREF_DOMAIN_KEY, (newValue as String).trimEnd('/'))
                    .apply()
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain"
        private const val DEFAULT_DOMAIN = "https://voratoon.com"
        private const val PAGE_SIZE = 30
    }
}
