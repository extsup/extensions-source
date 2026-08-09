package eu.kanade.tachiyomi.extension.id.ikirubeta

import eu.kanade.tachiyomi.multisrc.natsuid.NatsuId
import keiyoushi.annotation.Source
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

@Source
abstract class IkiruBeta : NatsuId() {

    override fun OkHttpClient.Builder.customizeClient(): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(12, 3)).build().newBuilder()

    override fun transformJsonResponse(responseBody: String): String {
        val jsonStart = responseBody.indexOfFirst { it == '{' || it == '[' }
        return if (jsonStart >= 0) responseBody.substring(jsonStart) else responseBody
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
