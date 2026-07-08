package app.shelfie.data

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Accepts any TLS certificate. Self-hosted Audiobookshelf servers commonly run
 * with self-signed or otherwise untrusted certificates (or plain HTTP), so the
 * app trusts whatever the user's server presents instead of failing the
 * connection.
 */
object InsecureTls {

    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val socketFactory: SSLSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }.socketFactory

    /** Relaxes certificate and hostname checks on an OkHttp client. */
    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .sslSocketFactory(socketFactory, trustManager)
        .hostnameVerifier { _, _ -> true }

    /**
     * Relaxes the process-wide HttpsURLConnection defaults, which Media3's
     * DefaultHttpDataSource uses for audio streaming.
     */
    fun installGlobal() {
        HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
    }
}
