package com.lumilink.data

import android.util.Log
import com.lumilink.camera.DownloadProfile
import com.lumilink.model.CameraPhoto
import android.net.Uri
import com.lumilink.network.CameraException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads a photo's original file from the camera and saves it via [MediaStoreSaver], reporting
 * progress. RAW files over the camera's 2.4 GHz Wi-Fi are slow, hence the generous read timeout.
 */
class PhotoDownloader(
    private val mediaStoreSaver: MediaStoreSaver,
    private val downloadProfile: DownloadProfile,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    /**
     * Download a photo, retrying a few times — the camera's embedded server often drops the first
     * connection (especially right after a mode switch), which is why a manual retry usually works.
     *
     * @param onProgress fraction 0f..1f for this single file (best-effort; only when size known).
     */
    suspend fun download(photo: CameraPhoto, onProgress: (Float) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                try {
                    downloadOnce(photo, onProgress)
                    return@withContext
                } catch (e: Exception) {
                    attempt++
                    if (attempt >= MAX_ATTEMPTS) throw e
                    Log.w(TAG, "Download attempt $attempt for ${photo.title} failed: ${e.message}; retrying")
                    delay(RETRY_DELAY_MS)
                }
            }
        }

    private suspend fun downloadOnce(photo: CameraPhoto, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(photo.originalUrl).apply {
                downloadProfile.downloadUserAgent?.let { header("User-Agent", it) }
            }.build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw CameraException("Download failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw CameraException("Empty download response")
                val totalBytes = photo.sizeBytes ?: body.contentLength().takeIf { it > 0 }

                val filename = fileNameFor(photo)
                val mimeType = downloadProfile.mimeTypeFor(photo)

                val save: ((OutputStream) -> Unit) -> Uri =
                    if (downloadProfile.isVideo(photo)) {
                        { write -> mediaStoreSaver.saveVideo(filename, mimeType, write) }
                    } else {
                        { write -> mediaStoreSaver.save(filename, mimeType, photo.isRaw, write) }
                    }
                save { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (totalBytes != null && totalBytes > 0) {
                                onProgress((copied.toFloat() / totalBytes).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                onProgress(1f)
            }
        }

    private fun fileNameFor(photo: CameraPhoto): String {
        val extension = downloadProfile.fileExtensionFor(photo)
        return "${photo.title}.$extension"
    }

    private companion object {
        const val TAG = "PhotoDownloader"
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1_000L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // RAW files can be large over slow Wi-Fi
            .build()
    }
}
