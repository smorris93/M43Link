package com.lumilink.model

/**
 * The camera brands M43Link knows how to talk to. Each type selects a set of protocol
 * backends (see [com.lumilink.camera.CameraBackends]) — the UI and ViewModels stay identical
 * across cameras and only depend on the backend interfaces.
 *
 * Brand-level by design: the Lumix implementation (cam.cgi) covers Panasonic's Wi-Fi remote
 * bodies (verified on the GX80) and the OM System implementation covers the OPC protocol
 * (verified on the PEN) — model-specific quirks are handled inside the backends, not here.
 * NOTE: the DataStore may hold the original model-specific names ("LUMIX_GX80" /
 * "OM_SYSTEM_PEN") from earlier versions — CameraCredentialsStore maps them on read.
 */
enum class CameraType(val displayName: String) {
    LUMIX("Lumix"),
    OM_SYSTEM("OM System"),
}
