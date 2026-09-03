package eu.kanade.tachiyomi.extension.id.komiknesia

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Source
abstract class KomikNesia : HttpSource() {

    private val apiUrl = "https://api-be.komiknesia.my.id/api"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    private val deviceId = generateDeviceId()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("X-Device-Id", deviceId)

    // ===============================
    // Decrypt
    // ===============================

    private fun generateDeviceId(): String {
        val rand = (Math.random() * 0xFFFFFFFFL).toLong()
            .toString(16).padStart(8, '0').take(8)
        val ts = (System.currentTimeMillis() / 1000)
            .toString(16).takeLast(6)
        return "dv_$rand$ts"
    }

    private fun generateKey(time: Long): String {
        var r = time.toDouble()
        repeat(5) { r /= 2 }
        return String.format(Locale.US, "%.8f", r)
            .padEnd(32, '0')
            .take(32)
    }

    private fun decryptData(encryptedData: String, time: Long): String {
        val key = generateKey(time)
        val raw = Base64.decode(encryptedData, Base64.DEFAULT)
        val iv = raw.copyOfRange(0, 16)
        val cipherText = raw.copyOfRange(16, raw.size)
        val secretKey = SecretKeySpec(key.toByteArray(), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        return String(cipher.doFinal(cipherText))
    }

    private fun Response.decryptedBody(): String {
        val payload = parseAs<EncryptedPayloadDto>()
        return if (payload.encrypted == true && payload.time != null) {
            decryptData(payload.data.jsonPrimitive.content, payload.time)
        } else {
            """{"data":${payload.data},"meta":${
                if (payload.meta != null) {
                    """{"page":${payload.meta.page},"total_pages":${payload.meta.totalPages}}"""
                } else {
                    "null"
                }
            }}"""
        }
    }

    // ===============================
    // Popular
    // ===============================

    override fun popularMangaRequest(page: Int): Request = searchMangaRequest(page, "", FilterList(OrderFilter().apply { state = 2 }))

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    // ===============================
    // Latest
    // ===============================

    override fun latestUpdatesRequest(page: Int): Request = searchMangaRequest(page, "", FilterList(OrderFilter().apply { state = 0 }))

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    // ===============================
    // Search
    // ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/contents".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotEmpty()) {
            url.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    filter.state
                        .filter { it.state }
                        .forEach { url.addQueryParameter("genre[]", it.id) }
                }
                is StatusFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("status", filter.toUriPart())
                    }
                }
                is OrderFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("orderBy", filter.toUriPart())
                    }
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.decryptedBody()
        val payload = json.decodeFromString<PayloadDto<List<MangaDto>>>(body)
        val mangas = payload.data.map { it.toSManga() }
        val hasNextPage = payload.meta?.let { it.page < it.totalPages } ?: false
        return MangasPage(mangas, hasNextPage)
    }

    // ===============================
    // Details
    // ===============================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/comic/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.decryptedBody()
        val payload = json.decodeFromString<PayloadDto<MangaDto>>(body)
        return payload.data.toSManga()
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/komik/${manga.url}"

    // ===============================
    // Chapters
    // ===============================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.decryptedBody()
        val payload = json.decodeFromString<PayloadDto<MangaDto>>(body)
        return payload.data.chapters?.map { it.toSChapter() } ?: emptyList()
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/view/${chapter.url}"

    // ===============================
    // Pages
    // ===============================

    override fun pageListRequest(chapter: SChapter): Request = GET("$apiUrl/chapters/slug/${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val body = response.decryptedBody()
        val payload = json.decodeFromString<PayloadDto<PageListDto>>(body)
        if (payload.data.images.isEmpty()) {
            throw Exception("Chapter terbaru dapat dibaca setelah login melalui WebView, atau tunggu hingga 2 jam dari rilis untuk membaca tanpa login.")
        }
        return payload.data.images.mapIndexed { idx, img ->
            Page(idx, imageUrl = img)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ===============================
    // Filters
    // ===============================

    private var genresList: List<Pair<String, String>> = emptyList()
    private var genresFetched: Boolean = false
    private var fetchGenresAttempts: Int = 0
    private val scope = CoroutineScope(Dispatchers.IO)
    private fun launchIO(block: () -> Unit) = scope.launch { block() }

    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }

        val filters = mutableListOf<Filter<*>>(
            OrderFilter(),
            StatusFilter(),
        )

        if (genresList.isNotEmpty()) {
            filters += listOf(
                Filter.Separator(),
                GenreFilter(genresList.map { Genre(it.first, it.second) }),
            )
        } else {
            filters += listOf(
                Filter.Header("Press 'Reset' to load genres"),
            )
        }

        return FilterList(filters)
    }

    private fun fetchGenres() {
        if (fetchGenresAttempts < 3 && !genresFetched) {
            try {
                client.newCall(GET("$apiUrl/contents/genres", headers)).execute().use { response ->
                    val body = response.decryptedBody()
                    val payload = json.decodeFromString<PayloadDto<List<GenreDto>>>(body)
                    genresList = payload.data.map { it.name to it.id.toString() }
                    genresFetched = true
                }
            } catch (_: Exception) {
            } finally {
                fetchGenresAttempts++
            }
        }
    }
}
