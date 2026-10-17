package com.lumilink.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Talks to an OM System (Olympus) camera over the OPC protocol — plain HTTP CGI endpoints on
 * the camera hotspot, same overall shape as the Lumix's `cam.cgi` but a different verb set
 * (`get_camprop.cgi`, `get_imglist.cgi`, `exec_takemotion.cgi`, …).
 *
 * Two things matter:
 *  - the camera sits at a fixed address (`http://192.168.0.10`) on its own hotspot, and
 *  - it expects requests to arrive with an OI.Share User-Agent, so we always send one.
 *
 * All app sockets are already bound to the camera network by [CameraNetworkManager], so these
 * requests automatically go over the camera Wi-Fi.
 */
class OpcClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    /**
     * Performs the GET and returns the raw body, throwing only on transport/HTTP failure.
     * [pathAndQuery] is everything after the host, e.g. `get_camprop.cgi?com=desc`.
     */
    suspend fun getRaw(pathAndQuery: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/$pathAndQuery")
            .header("User-Agent", USER_AGENT)
            .header("Connection", "Keep-Alive")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CameraException("Camera returned HTTP ${response.code}")
            }
            body
        }
    }

    /**
     * Non-throwing variant for probing: returns the HTTP status and raw body whatever the
     * camera thinks of the request. Only transport failures throw.
     */
    suspend fun tryGet(pathAndQuery: String, userAgent: String = USER_AGENT): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/$pathAndQuery")
                .header("User-Agent", userAgent)
                .header("Connection", "Keep-Alive")
                .build()
            httpClient.newCall(request).execute().use { response ->
                response.code to (response.body?.string().orEmpty())
            }
        }

    companion object {
        const val DEFAULT_BASE_URL = "http://192.168.0.10"

        /**
         * The UA string OI.Share sends. Older Olympus bodies reject requests that don't look
         * like their own app, so we keep sending it until the PEN is verified without it.
         */
        const val USER_AGENT = "OI.Share v2"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
