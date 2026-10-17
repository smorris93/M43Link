package com.lumilink.camera.lumix

import com.lumilink.camera.CameraBackends
import com.lumilink.camera.CameraConnection
import com.lumilink.camera.CameraControl
import com.lumilink.camera.DownloadProfile
import com.lumilink.camera.LiveViewConfig
import com.lumilink.camera.PhotoLister
import com.lumilink.data.MediaStoreSaver
import com.lumilink.data.PhotoDownloader
import com.lumilink.data.PhotoRepository
import com.lumilink.model.CameraExposure
import com.lumilink.model.CameraPhoto
import com.lumilink.model.CameraStatus
import com.lumilink.model.CameraType
import com.lumilink.model.SettableProp
import com.lumilink.network.CameraClient
import com.lumilink.network.CameraException
import com.lumilink.network.OneDatagramJpegAssembler

/**
 * The Lumix GX80's protocol implementation — thin delegation onto the existing, proven
 * `cam.cgi`/DLNA classes. This file is the whole Lumix wiring in one readable place; the
 * PANASONIC behaviour is unchanged from the pre-refactor code.
 */

/** Pairing handshake: the GX80 requires an access-control grant before it accepts commands. */
private class LumixConnection(private val client: CameraClient) : CameraConnection {
    override suspend fun establishSession() = client.pair()
}

/** DLNA/UPnP photo browsing (SSDP discovery + recursive ContentDirectory Browse). */
private class LumixPhotoLister(private val repository: PhotoRepository) : PhotoLister {
    override suspend fun ensurePlaybackMode() = repository.ensurePlaybackMode()
    override suspend fun listPhotos(): List<CameraPhoto> = repository.listPhotos()
}

/** Remote control over `cam.cgi` (`mode=camcmd`, `mode=camctrl`, `mode=startstream`, …). */
private class LumixControl(private val client: CameraClient) : CameraControl {
    override suspend fun prepareRecordMode() {
        // The GX80 only accepts commands after pairing; pair() is idempotent (an earlier
        // session returns already-connected), so it's safe on every screen entry.
        client.pair()
        client.enterRecordMode()
    }

    override suspend fun enterPlaybackMode() = client.enterPlaybackMode()
    override suspend fun capture() = client.capture()
    override suspend fun touchAf(x: Int, y: Int) = client.touchAf(x, y)
    override suspend fun startVideoRecord() = client.startVideoRecord()
    override suspend fun stopVideoRecord() = client.stopVideoRecord()
    override suspend fun fetchStatus(): CameraStatus = client.fetchStatus()
    override suspend fun fetchExposure(): CameraExposure = client.fetchExposure()
    override suspend fun startLiveViewStream(port: Int) = client.startStream(port)
    override suspend fun stopLiveViewStream() = client.stopStream()

    /** The GX80 exposes no settable settings over Wi-Fi (dial-controlled in Manual mode). */
    override suspend fun fetchSettableProps(): List<SettableProp> = emptyList()

    override suspend fun setProp(prop: SettableProp, value: String): String? =
        throw CameraException("This camera doesn't expose settable settings over Wi-Fi")
}

/** The GX80 pushes live view to port 49152, one full JPEG per UDP datagram. */
private class LumixLiveViewConfig(override val port: Int = DEFAULT_PORT) : LiveViewConfig {
    override fun newFrameAssembler() = OneDatagramJpegAssembler()

    private companion object {
        const val DEFAULT_PORT = 49152
    }
}

/** RW2 RAWs; sequential downloads only (the GX80's server dislikes concurrency). */
private class LumixDownloadProfile : DownloadProfile {
    override val maxConcurrentDownloads: Int get() = 1
    override fun mimeTypeFor(photo: CameraPhoto): String =
        if (photo.isRaw) "image/x-panasonic-rw2" else "image/jpeg"

    override fun fileExtensionFor(photo: CameraPhoto): String =
        if (photo.isRaw) "rw2" else "jpg"
}

/** Builds the full GX80 backend set. */
fun lumixBackends(mediaStoreSaver: MediaStoreSaver): CameraBackends {
    val client = CameraClient()
    val downloadProfile = LumixDownloadProfile()
    return CameraBackends(
        type = CameraType.LUMIX_GX80,
        connection = LumixConnection(client),
        photoLister = LumixPhotoLister(PhotoRepository(client)),
        control = LumixControl(client),
        liveView = LumixLiveViewConfig(),
        downloadProfile = downloadProfile,
        photoDownloader = PhotoDownloader(mediaStoreSaver, downloadProfile),
    )
}
