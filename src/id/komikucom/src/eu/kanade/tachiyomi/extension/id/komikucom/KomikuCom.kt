package eu.kanade.tachiyomi.extension.id.komikucom

import eu.kanade.tachiyomi.source.model.FilterList
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

private const val API_BASE = "https://01.komiku.asia/api/v2"
private const val READER_BASE = "https://api.komiku.asia/read/id"
private const val PAGE_SIZE = 20

@Source
abstract class KomikuCom : KeiSource() {

    // ── Popular ───────────────────────────────────────────────────────────────

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$API_BASE/comics".toHttpUrl().newBuilder()
            .addQueryParameter("order_by", "views")
            .addQueryParameter("order", "desc")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PAGE_SIZE.toString())
            .build()

        val response = client.get(url, headers)
        val body = response.parseAs<ComicsResponse>()
        return MangasPage(
            mangas = body.data.map { it.toSManga() },
            hasNextPage = page < body.meta.totalPages,
        )
    }

    // ── Latest ────────────────────────────────────────────────────────────────

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$API_BASE/comics".toHttpUrl().newBuilder()
            .addQueryParameter("order_by", "updated_at")
            .addQueryParameter("order", "desc")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PAGE_SIZE.toString())
            .build()

        val response = client.get(url, headers)
        val body = response.parseAs<ComicsResponse>()
        return MangasPage(
            mangas = body.data.map { it.toSManga() },
            hasNextPage = page < body.meta.totalPages,
        )
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val urlBuilder = "$API_BASE/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", PAGE_SIZE.toString())

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("search", query)
        }

        val response = client.get(urlBuilder.build(), headers)
        val body = response.parseAs<ComicsResponse>()
        return MangasPage(
            mangas = body.data.map { it.toSManga() },
            hasNextPage = page < body.meta.totalPages,
        )
    }

    // ── Details + Chapters ────────────────────────────────────────────────────

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga: SManga? = if (fetchDetails) {
            val detailResponse = client.get("$API_BASE/comics/${manga.url}", headers)
            detailResponse.parseAs<ComicDetailResponse>().data.toSMangaFull()
        } else {
            null
        }

        val updatedChapters: List<SChapter>? = if (fetchChapters) {
            val numericId = getNumericId(manga.url)
            val chapterResponse = client.get("$API_BASE/comics/$numericId/chapters", headers)
            chapterResponse.parseAs<ChaptersResponse>().data
                .map { it.toSChapter() }
                .reversed()
        } else {
            null
        }

        return SMangaUpdate(
            manga = updatedManga ?: manga,
            chapters = updatedChapters ?: chapters,
        )
    }

    private suspend fun getNumericId(slug: String): Int {
        val rawJson = client.get("$API_BASE/comics/$slug", headers).body.string()
        val match = Regex(""""id"\s*:\s*(\d+)""").find(rawJson)
        return match?.groupValues?.get(1)?.toInt()
            ?: throw IllegalStateException("Cannot parse numeric id from detail response")
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // url format encoded in Dto: {comicSlug}|{chapterId}|{chapterNumber}
        val parts = chapter.url.split("|")
        val slug = parts[0]
        val chapterId = parts[1]
        val chapterNumber = parts[2]

        // chapterNumber may be "1", "1.5", etc. The reader URL uses integer-like format
        // e.g. ch1-12345 or ch1-5-12345 (decimals become dashes)
        val chNum = chapterNumber.replace(".", "-")
        val readerUrl = "$READER_BASE/$slug/ch$chNum-$chapterId"

        val response = client.get(readerUrl, headers)
        val document = response.asJsoup()

        return document.select("img[src*=cdnkomiku.xyz]").mapIndexed { index, img ->
            Page(index, imageUrl = img.attr("abs:src"))
        }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/komik/${manga.url}/"

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split("|")
        val slug = parts[0]
        val chapterId = parts[1]
        val chapterNumber = parts[2].replace(".", "-")
        return "$READER_BASE/$slug/ch$chapterNumber-$chapterId"
    }
}
