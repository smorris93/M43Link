package com.lumilink.camera

import com.lumilink.data.PhotoDownloader
import com.lumilink.model.CameraType

/**
 * The complete set of protocol implementations for one camera type. Built once per type in
 * the DI container and handed out as a set — ViewModels receive these interfaces and never a
 * concrete client, which is what keeps a second camera cheap to add.
 */
class CameraBackends(
    val type: CameraType,
    val connection: CameraConnection,
    val photoLister: PhotoLister,
    val control: CameraControl,
    val liveView: LiveViewConfig,
    val downloadProfile: DownloadProfile,
    val photoDownloader: PhotoDownloader,
)
