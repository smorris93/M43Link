package com.lumilink.camera

import com.lumilink.model.CameraPhoto

/**
 * Lists the photos stored on the camera, in whatever way that camera family exposes them
 * (the Lumix speaks DLNA/UPnP; the OM System PEN serves an XML directory listing over HTTP).
 * Implementations handle their own mode switching and handshake — callers just call.
 */
interface PhotoLister {
    /**
     * Re-assert playback mode — cheap, and needed if another screen put the camera in record
     * mode. Safe to call every time the gallery becomes visible.
     */
    suspend fun ensurePlaybackMode()

    suspend fun listPhotos(): List<CameraPhoto>
}
