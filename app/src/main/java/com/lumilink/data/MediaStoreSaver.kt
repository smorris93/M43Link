package com.lumilink.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

/**
 * Writes downloaded files into the device's shared media store (scoped storage), so they show up
 * in the Gallery / Files app. JPEGs go to Pictures/LumiLink; movies to Movies/LumiLink; RAW
 * files to Download/LumiLink (the Images collection only accepts standard image types).
 *
 * Uses the API 29+ `IS_PENDING` pattern: mark the entry pending, write bytes, then publish.
 */
class MediaStoreSaver(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    /**
     * @param write called with an output stream to stream the file bytes into.
     * @return the content Uri of the saved item.
     */
    fun save(
        filename: String,
        mimeType: String,
        asDownload: Boolean,
        write: (OutputStream) -> Unit,
    ): Uri = saveVideoAware(filename, mimeType, asDownload, asVideo = false, write)

    /** Same as [save], with movies routed to the device's video collection. */
    fun saveVideo(
        filename: String,
        mimeType: String,
        write: (OutputStream) -> Unit,
    ): Uri = saveVideoAware(filename, mimeType, asDownload = false, asVideo = true, write)

    private fun saveVideoAware(
        filename: String,
        mimeType: String,
        asDownload: Boolean,
        asVideo: Boolean,
        write: (OutputStream) -> Unit,
    ): Uri {
        val (collection, relativePath) = when {
            asVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_MOVIES}/M43Link"
            asDownload -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_DOWNLOADS}/M43Link"
            else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_PICTURES}/M43Link"
        }

        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, pending)
            ?: throw IOException("Couldn't create a media entry for $filename")

        try {
            resolver.openOutputStream(uri)?.use(write)
                ?: throw IOException("Couldn't open an output stream for $filename")
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // don't leave a half-written pending entry behind
            throw e
        }

        val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, publish, null, null)
        return uri
    }
}
