package com.lumilink

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lumilink.di.AppContainer
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Application entry point. Android instantiates this once, before any Activity. It builds and
 * holds the manual DI container ([AppContainer]) for the app's lifetime; screens reach it via
 * `context.applicationContext as LumiLinkApp`.
 *
 * It also implements [ImageLoaderFactory] to give Coil (the thumbnail loader) a client tuned for
 * the camera's slow embedded Wi-Fi server: generous timeouts so slow thumbnails complete instead
 * of timing out, plus memory + disk caches so a browsed grid doesn't re-fetch.
 */
class LumiLinkApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader {
        // The camera's embedded server chokes on many simultaneous connections (measured: 12 at
        // once caused connect timeouts). A single thumbnail is tiny (~4.5 KB, ~50 ms), so a low
        // concurrency loads a screenful fast without overwhelming the camera.
        // Keep concurrency low: the camera's tiny server drops connections when hit too hard
        // (measured connect-timeouts/resets under load). 3 fresh connections is a sustainable rate.
        val dispatcher = Dispatcher().apply {
            maxRequests = 3
            maxRequestsPerHost = 3
        }
        val cameraTunedClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            // The camera's server mishandles keep-alive: reusing a connection hangs until timeout.
            // Force a fresh connection per request (each is only ~50 ms) so nothing stalls.
            // The OM System PEN also expects OI.Share's user-agent on every request — harmless
            // to send to the Lumix, which doesn't check.
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "OI.Share v2")
                        .header("Connection", "close")
                        .build(),
                )
            }
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
            // Fail fast on a stuck connect so the slot frees up quickly instead of blocking ~5 s.
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(cameraTunedClient)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("thumbnails")).maxSizeBytes(100L * 1024 * 1024).build() }
            // The camera sends no HTTP cache headers, so Coil re-downloads every thumbnail on each
            // view. Ignore those headers and cache thumbnails ourselves — the camera's photos don't
            // change, so cached thumbnails are always valid.
            .respectCacheHeaders(false)
            .build()
    }
}
