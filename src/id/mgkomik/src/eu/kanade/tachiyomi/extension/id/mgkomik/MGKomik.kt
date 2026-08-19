package eu.kanade.tachiyomi.extension.id.mgkomik

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.applicationContext
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Source
abstract class MGKomik : Madara() {
    override val dateFormat = SimpleDateFormat("dd MMM yy", Locale.US)

    override val useLoadMoreRequest = LoadMoreStrategy.Always
    override val mangaSubString = "komik"
    override val chapterUrlSuffix = ""

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
        set("Sec-CH-UA-Model", "\"\"")
    }

    private val sessionWarmedUp = AtomicBoolean(false)

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 403 && !sessionWarmedUp.get()) {
                response.close()
                warmupWebViewSession()
                chain.proceed(chain.request())
            } else {
                response
            }
        }
        .addInterceptor { chain ->
            val request = chain.request()
            val cookies = CookieManager.getInstance().getCookie(baseUrl)
            val newRequest = if (cookies != null) {
                request.newBuilder().header("Cookie", cookies).build()
            } else {
                request
            }
            chain.proceed(newRequest)
        }
        .addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val isAjax = path.contains("admin-ajax.php") ||
                path.contains("wp-json") ||
                path.endsWith("/ajax/chapters")
            if (isAjax) {
                chain.proceed(
                    request.newBuilder()
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("Origin", baseUrl)
                        .header("Priority", "u=1, i")
                        .removeHeader("Sec-Fetch-User")
                        .removeHeader("Upgrade-Insecure-Requests")
                        .build(),
                )
            } else {
                chain.proceed(request)
            }
        }
        .rateLimit(1)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    private fun warmupWebViewSession() {
        if (!sessionWarmedUp.compareAndSet(false, true)) return

        val latch = CountDownLatch(1)
        val mainHandler = Handler(Looper.getMainLooper())

        mainHandler.post {
            val wv = WebView(applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    mainHandler.postDelayed({
                        runCatching {
                            view?.stopLoading()
                            view?.destroy()
                        }
                        latch.countDown()
                    }, 3000L)
                }
            }
            wv.loadUrl("$baseUrl/")
        }

        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                sessionWarmedUp.set(false)
            }
        } catch (_: InterruptedException) {
            sessionWarmedUp.set(false)
        }
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/$mangaSubString${if (page > 1) "/page/$page/" else "/"}?m_orderby=trending"
        return GET(url, headers)
    }

    override val mangaDetailsSelectorDescription = "div.description-summary div.summary__content p"

    override fun parseGenres(document: Document): List<Genre> = document.select("div.checkbox-group div.checkbox")
        .mapNotNull { cb ->
            val label = cb.selectFirst("label")?.text() ?: return@mapNotNull null
            val value = cb.selectFirst("input[type=checkbox]")?.`val`() ?: return@mapNotNull null
            if (value.matches(Regex("""^\d+[kKmM]?$"""))) return@mapNotNull null
            Genre(label, value)
        }

    // PROJECT FILTER
    class ProjectFilter : Filter.CheckBox(" Project Only", false)

    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }
        val base = super.getFilterList().list.toMutableList()
        base.add(0, ProjectFilter())
        base.add(1, Filter.Separator())
        return FilterList(base)
    }

    override fun searchLoadMoreRequest(page: Int, query: String, filters: FilterList): Request {
        val projectChecked = filters.filterIsInstance<ProjectFilter>().firstOrNull()?.state == true
        if (!projectChecked) return super.searchLoadMoreRequest(page, query, filters)

        val taxQueryIdx = filters.count { filter ->
            when (filter) {
                is AuthorFilter -> filter.state.isNotBlank()
                is ArtistFilter -> filter.state.isNotBlank()
                is YearFilter -> filter.state.isNotBlank()
                is GenreList -> filter.state.any { it.state }
                else -> false
            }
        }

        val superRequest = super.searchLoadMoreRequest(page, query, filters)
        val oldBody = superRequest.body as FormBody

        val newBody = FormBody.Builder().apply {
            for (i in 0 until oldBody.size) add(oldBody.name(i), oldBody.value(i))
            add("vars[tax_query][$taxQueryIdx][taxonomy]", "wp-manga-tag")
            add("vars[tax_query][$taxQueryIdx][field]", "slug")
            add("vars[tax_query][$taxQueryIdx][terms][0]", "project")
        }.build()

        return superRequest.newBuilder().post(newBody).build()
    }
}
