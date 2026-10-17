package com.lumilink.ui.control

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumilink.model.CameraExposure
import com.lumilink.model.CameraType
import com.lumilink.model.SettableProp
import com.lumilink.model.CameraStatus
import com.lumilink.ui.appContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.delay

/**
 * Control screen (MVP2): remote shutter, autofocus and video record, with a live camera-status
 * strip. Live view and exposure numbers are placeholders until MVP3 wires up the MJPEG stream.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen() {
    val container = appContainer()
    val viewModel: ControlViewModel = viewModel(factory = ControlViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val liveFrame by viewModel.liveFrame.collectAsStateWithLifecycle()

    // Poll only while this screen is on-screen; stop when the user switches tabs.
    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    // Transient status messages render inline (above the controls) and auto-clear — a
    // bottom snackbar would cover the shutter/REC buttons while the user is tapping them.
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(MESSAGE_AUTO_CLEAR_MS)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Control") },
                actions = { ConnectedChip(ready = state.recordModeReady); Box(Modifier.size(12.dp)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The transient message overlays the live view (bottom centre) — zero layout
            // footprint, so the readouts and buttons never shift when it appears or clears.
            Box {
                LiveView(frame = liveFrame, onFocusTap = viewModel::focusAt)
                state.message?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp),
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            // Cameras that expose settable settings (the PEN) get live steppers built from the
            // camera's own allowed values; the GX80 keeps its plain read-out grid.
            if (state.settableProps.isNotEmpty()) {
                SettablePropsRow(
                    props = state.settableProps,
                    enabled = state.recordModeReady && !state.busy,
                    onAdjust = viewModel::adjustProp,
                )
            } else {
                ExposureGrid(state.exposure)
            }
            StatusStrip(state.status)
            Box(Modifier.weight(1f))
            ControlsRow(
                enabled = state.recordModeReady && !state.busy,
                recording = state.videoRecording,
                videoEnabled = viewModel.supportsVideoRecord,
                onAutofocus = viewModel::autofocus,
                onCapture = viewModel::capture,
                onToggleVideo = viewModel::toggleVideoRecord,
            )
            val hint = if (container.credentialsStore.currentCameraType() == CameraType.LUMIX_GX80) {
                "Tap the live view to focus there; AF focuses the centre. ISO is live; the " +
                    "GX80 doesn't expose aperture/shutter over Wi-Fi in Manual mode."
            } else {
                "Tap the live view to focus there; AF focuses the centre. Exposure read-outs " +
                    "appear as the PEN reports them."
            }
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Live steppers for each settable setting, in the camera's own value order. */
@Composable
private fun SettablePropsRow(
    props: List<SettableProp>,
    enabled: Boolean,
    onAdjust: (SettableProp, Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        props.forEach { prop ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                Text(
                    text = prop.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onAdjust(prop, -1) },
                        enabled = enabled && prop.canAdjust && prop.rawValue != prop.options.first(),
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease ${prop.displayName}")
                    }
                    Text(
                        text = prop.displayValue,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(min = 36.dp),
                        textAlign = TextAlign.Center,
                    )
                    IconButton(
                        onClick = { onAdjust(prop, +1) },
                        enabled = enabled && prop.canAdjust && prop.rawValue != prop.options.last(),
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase ${prop.displayName}")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedChip(ready: Boolean) {
    val color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            text = if (ready) "record mode" else "arming…",
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/**
 * The live-view feed. Shows the latest MJPEG frame once streaming (matching its aspect ratio so
 * tap coordinates map exactly), or a waiting placeholder. Tapping the image focuses at that point.
 */
@Composable
private fun LiveView(frame: ImageBitmap?, onFocusTap: (Float, Float) -> Unit) {
    var tapPoint by remember { mutableStateOf<Offset?>(null) }
    tapPoint?.let { LaunchedEffect(it) { delay(900); tapPoint = null } }

    val aspect = if (frame != null && frame.height > 0) frame.width.toFloat() / frame.height else 3f / 2f
    Surface(
        color = Color(0xFF0B0D0F),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(frame != null) {
                    if (frame != null) {
                        detectTapGestures { offset ->
                            tapPoint = offset
                            onFocusTap(
                                (offset.x / size.width).coerceIn(0f, 1f),
                                (offset.y / size.height).coerceIn(0f, 1f),
                            )
                        }
                    }
                },
        ) {
            if (frame != null) {
                Image(
                    bitmap = frame,
                    contentDescription = "Live view",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                tapPoint?.let { pt ->
                    val reticle = 44.dp
                    Box(
                        Modifier
                            // Anchor top-left, not the box centre, so the offset lands the reticle
                            // exactly on the tap point.
                            .align(Alignment.TopStart)
                            .offset { IntOffset((pt.x).toInt() - reticle.roundToPx() / 2, (pt.y).toInt() - reticle.roundToPx() / 2) }
                            .size(reticle)
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                    )
                }
            } else {
                Box(
                    Modifier.size(52.dp)
                        .border(1.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                )
                Text(
                    text = "live view · MJPEG :49152 · waiting…",
                    color = Color.White.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                )
            }
        }
    }
}

/** Exposure read-out. Shows only the fields the camera actually reports — ISO always (from
 *  curmenu); aperture/shutter/EV appear automatically if the body ever exposes them (it doesn't in
 *  Manual mode), so there are no dead "—" cells. */
@Composable
private fun ExposureGrid(exposure: CameraExposure) {
    val cells = buildList {
        add("ISO" to (exposure.iso ?: "—"))
        exposure.aperture?.let { add("Aperture" to "f/$it") }
        exposure.shutter?.let { add("Shutter" to it) }
        exposure.exposureComp?.let { add("EV" to it) }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(vertical = 8.dp)) {
            cells.forEach { (label, value) ->
                Column(
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = value,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

/** Real camera status from getstate: battery, mode and SD-card. Shows "—" for anything unknown. */
@Composable
private fun StatusStrip(status: CameraStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Fields the camera can't report stay hidden rather than showing an eternal "—"
        // (the PEN's protocol exposes no battery/SD status — see PROJECT_CONTEXT.md).
        status.battery?.let { StatusItem("BATTERY", it) }
        StatusItem("MODE", status.mode ?: "—")
        status.sdCard?.let { StatusItem("SD", it) }
    }
}

@Composable
private fun StatusItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}

/** AF (left) · Shutter (center, large) · REC (right), matching the wireframe. */
@Composable
private fun ControlsRow(
    enabled: Boolean,
    recording: Boolean,
    videoEnabled: Boolean,
    onAutofocus: () -> Unit,
    onCapture: () -> Unit,
    onToggleVideo: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RingButton(
            label = "AF",
            enabled = enabled,
            onClick = onAutofocus,
            icon = Icons.Default.CenterFocusStrong,
            contentDescription = "Autofocus",
        )
        ShutterButton(enabled = enabled, onClick = onCapture)
        RingButton(
            label = if (recording) "STOP" else "REC",
            enabled = enabled && videoEnabled,
            tint = MaterialTheme.colorScheme.error,
            onClick = onToggleVideo,
            icon = if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
            contentDescription = "Record video",
        )
    }
}

@Composable
private fun RingButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val alpha = if (enabled) 1f else 0.4f
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = alpha), CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = tint.copy(alpha = alpha))
        }
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}

/** The big amber capture button. */
@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(3.dp, accent.copy(alpha = alpha), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(50.dp).clip(CircleShape).background(accent.copy(alpha = alpha)))
    }
}

private const val MESSAGE_AUTO_CLEAR_MS = 2_500L
