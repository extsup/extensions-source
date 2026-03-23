package eu.kanade.tachiyomi.extension.id.keikomik

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
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class KeiKomik : HttpSource() {

    override val name = "KeiKomik"

    // baseUrl dipakai Tachiyomi untuk cek duplikat source — pakai domain asli site
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

    // OkHttp — tidak perlu header khusus, Firestore REST support CORS/plain request
    override val client: OkHttpClient = network.cloudflareClient

    // ── URL builder helper ────────────────────────────────────
    private fun fsUrl(path: String, vararg params: Pair<String, String>): String =
        "$fsBase/$path".toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
            .toString()

    // ── Helper: ambil string value dari Firestore typed field ─
    private fun fsStr(field: Map.Entry<String, *>?): String =
        field?.value?.let {
            runCatching {
                (it as? kotlinx.serialization.json.JsonElement)
                    ?.jsonObject?.get("stringValue")?.jsonPrimitive?.content
            }.getOrNull()
        } ?: ""

    // ── Konversi Firestore document → SManga ──────────────────
    private fun docToManga(doc: kotlinx.serialization.json.JsonObject): SManga {
        val name = doc["name"]?.jsonPrimitive?.content ?: "" // Firestore doc path
        val docId = name.substringAfterLast("/")
        val fields = doc["fields"]?.jsonObject ?: return SManga.create()

        fun str(key: String) = fields[key]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: ""
        fun num(key: String) = fields[key]?.jsonObject?.let {
            it["integerValue"]?.jsonPrimitive?.content
                ?: it["doubleValue"]?.jsonPrimitive?.content
        }

        return SManga.create().apply {
            // url = docId saja, kita tahu collection-nya
            url = docId
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

    // ── Popular ───────────────────────────────────────────────
    // fetch semua dokumen (max 300 per page), ordered by UpdateAt desc via runQuery
    override fun popularMangaRequest(page: Int): Request {
        return latestUpdatesRequest(page)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return latestUpdatesParse(response)
    }

    // ── Latest Updates ────────────────────────────────────────
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$fsBase:runQuery?key=$apiKey"
        val offset = (page - 1) * PAGE_SIZE
        val body = """
        {
          "structuredQuery": {
            "from": [{"collectionId": "$collection"}],
            "orderBy": [{"field": {"fieldPath": "UpdateAt"}, "direction": "DESCENDING"}],
            "limit": $PAGE_SIZE,
            "offset": $offset
          }
        }
        """.trimIndent()
        return Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val arr = json.parseToJsonElement(response.body.string()).jsonArray
        val mangas = arr.mapNotNull { item ->
            item.jsonObject["document"]?.jsonObject?.let { docToManga(it) }
                ?.takeIf { it.title.isNotEmpty() }
        }
        return MangasPage(mangas, mangas.size == PAGE_SIZE)
    }

    // ── Search ────────────────────────────────────────────────
    // Firestore tidak support full-text search → fetch semua lalu filter di client
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return fetchLatestUpdates(1).map { result ->
            val filtered = result.mangas.filter {
                it.title.contains(query, ignoreCase = true)
            }
            MangasPage(filtered, false)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        latestUpdatesRequest(1)

    override fun searchMangaParse(response: Response): MangasPage =
        latestUpdatesParse(response)

    // ── Manga Detail ──────────────────────────────────────────
    // manga.url = docId (e.g. "vfLsvQaFlTq8hAckGX7X")
    override fun mangaDetailsRequest(manga: SManga): Request =
        Request.Builder().url(fsUrl("$collection/${manga.url}")).build()

    override fun mangaDetailsParse(response: Response): SManga =
        docToManga(json.parseToJsonElement(response.body.string()).jsonObject)

    // ── Chapter List ──────────────────────────────────────────
    // Data chapter ada di dalam field "Komik" dokumen yang sama
    // → reuse mangaDetailsRequest, tidak perlu request baru
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

                // cek ada gambar valid
                val hasImages = chFields["img"]?.jsonObject?.get("arrayValue")
                    ?.jsonObject?.get("values")?.jsonArray
                    ?.any { it.jsonObject["stringValue"]?.jsonPrimitive?.content?.isNotEmpty() == true }
                    ?: false

                SChapter.create().apply {
                    // url format: "{docId}/{chapterId}" — docId untuk fetch, chId untuk lookup
                    url = "$docId/$chId"
                    name = "Chapter $chId"
                    date_upload = runCatching {
                        chFields["UpdateAt"]?.jsonObject?.get("timestampValue")
                            ?.jsonPrimitive?.content
                            ?.let { dateFormat.parse(it)?.time } ?: 0L
                    }.getOrDefault(0L)
                    // tandai chapter yang belum ada gambar
                    scanlator = if (!hasImages) "⚠ Belum ada gambar" else null
                }
            }
    }

    // ── Page List ─────────────────────────────────────────────
    // chapter.url = "{docId}/{chapterId}"
    // fetch dokumen → ambil Komik.{chapterId}.img
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val (docId, chId) = chapter.url.split("/", limit = 2)
        val request = Request.Builder().url(fsUrl("$collection/$docId")).build()

        return client.newCall(request).asObservableSuccess().map { response ->
            val doc = json.parseToJsonElement(response.body.string()).jsonObject
            val fields = doc["fields"]?.jsonObject ?: return@map emptyList()

            val imgArray = fields["Komik"]
                ?.jsonObject?.get("mapValue")
                ?.jsonObject?.get("fields")
                ?.jsonObject?.get(chId)
                ?.jsonObject?.get("mapValue")
                ?.jsonObject?.get("fields")
                ?.jsonObject?.get("img")
                ?.jsonObject?.get("arrayValue")
                ?.jsonObject?.get("values")
                ?.jsonArray ?: return@map emptyList()

            imgArray.mapIndexedNotNull { i, v ->
                val url = v.jsonObject["stringValue"]?.jsonPrimitive?.content
                if (!url.isNullOrEmpty()) Page(i, imageUrl = url) else null
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val (docId, _) = chapter.url.split("/", limit = 2)
        return Request.Builder().url(fsUrl("$collection/$docId")).build()
    }

    override fun pageListParse(response: Response): List<Page> = emptyList() // tidak dipakai, pakai fetchPageList

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
