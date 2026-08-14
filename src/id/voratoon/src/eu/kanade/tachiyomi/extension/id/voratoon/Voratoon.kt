package eu.kanade.tachiyomi.extension.id.voratoon

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class Voratoon : KeiSource() {

    companion object {
        private const val API_URL = "https://api.voratoon.com"
        private const val PAGE_SIZE = 30
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/series/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val (slug, index) = chapter.url.split("/", limit = 2)
        return "$baseUrl/manga/$slug/chapter-$index"
    }

    // ---- Popular ----

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get(
            "$API_URL/series".toHttpUrl().newBuilder()
                .addQueryParameter("take", PAGE_SIZE.toString())
                .addQueryParameter("page", page.toString())
                .addQueryParameter("sort", "views")
                .addQueryParameter("sortOrder", "desc")
                .build().toString(),
            headers,
        )
        val dto = response.parseAs<SeriesListDto>()
        return MangasPage(dto.data.map { it.toSManga() }, dto.data.size >= PAGE_SIZE)
    }

    // ---- Latest ----

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.get(
            "$API_URL/series".toHttpUrl().newBuilder()
                .addQueryParameter("take", PAGE_SIZE.toString())
                .addQueryParameter("page", page.toString())
                .addQueryParameter("sort", "latest")
                .addQueryParameter("sortOrder", "desc")
                .build().toString(),
            headers,
        )
        val dto = response.parseAs<SeriesListDto>()
        return MangasPage(dto.data.map { it.toSManga() }, dto.data.size >= PAGE_SIZE)
    }

    // ---- Search ----

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()
        val status = filters.firstInstanceOrNull<StatusFilter>()
        val format = filters.firstInstanceOrNull<FormatFilter>()
        val genre = filters.firstInstanceOrNull<GenreFilter>()

        val url = "$API_URL/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("take", PAGE_SIZE.toString())
            addQueryParameter("page", page.toString())
            addQueryParameter("sort", sort?.apiValue ?: "latest")
            addQueryParameter("sortOrder", "desc")
            if (query.isNotBlank()) addQueryParameter("title", query)
            status?.apiValue?.let { addQueryParameter("status", it) }
            format?.apiValue?.let { addQueryParameter("format", it) }
            genre?.selectedId?.let { addQueryParameter("genreId", it.toString()) }
        }.build().toString()

        val response = client.get(url, headers)
        val dto = response.parseAs<SeriesListDto>()
        return MangasPage(dto.data.map { it.toSManga() }, dto.data.size >= PAGE_SIZE)
    }

    // ---- Filter list ----

    private var genreList: List<GenreItemDto> = emptyList()
    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = run {
        val response = client.get("$API_URL/genres", headers)
        val dto = response.parseAs<GenreListDto>()
        genreList = dto.data
        Json.encodeToJsonElement(GenreListDto.serializer(), dto)
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.let {
            try { Json.decodeFromJsonElement(GenreListDto.serializer(), it).data }
            catch (e: Exception) { genreList }
        } ?: genreList
        return FilterList(
            SortFilter(),
            StatusFilter(),
            FormatFilter(),
            GenreFilter(genres),
        )
    }

    // ---- Manga Details ----

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            val response = client.get("$API_URL/series/${manga.url}", headers)
            val dto = response.parseAs<SeriesDetailDto>()
            dto.item.toSManga().apply { url = manga.url }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val response = client.get("$API_URL/series/${manga.url}/chapters", headers)
            val dto = response.parseAs<ChapterListDto>()
            dto.data.map { it.toSChapter(manga.url) }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    // ---- Chapter Pages ----

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (slug, index) = chapter.url.split("/", limit = 2)
        val response = client.get("$API_URL/series/$slug/chapters/$index", headers)
        val dto = response.parseAs<ChapterDetailDto>()
        return dto.item.info.images.mapIndexed { i, url ->
            Page(i, imageUrl = url)
        }
    }

    // ---- URL deep link ----

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.pathSegments.getOrNull(1) ?: return null
        val response = client.get("$API_URL/series/$slug", headers)
        val dto = response.parseAs<SeriesDetailDto>()
        return dto.item.toSManga().apply { this.url = slug }
    }
}
