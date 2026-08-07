package eu.kanade.tachiyomi.extension.id.komikavbeta

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
class KomikavBeta : KeiSource() {

    // Base URL situs
    override val baseUrl = "https://komikav.net"

    // Preferensi bahasa (ID)
    override val lang = "id"

    // Nama ekstensi
    override val name = "Komikav Beta"

    // Versi library (1.4)
    override val libVersion = "1.4"

    // ---------- Daftar Manga Populer ----------
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/popular/?page=$page"
        val doc = fetchDocument(url)
        val mangaElements = doc.select("div.flex.overflow-hidden.rounded-md.bg-white")
        val mangas = mangaElements.map { element -> parseMangaItem(element) }
        val hasNextPage = doc.select("a.next").isNotEmpty() // cek tombol next
        return MangasPage(mangas, hasNextPage)
    }

    // ---------- Update Terbaru (Latest) ----------
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        // Halaman utama menampilkan update terbaru
        val url = "$baseUrl/?page=$page"
        val doc = fetchDocument(url)
        val mangaElements = doc.select("div.flex.overflow-hidden.rounded-md.bg-white")
        val mangas = mangaElements.map { element -> parseMangaItem(element) }
        val hasNextPage = doc.select("a.next").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    // ---------- Pencarian ----------
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = HttpUrl.parse("$baseUrl/search")!!.newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("page", page.toString())
            .build().toString()
        val doc = fetchDocument(url)
        val mangaElements = doc.select("div.flex.overflow-hidden.rounded-md.bg-white")
        val mangas = mangaElements.map { element -> parseMangaItem(element) }
        val hasNextPage = doc.select("a.next").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    // ---------- Detail Manga ----------
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val doc = fetchDocument(url.toString())
        val manga = SManga().apply {
            // Ambil judul dari tag <h1> atau <title>
            title = doc.select("h1.text-2xl.font-bold").first()?.text() ?: doc.title()
            // Cover: cari gambar di dalam div detail (biasanya di .aspect-[5/7] img)
            thumbnail_url = doc.select("div.aspect-\\[5\\/7\\] img").first()?.attr("src") ?: ""
            // Sinopsis: cari deskripsi di paragraf (mungkin ada class tertentu)
            description = doc.select("div.prose p").text()
            // Status: cari teks "Status" atau "Ongoing/Completed"
            status = when {
                doc.text().contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                doc.text().contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            // Genre: ambil dari tag <a> di bagian genre (biasanya di div genre)
            genre = doc.select("div.genre a").joinToString(", ") { it.text() }
            // Tipe (Manga/Manhwa/Manhua) bisa didapat dari label di halaman
            // Tidak wajib
            initialized = true
        }
        return manga
    }

    // ---------- Daftar Chapter ----------
    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val url = manga.url
        val doc = fetchDocument(url)
        // Biasanya daftar chapter ada dalam div dengan class "chapter-list" atau "list-chapter"
        // Dari HTML contoh, chapter di halaman utama ada di div.grid.grid-cols-1.gap-2, tapi untuk detail kita perlu cari
        // Asumsikan ada div berisi link chapter
        val chapterElements = doc.select("div.chapter-list a, div.list-chapter a, div.grid.gap-2 a")
        return chapterElements.mapNotNull { element ->
            val link = element.attr("href")
            val name = element.text().trim()
            if (link.isNotBlank() && name.isNotBlank()) {
                SChapter().apply {
                    this.url = link
                    this.name = name
                    // Tanggal upload (jika ada) bisa diambil dari span dengan class "float-right"
                    val dateText = element.select("span.float-right").text()
                    date_upload = parseDate(dateText) ?: 0L
                }
            } else null
        }.reversed() // biasanya chapter terbaru di atas, tapi kita bisa urutkan
    }

    // ---------- Halaman Gambar di Chapter ----------
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = fetchDocument(chapter.url)
        val imageElements = doc.select("div.chapter-content img, div.reader-area img")
        return imageElements.mapIndexed { index, img ->
            val imageUrl = img.attr("src").ifEmpty { img.attr("data-src") }
            Page(index, imageUrl = imageUrl)
        }
    }

    // ---------- Helper: fetch document dengan Jsoup ----------
    private suspend fun fetchDocument(url: String): Document {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: throw Exception("Empty response")
        return Jsoup.parse(html, url)
    }

    // ---------- Helper: parsing item manga dari elemen ----------
    private fun parseMangaItem(element: Element): SManga {
        val linkElement = element.select("a").first()
        val coverElement = element.select("img").first()
        val titleElement = element.select("h2").first() ?: element.select("h2 a").first()

        return SManga().apply {
            title = titleElement?.text()?.trim() ?: "Unknown"
            url = linkElement?.attr("href") ?: ""
            thumbnail_url = coverElement?.attr("data-src")?.ifEmpty { coverElement.attr("src") } ?: ""
            // Genre label bisa diambil dari div absolute
            val genreLabel = element.select("div.z-100.absolute.left-0.top-0").text()
            if (genreLabel.isNotBlank()) genre = genreLabel
            initialized = true
        }
    }

    // ---------- Helper: parse tanggal (opsional) ----------
    private fun parseDate(dateText: String): Long? {
        // Contoh: "1 jam lalu", "2 hari lalu", "1 mgg lalu", "1 bln lalu"
        return when {
            dateText.contains("jam lalu") -> System.currentTimeMillis() - 3600_000L
            dateText.contains("hari lalu") -> {
                val num = dateText.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                System.currentTimeMillis() - num * 24 * 3600_000L
            }
            dateText.contains("mgg lalu") -> {
                val num = dateText.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                System.currentTimeMillis() - num * 7 * 24 * 3600_000L
            }
            dateText.contains("bln lalu") -> {
                val num = dateText.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                System.currentTimeMillis() - num * 30 * 24 * 3600_000L
            }
            else -> null
        }
    }
}
