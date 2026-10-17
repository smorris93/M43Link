package com.lumilink.camera.om

import android.util.Log
import com.lumilink.camera.PhotoLister
import com.lumilink.model.CameraPhoto
import com.lumilink.network.ImgListParser
import com.lumilink.network.OpcClient
import com.lumilink.network.OpcListEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Lists the photos on an OM System camera by walking `get_imglist.cgi` from `/DCIM`
 * (the VER_100 CSV format — see [ImgListParser]), recursing into directory entries.
 *
 * Camera-specific URLs per file (all verified against the reference implementations and the
 * on-device probe):
 *  - thumbnail: `get_thumbnail.cgi?DIR=<path>`   (small, for the grid)
 *  - preview:   `get_screennail.cgi?DIR=<path>`  (medium, for the full-screen viewer)
 *  - original:  the plain path `http://192.168.0.10<fullPath>` (full file for download)
 */
class OpcPhotoLister(
    private val client: OpcClient,
) : PhotoLister {

    /**
     * Switch the camera to playback mode before browsing. Best-effort, matching the camera's
     * own app flow: a camera already in play mode may not re-acknowledge the switch, and the
     * listing itself will reveal whether it worked.
     */
    override suspend fun ensurePlaybackMode() = withContext(Dispatchers.IO) {
        runCatching { client.getRaw(SWITCH_PLAY) }
            .onFailure { Log.i(TAG, "switch to play failed (continuing): ${it.message}") }
        delay(MODE_SWITCH_DELAY_MS)
    }

    override suspend fun listPhotos(): List<CameraPhoto> = withContext(Dispatchers.IO) {
        ensurePlaybackMode()

        val photos = mutableListOf<CameraPhoto>()
        val visited = mutableSetOf<String>()
        // Each queue element is a directory path plus its depth in the tree.
        val queue = ArrayDeque<Pair<String, Int>>().apply { add(ROOT_DIR to 0) }
        visited.add(ROOT_DIR)
        var dirsBrowsed = 0

        while (queue.isNotEmpty() && photos.size < MAX_PHOTOS && dirsBrowsed < MAX_DIRS) {
            val (dir, depth) = queue.removeFirst()
            val entries = try {
                dirsBrowsed++
                ImgListParser.parse(client.getRaw("$LIST_DIR$dir"))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping $dir: ${e.message}")
                continue
            }
            for (entry in entries) {
                if (entry.isDirectory) {
                    if (depth < MAX_DEPTH && visited.add(entry.fullPath)) {
                        queue.add(entry.fullPath to depth + 1)
                    }
                } else {
                    photoFrom(entry)?.let {
                        if (photos.size < MAX_PHOTOS) photos.add(it)
                    }
                }
            }
        }
        Log.i(TAG, "Listed ${photos.size} photos across $dirsBrowsed directories")
        photos
    }

    /** Maps a listing record to a gallery photo, or null for files we don't handle. */
    private fun photoFrom(entry: OpcListEntry): CameraPhoto? {
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        val isVideo = extension in VIDEO_EXTENSIONS
        if (!isVideo && extension !in IMAGE_EXTENSIONS) {
            Log.i(TAG, "Skipping ${entry.name} (.$extension)")
            return null
        }
        return CameraPhoto(
            id = entry.fullPath,
            title = entry.name.substringBeforeLast('.'),
            isRaw = extension == "orf",
            thumbnailUrl = OpcClient.urlFor(OpcClient.DEFAULT_BASE_URL, "$THUMBNAIL_DIR${entry.fullPath}"),
            originalUrl = OpcClient.urlFor(OpcClient.DEFAULT_BASE_URL, entry.fullPath),
            sizeBytes = entry.sizeBytes,
            // Screennail serves the movie's first frame too (per the OPC spec), which also
            // makes it the preview for videos.
            previewUrl = OpcClient.urlFor(OpcClient.DEFAULT_BASE_URL, "$SCREENNAIL_DIR${entry.fullPath}"),
            isVideo = isVideo,
            extension = extension,
        )
    }

    companion object {
        private const val TAG = "OpcPhotoLister"
        private const val ROOT_DIR = "/DCIM"
        private const val LIST_DIR = "get_imglist.cgi?DIR="
        private const val THUMBNAIL_DIR = "get_thumbnail.cgi?DIR="
        private const val SCREENNAIL_DIR = "get_screennail.cgi?DIR="
        private const val SWITCH_PLAY = "switch_cammode.cgi?mode=play"

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "orf")

        /** Movie files (the same set Olympus View handles) — shown in the gallery, downloadable. */
        private val VIDEO_EXTENSIONS = setOf("mov", "mp4", "m4v", "avi", "mts", "m2ts")

        private const val MODE_SWITCH_DELAY_MS = 500L
        private const val MAX_PHOTOS = 2000
        private const val MAX_DIRS = 300
        private const val MAX_DEPTH = 8
    }
}
