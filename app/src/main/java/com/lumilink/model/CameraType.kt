package com.lumilink.model

/**
 * The camera families LumiLink knows how to talk to. Each type selects a set of protocol
 * backends (see [com.lumilink.camera.CameraBackends]) — the UI and ViewModels stay identical
 * across cameras and only depend on the backend interfaces.
 *
 * Adding a camera = adding an enum value plus a backend implementation; no UI changes required.
 */
enum class CameraType(val displayName: String) {
    LUMIX_GX80("Lumix GX80"),
    OM_SYSTEM_PEN("OM System PEN"),
}
