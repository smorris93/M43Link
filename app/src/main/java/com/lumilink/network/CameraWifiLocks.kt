package com.lumilink.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Holds the Wi-Fi locks that keep the radio awake while the live-view UDP stream runs.
 * Without these, Android's Wi-Fi power save buffers the camera's packets at the AP and
 * delivers them in bursts — the live view freezes and then "catches up" (observed on-device
 * with the PEN; the locks are held for every camera's stream since all are UDP-based).
 *
 *  - a low-latency [WifiManager.WifiLock] disables power save for the duration, and
 *  - a [WifiManager.MulticastLock] stops the radio from filtering the stream's packets.
 *
 * Reference-counted off; acquire/release pairs are idempotent.
 */
class CameraWifiLocks(context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(WifiManager::class.java)

    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun acquire() {
        // A lock failure (missing permission, OEM quirks) must never kill the live-view
        // stream — the stream degrades to unlocked-but-working rather than erroring.
        if (wifiLock == null) {
            wifiLock = runCatching {
                wifiManager?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "LumiLink:liveView",
                )?.also { it.acquire() }
            }.getOrNull()
        }
        if (multicastLock == null) {
            multicastLock = runCatching {
                wifiManager?.createMulticastLock("LumiLink:liveView")?.also { it.acquire() }
            }.getOrNull()
        }
        Log.i(TAG, "Wi-Fi locks acquired (wifi=${wifiLock != null}, multicast=${multicastLock != null})")
    }

    fun release() {
        runCatching { wifiLock?.release() }
        runCatching { multicastLock?.release() }
        wifiLock = null
        multicastLock = null
        Log.i(TAG, "Wi-Fi locks released")
    }

    private companion object {
        const val TAG = "CameraWifiLocks"
    }
}
