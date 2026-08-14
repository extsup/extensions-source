package eu.kanade.tachiyomi.extension.id.kiryuubeta

import eu.kanade.tachiyomi.multisrc.natsuid.NatsuId
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

@Source
abstract class KiryuuBeta : NatsuId() {

    override fun OkHttpClient.Builder.customizeClient(): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(4, 1)).build().newBuilder()

    override fun chapterListRequest(manga: SManga): Request {
        val url = super.chapterListRequest(manga).url.newBuilder()
            .setQueryParameter("page", "1")
            .build()

        return GET(url, headers)
    }

}

private class RateLimitInterceptor(
    private val requests: Int,
    private val periodSeconds: Long,
) : Interceptor {
    private val minInterval = TimeUnit.SECONDS.toMillis(periodSeconds) / requests
    private var lastRequestTime = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val wait = minInterval - (now - lastRequestTime)
            if (wait > 0) Thread.sleep(wait)
            lastRequestTime = System.currentTimeMillis()
        }
        return chain.proceed(chain.request())
    }
}
