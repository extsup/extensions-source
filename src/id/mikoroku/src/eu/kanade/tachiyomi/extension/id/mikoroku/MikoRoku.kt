package eu.kanade.tachiyomi.extension.id.mikoroku

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import kotlin.time.Instant

@Source
abstract class MikoRoku : KeiSource() {

    override val supportsLatest = true

    private val mangaJsonUrl = "https://raw.githubusercontent.com/moemaomao/mymangadata/main/all-manga.json"
    private val chapterFeedUrl = "https://www.mikodrive.my.id/feeds/posts/default"
    private val githubRaw = "https://raw.githubusercontent.com/moemaomao/mymangadata/main/"

    private val chapterRegex = Regex(
        """(?:chapter|ch\.?|chap\.?)\s*(\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )

    private fun normalizeTitle(title: String): String = title.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun resolveCover(url: String): String {
        val trimmed = url.trimEnd('.').trim()
        if (trimmed.isBlank() || trimmed == "-") return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            githubRaw + trimmed.trimStart('/')
        }
    }

    private suspend fun fetchAllManga(): List<MangaEntry> = client.get(mangaJsonUrl, headers).parseAs()

    private fun paginate(list: List<MangaEntry>, page: Int): MangasPage {
        val from = (page - 1) * PAGE_SIZE
        return MangasPage(
            list.drop(from).take(PAGE_SIZE).map { it.toSManga(::resolveCover) },
            from + PAGE_SIZE < list.size,
        )
    }

    override suspend fun getPopularManga(page: Int): MangasPage = paginate(fetchAllManga().sortedByDescending { it.rating }, page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = paginate(fetchAllManga().filter { it.isUp }, page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val q = query.lowercase()
        val filtered = fetchAllManga().filter {
            it.title.lowercase().contains(q) || it.altTitle.lowercase().contains(q)
        }
        return paginate(filtered, page)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.substringAfter("slug=")
        val entry = fetchAllManga().firstOrNull { it.slug == slug }

        return SMangaUpdate(
            manga = if (fetchDetails && entry != null) {
                entry.toSManga(::resolveCover).also { it.url = manga.url }
            } else null,
            chapters = if (fetchChapters && entry != null) {
                fetchChaptersForEntry(entry)
            } else null,
        )
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.queryParameter("slug") ?: return null
        return fetchAllManga().firstOrNull { it.slug == slug }?.toSManga(::resolveCover)
    }

    private suspend fun fetchChaptersForEntry(entry: MangaEntry): List<SChapter> {
        val normalizedManga = normalizeTitle(entry.title)
        val feed = client.get("$chapterFeedUrl?alt=json&max-results=500", headers)
            .parseAs<BloggerFeed>()

        return feed.feed.entries.orEmpty()
            .filter { normalizeTitle(it.title.value).startsWith(normalizedManga) }
            .mapNotNull { post ->
                val num = chapterRegex.find(post.title.value)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val link = post.links.firstOrNull { it.rel == "alternate" }
                    ?: return@mapNotNull null
                SChapter.create().apply {
                    url = link.href.removePrefix("https://www.mikodrive.my.id")
                    name = "Chapter $num"
                    date_upload = runCatching {
                        Instant.parse(post.published.value).toEpochMilliseconds()
                    }.getOrDefault(0L)
                }
            }
            .sortedByDescending { it.name }
    }

    override fun getChapterUrl(chapter: SChapter): String = "https://www.mikodrive.my.id${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get("https://www.mikodrive.my.id${chapter.url}", headers).asJsoup()
        return doc.select(".post-body img[src], #post-body img[src]")
            .mapIndexed { index, img -> Page(index, imageUrl = img.attr("abs:src")) }
    }

    companion object {
        private const val PAGE_SIZE = 24
    }
}
