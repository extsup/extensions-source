package eu.kanade.tachiyomi.extension.id.komikucom

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class KomikuCom : KeiSource() {

    private val apiBase = "https://01.komiku.asia/api/v2"
    private val readerBase = "$baseUrl/read/id"
    private val pageSize = 20

    // ── Popular ───────────────────────────────────────────────────────────────

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$apiBase/comics".toHttpUrl().newBuilder()
            .addQueryParameter("order_by", "views")
            .addQueryParameter("order", "desc")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", pageSize.toString())
            .build()

        val response = client.get(url, headers)
        val body = response.parseAs<ComicsResponse>()
        return MangasPage(
            mangas = body.items.map { it.toSManga() },
            hasNextPage = page < body.totalPages,
        )
    }

    // ── Latest ────────────────────────────────────────────────────────────────

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$apiBase/comics".toHttpUrl().newBuilder()
            .addQueryParameter("order_by", "updated_at")
            .addQueryParameter("order", "desc")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", pageSize.toString())
            .build()

        val response = client.get(url, headers)
        val body = response.parseAs<ComicsResponse>()
        return MangasPage(
            mangas = body.items.map { it.toSManga() },
            hasNextPage = page < body.totalPages,
        )
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val urlBuilder = "$apiBase/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", pageSize.toString())

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("search", query)
        }

        val response = client.get(urlBuilder.build(), headers)
        val body = response.parseAs<ComicsResponse>()
        return MangasPage(
            mangas = body.items.map { it.toSManga() },
            hasNextPage = page < body.totalPages,
        )
    }

    // ── Details + Chapters ────────────────────────────────────────────────────

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val detail: ComicItem? = if (fetchDetails || fetchChapters) {
            client.get("$apiBase/comics/${manga.url}", headers).parseAs<ComicItem>()
        } else {
            null
        }

        val updatedManga: SManga? = if (fetchDetails) detail?.toSMangaFull() else null

        val updatedChapters: List<SChapter>? = if (fetchChapters && detail != null) {
            client.get("$apiBase/comics/${detail.id}/chapters", headers)
                .parseAs<List<ChapterItem>>()
                .map { it.toSChapter(manga.url) }
        } else {
            null
        }

        return SMangaUpdate(
            manga = updatedManga ?: manga,
            chapters = updatedChapters ?: chapters,
        )
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val parts = chapter.url.split("|")
        val slug = parts[0]
        val chapterId = parts[1]
        val chNum = parts[2].replace(".", "-")
        val readerUrl = "$readerBase/$slug/ch$chNum-$chapterId"

        val response = client.get(readerUrl, headers)
        val bodyStr = response.body.string()
        android.util.Log.d("KomikuCom", "URL: $readerUrl")
        android.util.Log.d("KomikuCom", "Body500: ${bodyStr.take(500)}")
        val document = org.jsoup.Jsoup.parse(bodyStr, readerUrl)

        val script = document.select("script").joinToString { it.html() }
        android.util.Log.d("KomikuCom", "ScriptLen: ${script.length}")
        val match = Regex(""""pages"\s*:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)
            .find(script)?.groupValues?.get(1)
        android.util.Log.d("KomikuCom", "Match: ${match?.take(200)}")
        if (match == null) return emptyList()

        return match.parseAs<List<PageItem>>().mapIndexed { index, page ->
            Page(index = index, imageUrl = page.url)
        }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/manga/${manga.url}/"

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split("|")
        val slug = parts[0]
        val chapterId = parts[1]
        val chapterNumber = parts[2].replace(".", "-")
        return "$readerBase/$slug/ch$chapterNumber-$chapterId"
    }
}
