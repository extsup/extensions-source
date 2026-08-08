package eu.kanade.tachiyomi.extension.id.komikavbeta

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

@Source
class KomikavBeta : HttpSource() {

    override val supportsLatest = true

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
            val poster = resolve(objs, item.opt("poster")) as? String ?: ""
            val type = resolve(objs, item.opt("type")) as? String ?: ""
            val status = resolve(objs, item.opt("status")) as? String ?: ""
            results.add(SManga.create().apply {
                url = "/manga/$slug/"
                this.title = title
                thumbnail_url = poster
                genre = type
                this.status = when (status) {
                    "on-going" -> SManga.ONGOING
                    "completed" -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
                initialized = false
            })
        }
        return results
    }

    private fun slicePage(all: List<SManga>, page: Int): MangasPage {
        val from = (page - 1) * 18
        val slice = if (from < all.size) all.subList(from, all.size) else emptyList()
        return MangasPage(slice, slice.size >= 18)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        Request.Builder()
            .url("$baseUrl/q-data.json?page=$page")
            .header("Referer", "$baseUrl/?page=$page")
            .header("User-Agent", "Mozilla/5.0")
            .build()

    override fun latestUpdatesParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val objs = JSONObject(response.body.string()).getJSONArray("_objs")
        return slicePage(extractMangas(objs), page)
    }

    override fun popularMangaRequest(page: Int): Request =
        Request.Builder()
            .url("$baseUrl/comic-list/q-data.json?page=$page")
            .header("Referer", "$baseUrl/comic-list/?page=$page")
            .header("User-Agent", "Mozilla/5.0")
            .build()

    override fun popularMangaParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val objs = JSONObject(response.body.string()).getJSONArray("_objs")
        return slicePage(extractMangas(objs), page)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return Request.Builder()
            .url("$baseUrl/search/$encoded/q-data.json?page=$page")
            .header("Referer", "$baseUrl/search/$encoded/?page=$page")
            .header("User-Agent", "Mozilla/5.0")
            .build()
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val objs = JSONObject(response.body.string()).getJSONArray("_objs")
        return slicePage(extractMangas(objs), page)
    }

    override fun mangaDetailsRequest(manga: SManga): Request =
        Request.Builder()
            .url("$baseUrl${manga.url}q-data.json")
            .header("Referer", "$baseUrl${manga.url}")
            .header("User-Agent", "Mozilla/5.0")
            .build()

    override fun mangaDetailsParse(response: Response): SManga {
        val objs = JSONObject(response.body.string()).getJSONArray("_objs")
        return extractMangas(objs).firstOrNull() ?: SManga.create()
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val objs = JSONObject(response.body.string()).getJSONArray("_objs")
        val slug = response.request.url.pathSegments
            .filter { it.isNotBlank() }
            .getOrNull(1) ?: ""
        val chapters = mutableListOf<SChapter>()
        for (i in 0 until objs.length()) {
            val item = objs.opt(i) as? JSONObject ?: continue
            if (!item.has("chapter") || !item.has("id")) continue
            val chNum = resolve(objs, item.get("chapter")) ?: continue
            val createdAt = resolve(objs, item.opt("created_at")) as? String ?: ""
            chapters.add(SChapter.create().apply {
                url = "/manga/$slug/chapter-$chNum/"
                name = "Chapter $chNum"
                date_upload = parseIsoDate(createdAt)
            })
        }
        return chapters.sortedByDescending { it.date_upload }
    }

    override fun pageListRequest(chapter: SChapter): Request =
        Request.Builder()
            .url("$baseUrl${chapter.url}q-data.json")
            .header("Referer", "$baseUrl${chapter.url}")
            .header("User-Agent", "Mozilla/5.0")
            .build()

    override fun pageListParse(response: Response): List<Page> {
        val objs = JSONObject(response.body.string()).getJSONArray("_objs")
        for (i in 0 until objs.length()) {
            val arr = objs.opt(i) as? JSONArray ?: continue
            for (j in 0 until arr.length()) {
                val ch = arr.opt(j) as? JSONObject ?: continue
                if (!ch.has("images")) continue
                val imagesObj = resolve(objs, ch.getString("images")) as? JSONObject ?: continue
                val pages = mutableListOf<Page>()
                val keys = imagesObj.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }
                for ((idx, key) in keys.withIndex()) {
                    val url = resolve(objs, imagesObj.getString(key)) as? String ?: continue
                    pages.add(Page(idx, imageUrl = url))
                }
                if (pages.isNotEmpty()) return pages
            }
        }
        return emptyList()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun parseIsoDate(s: String): Long = try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT)
            .also { it.timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .parse(s)?.time ?: 0L
    } catch (_: Exception) { 0L }
}
