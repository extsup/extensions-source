package eu.kanade.tachiyomi.multisrc.natsuid

import eu.kanade.tachiyomi.source.model.SManga
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

class Term(obj: JSONObject) {
    val name: String = obj.getString("name")
    val slug: String = obj.getString("slug")
    val taxonomy: String = obj.getString("taxonomy")
}

class FeaturedMedia(obj: JSONObject) {
    val sourceUrl: String = obj.optString("source_url", "")
}

class Rendered(obj: JSONObject, key: String) {
    val rendered: String = obj.getJSONObject(key).optString("rendered", "")
}

class Embedded(obj: JSONObject) {
    val featuredMedia: List<FeaturedMedia> = obj
        .optJSONArray("wp:featuredmedia")
        ?.let { arr -> (0 until arr.length()).map { FeaturedMedia(arr.getJSONObject(it)) } }
        ?: emptyList()

    private val terms: List<List<Term>> = obj
        .optJSONArray("wp:term")
        ?.let { arr ->
            (0 until arr.length()).map { i ->
                val inner = arr.getJSONArray(i)
                (0 until inner.length()).map { j -> Term(inner.getJSONObject(j)) }
            }
        }
        ?: emptyList()

    fun getTerms(type: String): List<String> =
        terms.find { it.getOrNull(0)?.taxonomy == type }?.map { it.name } ?: emptyList()
}

class MangaUrl(val id: Int, val slug: String) {
    fun toJsonString(): String = JSONObject().put("id", id).put("slug", slug).toString()

    companion object {
        fun fromJsonString(str: String): MangaUrl {
            val obj = JSONObject(str)
            return MangaUrl(obj.getInt("id"), obj.getString("slug"))
        }
    }
}

class Manga(obj: JSONObject) {
    val id: Int = obj.getInt("id")
    val slug: String = obj.getString("slug")
    val title: String = obj.getJSONObject("title").optString("rendered", "")
    val content: String = obj.getJSONObject("content").optString("rendered", "")
    val embedded: Embedded = Embedded(obj.optJSONObject("_embedded") ?: JSONObject())

    fun toSManga(appendId: Boolean = false) = SManga.create().apply {
        url = MangaUrl(id, slug).toJsonString()
        title = Parser.unescapeEntities(this@Manga.title, false)
        description = buildString {
            append(Jsoup.parseBodyFragment(content).wholeText())
            if (appendId) {
                append("\n\nID: $id")
            }
        }
        thumbnail_url = embedded.featuredMedia.firstOrNull()?.sourceUrl?.takeIf { it.isNotBlank() }
        author = embedded.getTerms("series-author").joinToString()
        artist = embedded.getTerms("artist").joinToString()
        genre = buildSet {
            addAll(embedded.getTerms("genre"))
            addAll(embedded.getTerms("type"))
        }.joinToString()
        status = with(embedded.getTerms("status")) {
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

fun JSONArray.toMangaList(): List<Manga> =
    (0 until length()).map { Manga(getJSONObject(it)) }

fun JSONArray.toTermList(): List<Term> =
    (0 until length()).map { Term(getJSONObject(it)) }
