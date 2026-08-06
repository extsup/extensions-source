package eu.kanade.tachiyomi.extension.id.komikcastbeta

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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class KomikCastBeta :
    HttpSource(),
    ConfigurableSource {

    private val apiUrl = "https://be.komikcast.cc"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, "https://komikcast.cz")!!

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json")
        .add("Accept-language", "en-US,en;q=0.9,id;q=0.8")

    // ============================== Popular ===============================

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

    // ============================== Latest ================================

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

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("includeMeta", "true")
            .addQueryParameter("take", "12")
            .addQueryParameter("page", page.toString())
        if (query.isNotEmpty()) {
            url.addQueryParameter("filter", "title=like=\"$query\",nativeTitle=like=\"$query\"")
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseSeriesListResponse(response)

    override fun getFilterList(): FilterList = FilterList()

    // ============================== Details ===============================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/series/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val root = response.parseAs<JsonObject>()
        val data = root["data"]!!.jsonObject["data"]!!.jsonObject
        return SManga.create().apply {
            title = data["title"]?.jsonPrimitive?.content.orEmpty()
            thumbnail_url = data["coverImage"]?.jsonPrimitive?.content
            val synopsis = data["synopsis"]?.jsonPrimitive?.content.orEmpty()
            val altTitle = data["nativeTitle"]?.jsonPrimitive?.content
            description = if (!altTitle.isNullOrBlank()) "$synopsis\n\nAlt Title: $altTitle" else synopsis
            status = when (data["status"]?.jsonPrimitive?.content?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            genre = data["genres"]?.jsonArray
                ?.joinToString { it.jsonObject["data"]!!.jsonObject["name"]!!.jsonPrimitive.content }
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
            val index = obj["data"]!!.jsonObject["index"]?.jsonPrimitive?.intOrNull ?: 0
            SChapter.create().apply {
                url = "$slug/chapters/$index"
                name = "Chapter $index"
                date_upload = dateFormat.tryParse(obj["createdAt"]?.jsonPrimitive?.content)
            }
        }
    }

    // ============================== Pages =================================

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split("/chapters/")
        return "$baseUrl/series/${parts[0]}/chapter/${parts[1]}"
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/chapters/")
        return GET("$apiUrl/series/${parts[0]}/chapters/${parts[1]}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val root = response.parseAs<JsonObject>()
        val images = root["data"]!!.jsonObject["data"]!!.jsonObject["images"]?.jsonArray
            ?: return emptyList()
        return images.mapIndexed { index, el ->
            Page(index = index, imageUrl = el.jsonPrimitive.content)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    // ============================== Preferences ===========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Domain URL"
            summary = "Sekarang: $baseUrl"
            setDefaultValue("https://v3.komikcast.fit")
            dialogTitle = "Masukkan domain"
            dialogMessage = "Masukkan Domain Baru"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_DOMAIN_KEY, (newValue as String).trimEnd('/')).apply()
                true
            }
        }.also(screen::addPreference)
    }

    // ============================== Helper ================================

    private fun parseSeriesListResponse(response: Response): MangasPage {
        val root = response.parseAs<JsonObject>()
        val data = root["data"]!!.jsonArray
        val meta = root["meta"]?.jsonObject
        val mangas = data.map { el ->
            val obj = el.jsonObject["data"]!!.jsonObject
            SManga.create().apply {
                title = obj["title"]?.jsonPrimitive?.content.orEmpty()
                thumbnail_url = obj["coverImage"]?.jsonPrimitive?.content
                url = obj["slug"]?.jsonPrimitive?.content.orEmpty()
            }
        }
        val currentPage = meta?.get("page")?.jsonPrimitive?.intOrNull ?: 0
        val lastPage = meta?.get("lastPage")?.jsonPrimitive?.intOrNull ?: 0
        return MangasPage(mangas, currentPage < lastPage)
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain"
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ENGLISH)
    }
}
