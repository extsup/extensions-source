package eu.kanade.tachiyomi.extension.id.keikomik

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class KeiKomik : HttpSource() {

    override val name = "KeiKomik"
    override val baseUrl = "https://keikomik.web.id"
    override val lang = "id"
    override val supportsLatest = true

    // ── Firestore config ──────────────────────────────────────
    private val projectId = "komikapp-677a0"
    private val apiKey = "AIzaSyAtpMBExnqiiZQabVGWuKMWoogtYc3kAAc"
    private val collection = "KomikApp"
    private val fsBase = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"

    private val json by lazy { Json { ignoreUnknownKeys = true } }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).also {
        it.timeZone = TimeZone.getTimeZone("UTC")
    }

    // Firestore REST API — tidak perlu cloudflareClient
    // Rate limit 1 req/detik untuk hindari 429 quota Firestore
    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimitHost(
            "firestore.googleapis.com".toHttpUrl(),
            1,
            1,
            TimeUnit.SECONDS,
        )
        .build()

    // ── URL builder ───────────────────────────────────────────
    private fun fsUrl(path: String): String =
        "$fsBase/$path".toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey)
            .build()
            .toString()

    // ── Konversi Firestore document → SManga ──────────────────
    private fun docToManga(doc: kotlinx.serialization.json.JsonObject): SManga {
        val docName = doc["name"]?.jsonPrimitive?.content ?: ""
        val fields = doc["fields"]?.jsonObject ?: return SManga.create()

        fun str(key: String) = fields[key]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: ""

        return SManga.create().apply {
            url = docName.substringAfterLast("/")
            title = str("name")
            thumbnail_url = str("image")
            author = str("author")
            genre = fields["genre"]?.jsonObject?.get("arrayValue")?.jsonObject
                ?.get("values")?.jsonArray
                ?.joinToString { it.jsonObject["stringValue"]?.jsonPrimitive?.content ?: "" }
            status = when (str("status")) {
                "Ongoing" -> SManga.ONGOING
                "Completed" -> SManga.COMPLETED
                "Hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            description = buildString {
                val desc = str("description")
                if (desc.isNotEmpty()) appendLine(desc)
                val rate = fields["rate"]?.jsonObject?.let {
                    it["doubleValue"]?.jsonPrimitive?.content
                        ?: it["integerValue"]?.jsonPrimitive?.content
                }
                if (rate != null) appendLine("⭐ $rate")
                val type = str("type")
                if (type.isNotEmpty()) appendLine("Type: $type")
                val rilis = str("rilis")
                if (rilis.isNotEmpty()) appendLine("Rilis: $rilis")
            }.trim()
        }
    }

    // ── runQuery body ─────────────────────────────────────────
    private fun queryBody(offset: Int) = """
        {
          "structuredQuery": {
            "from": [{"collectionId": "$collection"}],
            "orderBy": [{"field": {"fieldPath": "UpdateAt"}, "direction": "DESCENDING"}],
            "limit": $PAGE_SIZE,
            "offset": $offset
          }
        }
    """.trimIndent()

    private fun queryRequest(offset: Int): Request =
        Request.Builder()
            .url("$fsBase:runQuery?key=$apiKey")
            .post(queryBody(offset).toRequestBody("application/json".toMediaType()))
            .build()

    private fun queryParse(response: Response): MangasPage {
        val arr = json.parseToJsonElement(response.body.string()).jsonArray
        val mangas = arr.mapNotNull { item ->
            item.jsonObject["document"]?.jsonObject?.let { docToManga(it) }
                ?.takeIf { it.title.isNotEmpty() }
        }
        return MangasPage(mangas, mangas.size == PAGE_SIZE)
    }

    // ── Popular ───────────────────────────────────────────────
    override fun popularMangaRequest(page: Int): Request =
        queryRequest((page - 1) * PAGE_SIZE)

    override fun popularMangaParse(response: Response): MangasPage =
        queryParse(response)

    // ── Latest Updates ────────────────────────────────────────
    override fun latestUpdatesRequest(page: Int): Request =
        queryRequest((page - 1) * PAGE_SIZE)

    override fun latestUpdatesParse(response: Response): MangasPage =
        queryParse(response)

    // ── Search ────────────────────────────────────────────────
    // Firestore tidak support full-text → fetch semua, filter client-side.
    // Query string dipass lewat custom header.
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        queryRequest(0)
            .newBuilder()
            .addHeader("X-Search-Query", query)
            .build()

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.header("X-Search-Query") ?: ""
        val result = queryParse(response)
        val filtered = if (query.isEmpty()) {
            result.mangas
        } else {
            result.mangas.filter { it.title.contains(query, ignoreCase = true) }
        }
        return MangasPage(filtered, false)
    }

    // ── Manga Detail ──────────────────────────────────────────
    override fun mangaDetailsRequest(manga: SManga): Request =
        Request.Builder().url(fsUrl("$collection/${manga.url}")).build()

    override fun mangaDetailsParse(response: Response): SManga =
        docToManga(json.parseToJsonElement(response.body.string()).jsonObject)

    // ── Chapter List (same endpoint as detail) ────────────────
    override fun chapterListRequest(manga: SManga): Request =
        mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = json.parseToJsonElement(response.body.string()).jsonObject
        val docId = doc["name"]?.jsonPrimitive?.content?.substringAfterLast("/") ?: return emptyList()
        val fields = doc["fields"]?.jsonObject ?: return emptyList()

        val komikFields = fields["Komik"]
            ?.jsonObject?.get("mapValue")
            ?.jsonObject?.get("fields")
            ?.jsonObject ?: return emptyList()

        return komikFields.entries
            .sortedByDescending { it.key.toDoubleOrNull() ?: 0.0 }
            .mapNotNull { (chId, chData) ->
                val chFields = chData.jsonObject["mapValue"]
                    ?.jsonObject?.get("fields")
                    ?.jsonObject ?: return@mapNotNull null

                val hasImages = chFields["img"]?.jsonObject?.get("arrayValue")
                    ?.jsonObject?.get("values")?.jsonArray
                    ?.any { it.jsonObject["stringValue"]?.jsonPrimitive?.content?.isNotEmpty() == true }
                    ?: false

                SChapter.create().apply {
                    url = "$docId/$chId"
                    name = "Chapter $chId"
                    date_upload = runCatching {
                        chFields["UpdateAt"]?.jsonObject?.get("timestampValue")
                            ?.jsonPrimitive?.content
                            ?.let { dateFormat.parse(it)?.time } ?: 0L
                    }.getOrDefault(0L)
                    scanlator = if (!hasImages) "⚠ Belum ada gambar" else null
                }
            }
    }

    // ── Page List ─────────────────────────────────────────────
    // chapter.url = "{docId}/{chapterId}"
    // chId dipass lewat custom header karena pageListParse tidak punya akses chapter.url
    override fun pageListRequest(chapter: SChapter): Request {
        val (docId, chId) = chapter.url.split("/", limit = 2)
        return Request.Builder()
            .url(fsUrl("$collection/$docId"))
            .addHeader("X-Chapter-Id", chId)
            .build()
    }

    override fun pageListParse(response: Response): List<Page> {
        val chId = response.request.header("X-Chapter-Id") ?: return emptyList()
        val doc = json.parseToJsonElement(response.body.string()).jsonObject
        val fields = doc["fields"]?.jsonObject ?: return emptyList()

        val imgArray = fields["Komik"]
            ?.jsonObject?.get("mapValue")
            ?.jsonObject?.get("fields")
            ?.jsonObject?.get(chId)
            ?.jsonObject?.get("mapValue")
            ?.jsonObject?.get("fields")
            ?.jsonObject?.get("img")
            ?.jsonObject?.get("arrayValue")
            ?.jsonObject?.get("values")
            ?.jsonArray ?: return emptyList()

        return imgArray.mapIndexedNotNull { i, v ->
            val url = v.jsonObject["stringValue"]?.jsonPrimitive?.content
            if (!url.isNullOrEmpty()) Page(i, imageUrl = url) else null
        }
    }

    // ── Image ─────────────────────────────────────────────────
    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request =
        Request.Builder()
            .url(page.imageUrl!!)
            .addHeader("Referer", "https://keikomik.web.id/")
            .build()

    companion object {
        private const val PAGE_SIZE = 50
    }
}
