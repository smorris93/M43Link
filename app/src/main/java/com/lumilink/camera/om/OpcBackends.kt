package com.lumilink.camera.om

import android.content.Context
import android.util.Log
import com.lumilink.camera.CameraBackends
import com.lumilink.camera.CameraConnection
import com.lumilink.camera.CameraControl
import com.lumilink.camera.DownloadProfile
import com.lumilink.camera.LiveViewConfig
import com.lumilink.camera.PhotoLister
import com.lumilink.data.MediaStoreSaver
import com.lumilink.data.PhotoDownloader
import com.lumilink.model.CameraExposure
import com.lumilink.model.CameraPhoto
import com.lumilink.model.CameraStatus
import com.lumilink.model.CameraType
import com.lumilink.network.CameraException
import com.lumilink.network.OpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The OM System PEN's protocol implementation. Milestone 1 delivers the connection (a
 * reachability probe — no pairing handshake is expected in Private Wi-Fi mode); photo browsing
 * (M2), control/live view (M3) and profiles (M4) follow. Until then those backends fail with
 * an explicit "arrives in Milestone N" message rather than pretending to work.
 *
 * This file is the whole PEN wiring in one readable place, mirroring `camera/lumix/LumixBackends`.
 */

/**
 * Probes the camera's HTTP API until it answers, then keeps the session open. Older OM System
 * bodies need no handshake beyond Private-mode Wi-Fi; if the PEN turns out to want a pairing
 * step, this is where it goes.
 */
private class OpcConnection(
    private val client: OpcClient,
    /** Where probe responses are dumped for offline test fixtures; null disables capture. */
    private val fixtureDir: File?,
) : CameraConnection {

    override suspend fun establishSession() {
        val startedAt = System.currentTimeMillis()
        var lastStatus = "unreachable"
        while (System.currentTimeMillis() - startedAt < PROBE_TIMEOUT_MS) {
            for (probe in PROBES) {
                val (code, body) = try {
                    client.tryGet(probe.query)
                } catch (e: Exception) {
                    // Camera not reachable yet (server still coming up after Wi-Fi associates).
                    Log.i(TAG, "Camera not reachable yet: ${e.message}")
                    lastStatus = "unreachable"
                    break
                }
                Log.i(TAG, "${probe.query} -> HTTP $code, ${body.length} chars: ${body.take(100)}")
                if (code == 200 && body.isNotBlank()) {
                    Log.i(TAG, "Camera answered via ${probe.query} (${body.length} chars)")
                    captureFixtures()
                    return
                }
                lastStatus = "HTTP $code on ${probe.query}"
            }
            delay(PROBE_RETRY_DELAY_MS)
        }
        throw CameraException(
            "Couldn't reach the camera's control API ($lastStatus). Make sure its Wi-Fi is " +
                "running and set to Private mode, then tap Try again.",
        )
    }

    /**
     * Best-effort capture of the probe exchanges as on-device fixtures for the M2/M3 parsers
     * and their JVM tests — never fails the connection.
     */
    private suspend fun captureFixtures() {
        val dir = fixtureDir ?: return
        runCatching {
            withContext(Dispatchers.IO) { dir.mkdirs() }
            for (probe in PROBES) {
                val (code, body) = runCatching { client.tryGet(probe.query) }.getOrNull() ?: continue
                if (code == 200 && body.isNotBlank()) {
                    withContext(Dispatchers.IO) { File(dir, probe.fixture).writeText(body) }
                    Log.i(TAG, "Captured ${probe.fixture} (${body.length} chars)")
                }
            }
        }.onFailure { Log.i(TAG, "Fixture capture skipped: ${it.message}") }
    }

    private data class Probe(val query: String, val fixture: String)

    private companion object {
        const val TAG = "OpcConnection"
        const val PROBE_TIMEOUT_MS = 12_000L
        const val PROBE_RETRY_DELAY_MS = 1_500L

        /** Candidate entry points, tried in order until one answers. Diagnoses mode/UA issues. */
        val PROBES = listOf(
            Probe("get_camprop.cgi?com=desc&propname=desclist", "desclist.xml"),
            Probe("get_imglist.cgi?DIR=/DCIM", "imglist_dcim.xml"),
            Probe("get_imglist.cgi?DIR=/", "imglist_root.xml"),
            Probe("get_camprop.cgi?com=get&propname=takemode", "camprop_takemode.xml"),
        )
    }
}

/** Placeholder until Milestone 3 — shutter/live view/exposure over the OPC control CGIs. */
private class OpcControl(
    private val connection: OpcConnection,
) : CameraControl {
    private fun notYet(): Nothing =
        throw CameraException("OM System PEN remote control arrives in Milestone 3")

    override suspend fun prepareRecordMode() {
        // Milestone 1 verification: prove the camera's API answers before failing. This mirrors
        // the Lumix flow, where the handshake also lives inside prepareRecordMode().
        connection.establishSession()
        notYet()
    }
    override suspend fun enterPlaybackMode() = notYet()
    override suspend fun capture() = notYet()
    override suspend fun touchAf(x: Int, y: Int) = notYet()
    override suspend fun startVideoRecord() = notYet()
    override suspend fun stopVideoRecord() = notYet()
    override suspend fun fetchStatus(): CameraStatus = notYet()
    override suspend fun fetchExposure(): CameraExposure = notYet()
    override suspend fun startLiveViewStream(port: Int) = notYet()
    override suspend fun stopLiveViewStream() = notYet()
}

/**
 * Live-view port: 37789 is the port used in the documented OPC examples; the PEN's actual
 * value (and frame format) is confirmed on-device in Milestone 3.
 */
private data class OpcLiveViewConfig(override val port: Int = DEFAULT_PORT) : LiveViewConfig {
    private companion object {
        const val DEFAULT_PORT = 37789
    }
}

/**
 * ORF RAWs; strictly sequential downloads. Measured on-device (10-file, 85 MB batch):
 * x2 gave no throughput gain (29.9 s vs 29.7 s) AND the camera's server silently crossed
 * response streams between concurrent transfers, corrupting the saved files. Never raise
 * this without re-verifying integrity, not just speed.
 */
private class OpcDownloadProfile : DownloadProfile {
    override val maxConcurrentDownloads: Int get() = 1
    override val downloadUserAgent: String? get() = OpcClient.USER_AGENT
    override fun mimeTypeFor(photo: CameraPhoto): String =
        if (photo.isRaw) "image/x-olympus-orf" else "image/jpeg"

    override fun fileExtensionFor(photo: CameraPhoto): String =
        if (photo.isRaw) "orf" else "jpg"
}

/** Builds the full PEN backend set. */
fun opcBackends(context: Context, mediaStoreSaver: MediaStoreSaver): CameraBackends {
    val client = OpcClient()
    val downloadProfile = OpcDownloadProfile()
    val connection = OpcConnection(client, File(context.filesDir, "opc_fixtures"))
    return CameraBackends(
        type = CameraType.OM_SYSTEM_PEN,
        connection = connection,
        photoLister = OpcPhotoLister(client),
        control = OpcControl(connection),
        liveView = OpcLiveViewConfig(),
        downloadProfile = downloadProfile,
        photoDownloader = PhotoDownloader(mediaStoreSaver, downloadProfile),
    )
}
