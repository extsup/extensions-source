package eu.kanade.tachiyomi.extension.id.komikucom

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class KomikuCom : KeiSource() {

    private val apiBase = "$baseUrl/api/v2"
    private val readerBase = "https://api.komiku.asia/read/id"
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

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> filter.selectedValue().takeIf { it.isNotEmpty() }?.let {
                    urlBuilder.addQueryParameter("status", it)
                }
                is TypeFilter -> filter.selectedValue().takeIf { it.isNotEmpty() }?.let {
                    urlBuilder.addQueryParameter("type", it)
                }
                is OrderFilter -> urlBuilder.addQueryParameter("order_by", filter.selectedValue())
                is OrderDirFilter -> urlBuilder.addQueryParameter("order", filter.selectedValue())
                is GenreFilter ->
                    filter.state
                        .filter { it.state }
                        .forEach { urlBuilder.addQueryParameter("genre", it.value) }
                else -> {}
            }
        }

        val response = client.get(urlBuilder.build(), headers)
        val body = response.parseAs<ComicsResponse>()
        return MangasPage(
            mangas = body.items.map { it.toSManga() },
            hasNextPage = page < body.totalPages,
        )
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        OrderFilter(),
        OrderDirFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
    )

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
        val chNum = parts[2].toFloat().let { if (it % 1 == 0f) it.toInt().toString() else it.toString().replace(".", "-") }
        val readerUrl = "$readerBase/$slug/ch$chNum-$chapterId"

        val response = client.get(readerUrl, headers)
        val document = response.asJsoup()

        return document.select("img.rd-page-image").mapIndexed { index, img ->
            Page(index = index, imageUrl = img.attr("abs:src"))
        }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/manga/${manga.url}/"

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split("|")
        val slug = parts[0]
        val chapterId = parts[1]
        val chapterNumber = parts[2].toFloat().let { if (it % 1 == 0f) it.toInt().toString() else it.toString().replace(".", "-") }
        return "$readerBase/$slug/ch$chapterNumber-$chapterId"
    }
}
