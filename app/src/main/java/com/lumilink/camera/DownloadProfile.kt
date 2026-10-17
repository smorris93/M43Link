package com.lumilink.camera

import com.lumilink.model.CameraPhoto

/**
 * How a camera's files should be saved and how many may transfer at once. The Lumix serves
 * RW2 RAWs; the PEN serves ORF. Concurrency is per-camera because embedded camera servers
 * vary wildly in what they tolerate — the default stays conservative (sequential) until a
 * given body is measured on-device.
 */
interface DownloadProfile {
    /**
     * Maximum simultaneous downloads for this camera. 1 = today's proven-sequential
     * behaviour. Only raised after on-device measurement shows the camera stays healthy.
     */
    val maxConcurrentDownloads: Int

    /** MIME type for MediaStore when saving [photo]'s original file. */
    fun mimeTypeFor(photo: CameraPhoto): String

    /** File extension for [photo]'s original (e.g. "rw2" or "orf"). */
    fun fileExtensionFor(photo: CameraPhoto): String
}
