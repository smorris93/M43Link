package com.lumilink.di

import android.content.Context
import com.lumilink.camera.CameraBackends
import com.lumilink.camera.lumix.lumixBackends
import com.lumilink.camera.om.opcBackends
import com.lumilink.data.CameraCredentialsStore
import com.lumilink.data.MediaStoreSaver
import com.lumilink.model.CameraType
import com.lumilink.network.CameraNetworkManager
import com.lumilink.network.CameraWifiLocks

/**
 * Manual dependency-injection container — plain, greppable wiring instead of an annotation
 * framework (Hilt/Dagger). Holds the app-wide singletons; created once in [com.lumilink.LumiLinkApp].
 *
 * Multi-camera: each supported [CameraType] has a prebuilt [CameraBackends] set (its protocol
 * implementations). The active set is chosen by the camera type selected on the Connect tab,
 * so switching cameras is a preference change — ViewModels just receive interfaces.
 *
 * Why manual DI: for a solo project this keeps the whole object graph readable in one file with
 * zero code generation, which was a deliberate goal (see plan §"Code style & readability").
 */
class AppContainer(context: Context) {
    val credentialsStore: CameraCredentialsStore = CameraCredentialsStore(context)
    val cameraNetworkManager: CameraNetworkManager = CameraNetworkManager(context)

    /** Held while the live-view stream runs (see CameraWifiLocks). */
    val cameraWifiLocks: CameraWifiLocks = CameraWifiLocks(context)

    private val mediaStoreSaver: MediaStoreSaver = MediaStoreSaver(context)

    private val backendsByType: Map<CameraType, CameraBackends> = buildMap {
        put(CameraType.LUMIX, lumixBackends(mediaStoreSaver))
        put(CameraType.OM_SYSTEM, opcBackends(context, mediaStoreSaver))
    }

    /** True when LumiLink can talk to [type] today (used to gate the Connect button). */
    fun isSupported(type: CameraType): Boolean = backendsByType.containsKey(type)

    /**
     * The backend set for the currently selected camera. Only called once a connection flow for
     * a supported type is under way, so the missing-backend case is unreachable in practice.
     */
    fun backends(): CameraBackends =
        backendsByType[credentialsStore.currentCameraType()]
            ?: error("No backends implemented for ${credentialsStore.currentCameraType()}")
}
