package com.lumilink.camera

/**
 * The after-Wi-Fi handshake a camera needs before it accepts commands or serves photos.
 * Called once the network layer reports the hotspot joined; idempotent — safe to call again
 * on every screen entry (the Lumix pairing handshake behaves this way; the PEN is expected
 * to need only a reachability probe).
 */
interface CameraConnection {
    suspend fun establishSession()
}
