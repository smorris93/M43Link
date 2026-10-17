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
import com.lumilink.model.SettableProp
import com.lumilink.model.CameraType
import com.lumilink.network.CamPropParser
import com.lumilink.network.CameraException
import com.lumilink.network.OpcClient
import com.lumilink.network.RtpJpegAssembler
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

/**
 * Remote control over the OPC CGIs: `switch_cammode.cgi` (mode), `exec_takemotion.cgi`
 * (shutter / AF point), `exec_takemisc.cgi` (live view / movie), `get_camprop.cgi`
 * (exposure properties). Verified verbs so far are the ones from the documented examples;
 * the PEN's replies (including its errorcode XML) tell us quickly on-device when a guess
 * is wrong, and every reply of interest is captured as a fixture.
 */
private class OpcControl(
    private val client: OpcClient,
    private val connection: OpcConnection,
    private val fixtureDir: File?,
) : CameraControl {

    /**
     * Settable settings as of the last desclist poll (prime on [fetchSettableProps]; the
     * exposure poll keeps them current).
     */
    @Volatile
    private var settableProps: List<SettableProp> = emptyList()

    /** The camera's takemode as of the last desclist poll — drives stepper gating. */
    @Volatile
    private var takemode: String? = null

    override suspend fun prepareRecordMode() {
        // Reachability probe first (mirrors the Lumix flow, where the handshake also lives
        // inside prepareRecordMode), then switch to record mode. lvqty picks the live-view
        // resolution (320x240 = the documented example size; the AF point space matches it).
        connection.establishSession()
        client.getRaw("switch_cammode.cgi?mode=rec&lvqty=0320x0240")
        // Give the camera a beat to settle into rec mode before the streamer asks for live view.
        delay(MODE_SWITCH_DELAY_MS)
    }

    override suspend fun enterPlaybackMode() {
        client.getRaw("switch_cammode.cgi?mode=play")
    }

    override suspend fun capture() {
        client.getRaw("exec_takemotion.cgi?com=starttake")
    }

    /**
     * Touch-to-focus at ([x], [y]) in the interface's 0..1000 space, mapped into the live view's
     * pixel coordinates (320x240 at our lvqty) as the camera expects (`point=0160x0120` style).
     */
    override suspend fun touchAf(x: Int, y: Int) {
        val px = (x * LIVE_VIEW_WIDTH / 1000).coerceIn(0, LIVE_VIEW_WIDTH)
        val py = (y * LIVE_VIEW_HEIGHT / 1000).coerceIn(0, LIVE_VIEW_HEIGHT)
        client.getRaw("exec_takemotion.cgi?com=assignafframe&point=${"%04d".format(px)}x${"%04d".format(py)}")
    }

    /**
     * Remote movie recording is descoped for the PEN. On-device probing found no working verb:
     * `startmovie`/`stopmovie` return errorcode 1004 (not implemented), and `starttake` in
     * movie mode accepts the connection but never replies (hangs the HTTP channel) without
     * recording. Findings in docs/PROJECT_CONTEXT.md; revisit with an OI.Share traffic
     * capture (M4's tooling) if this becomes a must-have.
     */
    override suspend fun startVideoRecord() =
        throw CameraException("Remote video recording isn't supported on this camera yet")

    override suspend fun stopVideoRecord() {
        // Nothing to stop — startVideoRecord always refuses.
    }

    /**
     * The PEN's readable status surface isn't mapped yet (battery/SD unknown — probed on-device
     * later). Mode comes from our own tracking. Unknown fields stay null and the UI shows "—".
     */
    override suspend fun fetchStatus(): CameraStatus =
        CameraStatus(mode = "rec")

    /**
     * Fetch the full property list and map the exposure read-outs from it. The PEN reports
     * ISO (`isospeedvalue`), aperture (`focalvalue`), shutter (`shutspeedvalue`) and exposure
     * compensation (`expcomp`) with live values — everything the GX80 refused to expose.
     * The desclist body is captured once as a fixture for offline parser work.
     */
    override suspend fun fetchSettableProps(): List<SettableProp> {
        if (settableProps.isEmpty()) {
            // Prime the cache with a fresh desclist (the exposure poll keeps it current).
            exposureFrom(client.getRaw(DESCLIST_QUERY))
        }
        return settableProps
    }

    override suspend fun setProp(prop: SettableProp, value: String): String? {
        require(value in prop.options) { "$value is not one of the camera's allowed values" }
        client.postRaw("set_camprop.cgi?com=set&propname=${prop.name}", value)
        // Confirm with a single-property get (~60 B) instead of the full desclist, so the UI
        // round-trip stays snappy; a rejected set simply reports the unchanged value.
        val confirmed = client.tryGet("get_camprop.cgi?com=get&propname=${prop.name}")
        if (confirmed.first != 200) return null
        return VALUE_REGEX.find(confirmed.second)?.groupValues?.getOrNull(1)
    }

    override suspend fun fetchExposure(): CameraExposure {
        val body = client.getRaw("get_camprop.cgi?com=desc&propname=desclist")
        if (!desclistCaptured) {
            desclistCaptured = true
            val dir = fixtureDir ?: return exposureFrom(body)
            runCatching {
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    File(dir, "desclist_rec.xml").writeText(body)
                }
                Log.i(TAG, "Captured desclist_rec.xml (${body.length} chars)")
            }
        }
        return exposureFrom(body)
    }

    override suspend fun startLiveViewStream(port: Int) {
        client.getRaw("exec_takemisc.cgi?com=startliveview&port=$port")
    }

    override suspend fun stopLiveViewStream() {
        runCatching { client.getRaw("exec_takemisc.cgi?com=stopliveview") }
            .onFailure { Log.i(TAG, "stopliveview failed (ignored): ${it.message}") }
    }

    private var desclistCaptured = false

    /** Builds the exposure read-out from a desclist body; unknown fields stay null. */
    private fun exposureFrom(desclistBody: String): CameraExposure {
        val props = CamPropParser.parseDesclist(desclistBody).associateBy { it.name }
        takemode = props["takemode"]?.value ?: takemode
        settableProps = SETTABLE_PROPS.mapNotNull { (name, displayName) ->
            val prop = props[name] ?: return@mapNotNull null
            if (!prop.canSet || prop.options.isEmpty()) return@mapNotNull null
            SettableProp(
                name = prop.name,
                displayName = displayName,
                rawValue = prop.value,
                displayValue = displayValueOf(prop.name, prop.value),
                options = prop.options,
                canAdjust = isAdjustableInMode(name, takemode),
            )
        }
        return CameraExposure(
            iso = props["isospeedvalue"]?.value,
            aperture = props["focalvalue"]?.value,
            shutter = props["shutspeedvalue"]?.value?.let(::formatShutter),
            exposureComp = props["expcomp"]?.value,
        )
    }

    /**
     * The PEN's shutter values are either plain numbers (denominators: `250` → 1/250s,
     * `2.5` → 1/2.5s) or seconds with a quote suffix (`60"` → 60s).
     */
    private fun formatShutter(value: String): String =
        if (value.endsWith("\"")) "${value.dropLast(1)}s" else "1/$value"

    companion object {
        private const val TAG = "OpcControl"
        private const val MODE_SWITCH_DELAY_MS = 500L
        private const val LIVE_VIEW_WIDTH = 320
        private const val LIVE_VIEW_HEIGHT = 240
        private const val DESCLIST_QUERY = "get_camprop.cgi?com=desc&propname=desclist"

        /** The settings surfaced as steppers, in display order. */
        private val SETTABLE_PROPS = linkedMapOf(
            "isospeedvalue" to "ISO",
            "focalvalue" to "Aperture",
            "shutspeedvalue" to "Shutter",
            "expcomp" to "EV",
        )

        /**
         * Whether the user can adjust [prop] in the camera's current [takemode]: shutter and
         * aperture are automatic outside S/M and A/M respectively (the camera rejects such
         * sets with errorcode 3001 — measured on-device).
         */
        private fun isAdjustableInMode(prop: String, takemode: String?): Boolean = when {
            prop == "shutspeedvalue" -> takemode == "S" || takemode == "M"
            prop == "focalvalue" -> takemode == "A" || takemode == "M"
            else -> true
        }

        /** Pulls the value out of a single-property get_camprop reply. */
        private val VALUE_REGEX = Regex("<value>([^<]+)</value>")

        /** Formats a property value for display (wire value in, pretty value out). */
        private fun displayValueOf(name: String, value: String): String = when (name) {
            "focalvalue" -> "f/$value"
            "shutspeedvalue" ->
                if (value.endsWith("\"")) "${value.dropLast(1)}s" else "1/$value"
            else -> value
        }
    }
}

/**
 * Live-view port: 37789 is the port used in the documented OPC examples; the PEN's actual
 * value (and frame format) is confirmed on-device in Milestone 3.
 */
private class OpcLiveViewConfig(override val port: Int = DEFAULT_PORT) : LiveViewConfig {
    override fun newFrameAssembler() = RtpJpegAssembler()

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
    val fixtureDir = File(context.filesDir, "opc_fixtures")
    val connection = OpcConnection(client, fixtureDir)
    return CameraBackends(
        type = CameraType.OM_SYSTEM_PEN,
        connection = connection,
        photoLister = OpcPhotoLister(client),
        control = OpcControl(client, connection, fixtureDir),
        liveView = OpcLiveViewConfig(),
        downloadProfile = downloadProfile,
        photoDownloader = PhotoDownloader(mediaStoreSaver, downloadProfile),
        supportsVideoRecord = false, // no working movie verb found — see OpcControl.startVideoRecord
    )
}
