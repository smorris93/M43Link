package com.lumilink.ui.control

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumilink.camera.CameraControl
import com.lumilink.camera.LiveViewConfig
import com.lumilink.di.AppContainer
import com.lumilink.model.CameraExposure
import com.lumilink.model.CameraStatus
import com.lumilink.model.SettableProp
import com.lumilink.network.CameraException
import com.lumilink.network.CameraWifiLocks
import com.lumilink.network.LiveViewStreamer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the Control screen (MVP2): puts the camera in record mode, polls its state, and issues
 * shutter / autofocus / video-record commands. Live view and exposure numbers arrive in MVP3 with
 * the MJPEG stream, so the exposure grid stays a placeholder for now.
 *
 * Polling is lifecycle-driven: the screen calls [start] when it appears and [stop] when it leaves,
 * so we don't hammer the camera while the user is on another tab.
 */
class ControlViewModel(
    private val control: CameraControl,
    private val liveView: LiveViewConfig,
    wifiLocks: CameraWifiLocks?,
    /** Whether this camera supports remote video recording (REC greys out otherwise). */
    val supportsVideoRecord: Boolean = true,
) : ViewModel() {

    data class UiState(
        /** True once the camera has acknowledged record mode; controls are enabled only then. */
        val recordModeReady: Boolean = false,
        /** True once live-view frames are arriving. */
        val liveViewActive: Boolean = false,
        val status: CameraStatus = CameraStatus.UNKNOWN,
        val exposure: CameraExposure = CameraExposure.UNKNOWN,
        /** Remote-settable settings (ISO/aperture/shutter/EV on the PEN; empty on the GX80). */
        val settableProps: List<SettableProp> = emptyList(),
        val busy: Boolean = false,
        /** Transient one-shot text for a snackbar (capture confirmation, errors). */
        val message: String? = null,
    ) {
        val videoRecording: Boolean get() = status.recording
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** The latest decoded live-view frame, or null before the stream is up / after it stops. */
    private val _liveFrame = MutableStateFlow<ImageBitmap?>(null)
    val liveFrame: StateFlow<ImageBitmap?> = _liveFrame.asStateFlow()

    private val streamer = LiveViewStreamer(control, liveView, wifiLocks)
    private var pollJob: Job? = null
    private var streamJob: Job? = null
    private var decodeFailures = 0

    /** Called when the Control screen becomes visible. Enters record mode, polls state, streams live view. */
    fun start() {
        startLiveView()
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            try {
                // Handshake + record mode in one idempotent call (the backend pairs if the camera
                // needs it, and re-asserting is harmless on a camera that's already there).
                control.prepareRecordMode()
                _uiState.update { it.copy(recordModeReady = true) }
            } catch (e: Exception) {
                // The camera may be asleep or still in playback; surface it but keep polling so a
                // recovered camera heals the screen without a manual retry. CameraExceptions carry
                // user-ready text (pairing failures, per-camera "arrives in Milestone N" stubs).
                Log.w(TAG, "enterRecordMode failed: ${e.message}")
                _uiState.update {
                    it.copy(
                        message = if (e is CameraException) {
                            e.message
                        } else {
                            "Couldn't switch the camera to record mode — wake it and try again."
                        },
                    )
                }
            }
            while (isActive) {
                try {
                    val status = control.fetchStatus()
                    _uiState.update {
                        // A successful poll implies record mode is live even if the earlier switch errored.
                        it.copy(status = status, recordModeReady = it.recordModeReady || status.mode == "rec")
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "status poll failed: ${e.message}")
                }
                try {
                    val exposure = control.fetchExposure()
                    val props = control.fetchSettableProps()
                    _uiState.update { it.copy(exposure = exposure, settableProps = props) }
                } catch (e: Exception) {
                    Log.i(TAG, "exposure poll failed: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Called when the Control screen leaves the composition (tab switch / disconnect). */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        streamJob?.cancel()
        streamJob = null
        _liveFrame.value = null
        _uiState.update { it.copy(liveViewActive = false) }
    }

    /**
     * Runs the live-view stream, retrying while the screen is up: the camera may still be settling
     * into record mode when we first ask, so a rejected startstream is transient, not fatal.
     */
    private fun startLiveView() {
        if (streamJob?.isActive == true) return
        streamJob = viewModelScope.launch {
            while (isActive) {
                try {
                    streamer.run { frame -> onLiveFrame(frame) }
                } catch (e: Exception) {
                    Log.i(TAG, "live view stream error (will retry): ${e.message}")
                    _uiState.update { it.copy(liveViewActive = false) }
                    delay(LIVE_RETRY_MS)
                }
            }
        }
    }

    /** Decode a JPEG frame to a bitmap on the streamer's IO thread and publish it. */
    private fun onLiveFrame(jpeg: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (bitmap == null) {
            if (decodeFailures < 3) {
                decodeFailures++
                Log.w(TAG, "live frame failed to decode (${jpeg.size}B) - corrupt assembly?")
            }
            return
        }
        _liveFrame.value = bitmap.asImageBitmap()
        if (!_uiState.value.liveViewActive) _uiState.update { it.copy(liveViewActive = true) }
    }

    fun capture() = runCommand("Captured") { control.capture() }

    /** AF button: focus at the centre via touch AF (the GX80 rejects the blind oneshot_af command). */
    fun autofocus() = focusAt(0.5f, 0.5f)

    /**
     * Touch-to-focus at a point in the live-view image, given as 0..1 fractions of width/height.
     * Maps to the camera's 0..1000 coordinate space.
     */
    fun focusAt(xFraction: Float, yFraction: Float) {
        val x = (xFraction * 1000f).toInt().coerceIn(0, 1000)
        val y = (yFraction * 1000f).toInt().coerceIn(0, 1000)
        runCommand(
            successMessage = "Focusing…",
            errorMapper = { raw ->
                if (raw.contains("err_reject", ignoreCase = true)) {
                    "Focus rejected — set the lens/body to AF (not MF). The shutter autofocuses anyway."
                } else {
                    null
                }
            },
        ) { control.touchAf(x, y) }
    }

    /**
     * Move [prop] one step ([delta] is -1 or +1) along the camera's own list of allowed
     * values. The steppers lock while the camera confirms; the displayed value only changes
     * once the camera agrees — a rejected set leaves everything as it was.
     */
    fun adjustProp(prop: SettableProp, delta: Int) {
        val index = prop.options.indexOf(prop.rawValue)
        if (index < 0 || _uiState.value.busy || !prop.canAdjust) return
        val nextIndex = (index + delta).coerceIn(0, prop.options.lastIndex)
        val next = prop.options[nextIndex]
        if (next == prop.rawValue) return

        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            try {
                val confirmed = control.setProp(prop, next)
                if (confirmed != null) {
                    val applied = prop.copy(
                        rawValue = confirmed,
                        displayValue = formatPropDisplay(prop.name, confirmed),
                    )
                    _uiState.update { state ->
                        state.copy(
                            settableProps = state.settableProps.map {
                                if (it.name == prop.name) applied else it
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "set ${prop.displayName} failed: ${e.message}")
                _uiState.update {
                    it.copy(message = "Couldn't set ${prop.displayName} — the camera kept ${prop.displayValue}")
                }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Formats a property value for display (wire value in, pretty value out) — same rules as
     * the Opc backend's read-out (aperture gains f/, quoted shutter values become seconds).
     */
    private fun formatPropDisplay(name: String, value: String): String = when (name) {
        "focalvalue" -> "f/$value"
        "shutspeedvalue" ->
            if (value.endsWith("\"")) "${value.dropLast(1)}s" else "1/$value"
        else -> value
    }

    fun toggleVideoRecord() {
        if (_uiState.value.videoRecording) {
            runCommand("Recording stopped") { control.stopVideoRecord() }
        } else {
            runCommand("Recording started") { control.startVideoRecord() }
        }
    }

    /**
     * Runs a one-shot camera command with a busy guard and a success/error snackbar message.
     * [errorMapper] can turn a raw failure message into friendlier text (return null to keep raw).
     */
    private fun runCommand(
        successMessage: String?,
        errorMapper: (String) -> String? = { null },
        command: suspend () -> Unit,
    ) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val message = try {
                command()
                successMessage
            } catch (e: Exception) {
                Log.w(TAG, "command failed: ${e.message}")
                val raw = e.message ?: "Camera command failed"
                errorMapper(raw) ?: raw
            }
            _uiState.update { it.copy(busy = false, message = message) }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    override fun onCleared() {
        stop()
    }

    companion object {
        private const val TAG = "ControlViewModel"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val LIVE_RETRY_MS = 1_500L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val backends = container.backends()
                ControlViewModel(
                    backends.control,
                    backends.liveView,
                    container.cameraWifiLocks,
                    backends.supportsVideoRecord,
                )
            }
        }
    }
}
