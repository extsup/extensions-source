package eu.kanade.tachiyomi.extension.id.ikirubeta

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random

@Source
abstract class IkiruBeta : HttpSource() {

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(4)
        .build()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    private fun tryParseDate(str: String?): Long {
        if (str.isNullOrBlank()) return 0L
        return try {
            dateFormat.parse(str)!!.time
        } catch (_: Exception) {
            0L
        }
    }

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int) = buildRequest(page, orderby = "popularity")

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int) = buildRequest(page, orderby = "updated")

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    // ============================== Search ================================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = if (query.startsWith("https://")) {
        deepLink(query)
    } else {
        super.fetchSearchManga(page, query, filters)
    }

    private fun buildRequest(page: Int, query: String = "", orderby: String = "popularity"): Request {
        val body = MultipartBody.Builder().apply {
            setType(MultipartBody.FORM)
            addFormDataPart("nonce", getNonce())
            addFormDataPart("page", page.toString())
            addFormDataPart("orderby", orderby)
            addFormDataPart("query", query.trim())
        }.build()
        return POST("$baseUrl/wp-admin/admin-ajax.php?action=advanced_search", headers, body)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = buildRequest(page, query)

    override fun searchMangaParse(response: Response): MangasPage {
        val document = Jsoup.parseBodyFragment(response.body!!.string(), baseUrl)
        val slugs = document.select("a[href*=/manga/]:has(> img)").map {
            it.absUrl("href").toHttpUrl().pathSegments[1]
        }.ifEmpty {
            return MangasPage(emptyList(), false)
        }

        val url = "$baseUrl/wp-json/wp/v2/manga".toHttpUrl().newBuilder().apply {
            slugs.forEach { addQueryParameter("slug[]", it) }
            addQueryParameter("per_page", "${slugs.size + 1}")
            addQueryParameter("_embed", null)
        }.build()

        val mangas = client.newCall(GET(url, headers)).execute()
            .parseAs<JsonArray>()
            .map { it.jsonObject }
            .filterNot { obj ->
                obj.getTerms("type").contains("Novel")
            }
            .associateBy { it["slug"]!!.jsonPrimitive.content }
            .let { details ->
                slugs.mapNotNull { slug -> details[slug]?.toSManga() }
            }

        val hasNextPage = document.selectFirst("polyline[points='9 18 15 12 9 6']") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList() = FilterList()

    // ============================== Details ===============================

    override fun getMangaUrl(manga: SManga): String {
        val slug = if (manga.url.startsWith("{")) {
            org.json.JSONObject(manga.url).getString("slug")
        } else {
            "$baseUrl${manga.url}".toHttpUrl().pathSegments[1]
        }
        return "$baseUrl/manga/$slug/"
    }

    private val descriptionIdRegex = Regex("""ID: (\d+)""")

    private fun getMangaId(manga: SManga): String = if (manga.url.startsWith("{")) {
        org.json.JSONObject(manga.url).getInt("id").toString()
    } else if (descriptionIdRegex.containsMatchIn(manga.description?.trim().orEmpty())) {
        descriptionIdRegex.find(manga.description!!.trim())!!.groupValues[1]
    } else {
        client.newCall(GET(getMangaUrl(manga), headers)).execute().asJsoup()
            .selectFirst("#gallery-list")!!.attr("hx-get")
            .substringAfter("manga_id=").substringBefore("&")
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = getMangaId(manga)
        val appendId = !manga.url.startsWith("{")
        return GET("$baseUrl/wp-json/wp/v2/manga/$id?_embed#$appendId", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val appendId = response.request.url.fragment == "true"
        return response.parseAs<JsonObject>().toSManga(appendId)
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        val id = getMangaId(manga)
        val url = "$baseUrl/wp-admin/admin-ajax.php".toHttpUrl().newBuilder()
            .addQueryParameter("manga_id", id)
            .addQueryParameter("page", "${Random.nextInt(99, 9999)}")
            .addQueryParameter("action", "chapter_list")
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parseBodyFragment(response.body!!.string(), baseUrl)
        return document.select("div a:has(time)").map {
            SChapter.create().apply {
                setUrlWithoutDomain(it.absUrl("href"))
                name = it.selectFirst("span")!!.ownText()
                date_upload = tryParseDate(it.selectFirst("time")?.attr("datetime"))
            }
        }
    }

    // ============================== Pages =================================

    override fun pageListParse(response: Response): List<Page> = response.asJsoup().select("main .relative section > img").mapIndexed { idx, img ->
        Page(idx, imageUrl = img.absUrl("src"))
    }

    override fun imageUrlParse(response: Response) = ""

    // ============================== Helpers ===============================

    private var nonce: String? = null

    @Synchronized
    private fun getNonce(): String {
        if (nonce == null) {
            val url = "$baseUrl/wp-admin/admin-ajax.php?type=search_form&action=get_nonce"
            Jsoup.parseBodyFragment(client.newCall(GET(url, headers)).execute().body!!.string())
                .selectFirst("input[name=search_nonce]")
                ?.attr("value")
                ?.takeIf { it.isNotBlank() }
                ?.also { nonce = it }
        }
        return nonce ?: throw Exception("Unable to get nonce")
    }

    private fun deepLink(url: String): Observable<MangasPage> {
        val httpUrl = url.toHttpUrl()
        if (httpUrl.host == baseUrl.toHttpUrl().host &&
            httpUrl.pathSegments.size >= 2 &&
            httpUrl.pathSegments[0] == "manga"
        ) {
            val slug = httpUrl.pathSegments[1]
            val reqUrl = "$baseUrl/wp-json/wp/v2/manga".toHttpUrl().newBuilder()
                .addQueryParameter("slug[]", slug)
                .addQueryParameter("_embed", null)
                .build()
            return client.newCall(GET(reqUrl, headers))
                .asObservableSuccess()
                .map { response ->
                    val obj = response.parseAs<JsonArray>()[0].jsonObject
                    if (obj.getTerms("type").contains("Novel")) throw Exception("Novels are not supported")
                    MangasPage(listOf(obj.toSManga()), false)
                }
        }
        return Observable.error(Exception("Unsupported url"))
    }

    // ============================== JsonObject extensions =================

    private fun JsonObject.getTerms(type: String): List<String> {
        val embedded = this["_embedded"]?.jsonObject ?: return emptyList()
        val terms = embedded["wp:term"]?.jsonArray ?: return emptyList()
        return terms.map { it.jsonArray }
            .firstOrNull { arr -> arr.firstOrNull()?.jsonObject?.get("taxonomy")?.jsonPrimitive?.contentOrNull == type }
            ?.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            ?: emptyList()
    }

    private fun JsonObject.toSManga(appendId: Boolean = false): SManga {
        val id = this["id"]!!.jsonPrimitive.intOrNull ?: 0
        val slug = this["slug"]!!.jsonPrimitive.content
        val title = this["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNull.orEmpty()
        val content = this["content"]?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNull.orEmpty()
        val embedded = this["_embedded"]?.jsonObject

        return SManga.create().apply {
            url = """{"id":$id,"slug":"$slug"}"""
            this.title = Parser.unescapeEntities(title, false)
            description = buildString {
                append(Jsoup.parseBodyFragment(content).wholeText())
                if (appendId) append("ID: $id")
            }
            thumbnail_url = embedded?.get("wp:featuredmedia")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("source_url")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            author = getTerms("series-author").joinToString()
            artist = getTerms("artist").joinToString()
            genre = (getTerms("genre") + getTerms("type")).toSet().joinToString()
            status = with(getTerms("status")) {
                when {
                    contains("Ongoing") -> SManga.ONGOING
                    contains("Completed") -> SManga.COMPLETED
                    contains("Cancelled") -> SManga.CANCELLED
                    contains("On Hiatus") -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
            }
            initialized = true
        }
    }
}
