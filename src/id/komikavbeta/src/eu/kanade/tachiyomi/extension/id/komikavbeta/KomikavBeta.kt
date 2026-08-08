package eu.kanade.tachiyomi.extension.id.komikavbeta

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class KomikavBeta : HttpSource() {

    override val supportsLatest = true

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ==================== HELPERS ====================

    private fun resolve(objs: JSONArray, raw: Any?, depth: Int = 0): Any? {
        if (depth > 8 || raw == null) return raw
        if (raw is String) {
            if (raw == "\u0001") return null
            if (raw.startsWith("\u0006")) return raw.substring(1)
            val idx = raw.toLongOrNull(36)?.toInt() ?: return raw
            if (idx < 0 || idx >= objs.length()) return raw
            return resolve(objs, objs.get(idx), depth + 1)
        }
        return raw
    }

    private fun extractMangas(objs: JSONArray): List<SManga> {
        val results = mutableListOf<SManga>()
        for (i in 0 until objs.length()) {
            val item = objs.opt(i) as? JSONObject ?: continue
            if (!item.has("title") || !item.has("slug")) continue
            val title = resolve(objs, item.get("title")) as? String ?: continue
            val slug = resolve(objs, item.get("slug")) as? String ?: continue
            if (title.length < 2 || slug.length < 2) continue
            val poster = (resolve(objs, item.opt("poster")) as? String)
                ?.takeIf { !it.contains("cdn.imgkomik.xyz") && !it.contains("manhwature.com") }
                ?: "$baseUrl/errorImage.png"
            val type = resolve(objs, item.opt("type")) as? String ?: ""
            val status = resolve(objs, item.opt("status")) as? String ?: ""
            results.add(
                SManga.create().apply {
                    url = "/manga/$slug/"
                    this.title = title
                    thumbnail_url = poster
                    this.status = when (status) {
                        "on-going" -> SManga.ONGOING
                        "completed" -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                    genre = type
                    initialized = false
                },
            )
        }
        return results
    }

    private fun extractDetail(objs: JSONArray, manga: SManga): SManga {
        val genres = mutableListOf<String>()
        var author = ""
        var artist = ""
        var synopsis = ""
        var alter = ""
        val type = manga.genre ?: ""

        for (i in 0 until objs.length()) {
            val item = objs.opt(i) as? JSONObject ?: continue
            // Ambil synopsis dan alter dari manga object utama
            if (item.has("title") && item.has("slug") && item.has("synopsis")) {
                synopsis = ((resolve(objs, item.opt("synopsis")) as? String) ?: "").replace(Regex("\\s+"), " ").trim()
                alter = resolve(objs, item.opt("alter")) as? String ?: ""
            }
            // Ambil taxonomy (genre/author/artist)
            if (item.has("name") && item.has("type") && item.has("slug") && item.has("id")) {
                val name = resolve(objs, item.get("name")) as? String ?: continue
                val taxType = resolve(objs, item.get("type")) as? String ?: continue
                when (taxType) {
                    "genre" -> genres.add(name)
                    "author" -> author = name
                    "artist" -> artist = name
                }
            }
        }

        val allGenres = (genres + listOfNotNull(type.ifBlank { null })).joinToString(", ")

        return manga.apply {
            description = buildString {
                if (synopsis.isNotBlank()) append(synopsis)
                if (alter.isNotBlank()) append("\n\nAlt: $alter")
            }
            genre = allGenres
            this.author = author.ifBlank { null }
            this.artist = artist.ifBlank { null }
            initialized = true
        }
    }

    private fun slicePage(all: List<SManga>, page: Int): MangasPage {
        val from = (page - 1) * 18
        val slice = if (from < all.size) all.subList(from, all.size) else emptyList()
        return MangasPage(slice, slice.size >= 18)
    }

    private fun parseIsoDate(s: String): Long = runCatching {
        dateFormat.parse(s)!!.time
    }.getOrDefault(0L)

    // ==================== POPULAR ====================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/comic-list/q-data.json?page=$page")

    override fun popularMangaParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val objs = JSONObject(response.body.string()).getJSONArray("_objs")
        return slicePage(extractMangas(objs), page)
    }

    // ==================== LATEST ====================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/q-data.json?page=$page")

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== SEARCH ====================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return GET("$baseUrl/search/$encoded/q-data.json?page=$page")
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==================== DETAIL ====================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}q-data.json")

    override fun mangaDetailsParse(response: Response): SManga {
        val objs = JSONObject(response.body.string()).getJSONArray("_objs")
        val manga = extractMangas(objs).firstOrNull() ?: SManga.create()
        return extractDetail(objs, manga)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    // ==================== CHAPTER LIST ====================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val objs = JSONObject(response.body.string()).getJSONArray("_objs")
        val slug = response.request.url.pathSegments.filter { it.isNotBlank() }.getOrNull(1) ?: ""
        val chapters = mutableListOf<SChapter>()
        for (i in 0 until objs.length()) {
            val item = objs.opt(i) as? JSONObject ?: continue
            if (!item.has("chapter") || !item.has("id")) continue
            if (item.has("name")) continue
            val chNum = resolve(objs, item.get("chapter")) ?: continue
            val createdAt = resolve(objs, item.opt("created_at")) as? String ?: ""
            chapters.add(
                SChapter.create().apply {
                    url = "/manga/$slug/chapter-$chNum/"
                    name = "Chapter $chNum"
                    date_upload = parseIsoDate(createdAt)
                },
            )
        }
        return chapters.sortedByDescending { it.date_upload }
    }

    // ==================== PAGES ====================

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl${chapter.url}q-data.json")

    override fun pageListParse(response: Response): List<Page> {
        val objs = JSONObject(response.body.string()).getJSONArray("_objs")
        for (i in 0 until objs.length()) {
            val item = objs.opt(i) as? JSONObject ?: continue
            if (!item.has("images")) continue
            val imagesObj = resolve(objs, item.getString("images")) as? JSONObject ?: continue
            val pages = mutableListOf<Page>()
            val keys = imagesObj.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }
            for ((idx, key) in keys.withIndex()) {
                val pageObj = resolve(objs, imagesObj.getString(key)) as? JSONObject ?: continue
                val url = resolve(objs, pageObj.opt("src")) as? String ?: continue
                pages.add(
                    Page(
                        index = idx,
                        imageUrl = url,
                    ),
                )
            }
            if (pages.isNotEmpty()) return pages
        }
        return emptyList()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList()
}
