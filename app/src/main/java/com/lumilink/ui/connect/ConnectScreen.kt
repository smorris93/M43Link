package com.lumilink.ui.connect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumilink.model.CameraType
import com.lumilink.model.ConnectionState
import com.lumilink.ui.appContainer

/**
 * Connect screen — pick the camera type, enter that camera hotspot's SSID + password (prefilled
 * once saved), request the Wi-Fi permission if needed, then connect. Navigation to
 * Control/Photos is driven by the parent nav host reacting to the connection state; this screen
 * just connects and disconnects.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen() {
    val container = appContainer()
    val viewModel: ConnectViewModel = viewModel(factory = ConnectViewModel.factory(container))

    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val saved by viewModel.savedCredentials.collectAsStateWithLifecycle()
    val cameraType by viewModel.selectedCameraType.collectAsStateWithLifecycle()

    var ssid by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    // Prefill fields from the selected camera's saved credentials (don't clobber user edits).
    LaunchedEffect(saved) {
        val savedCreds = saved
        if (savedCreds != null && ssid.isBlank()) {
            ssid = savedCreds.ssid
            password = savedCreds.passphrase
        }
    }

    // Switching camera type starts a fresh credential set (fields re-prefill from that camera's
    // own saved values via the effect above).
    fun selectCameraType(type: CameraType) {
        if (type == cameraType) return
        ssid = ""
        password = ""
        viewModel.selectCameraType(type)
    }

    val typeSupported = container.isSupported(cameraType)
    val connected = state is ConnectionState.Connected
    val context = LocalContext.current
    // Connecting to a Wi-Fi network with a specifier requires this runtime permission:
    // NEARBY_WIFI_DEVICES on Android 13+, otherwise fine location.
    val wifiPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.connect(ssid, password)
    }

    val attemptConnect = {
        val alreadyGranted = ContextCompat.checkSelfPermission(context, wifiPermission) ==
            PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            viewModel.connect(ssid, password)
        } else {
            permissionLauncher.launch(wifiPermission)
        }
    }

    val connecting = state is ConnectionState.Connecting

    Scaffold(topBar = { TopAppBar(title = { Text("LumiLink") }) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionStatusCard(state)

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                CameraType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = type == cameraType,
                        onClick = { selectCameraType(type) },
                        shape = SegmentedButtonDefaults.itemShape(index, CameraType.entries.size),
                    ) {
                        Text(type.displayName)
                    }
                }
            }

            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("Camera Wi-Fi name (SSID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Wi-Fi password (leave blank if none)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            if (connected) {
                OutlinedButton(
                    onClick = viewModel::disconnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Disconnect")
                }
            } else {
                Button(
                    // Password is optional — the camera hotspot may be an open network.
                    onClick = attemptConnect,
                    enabled = !connecting && typeSupported && ssid.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (connecting) "Connecting…" else "Connect to camera")
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = setupInstructions(cameraType, supported = typeSupported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Per-camera setup text shown under the connect button. */
private fun setupInstructions(type: CameraType, supported: Boolean): String = when (type) {
    CameraType.LUMIX_GX80 ->
        "On the camera: Wi-Fi → New Connection → Remote Shooting & View, then enter " +
            "that hotspot's name here. Leave the password blank if the camera doesn't show " +
            "one. The first connection shows a one-time Android prompt; after that it " +
            "connects automatically."
    CameraType.OM_SYSTEM_PEN ->
        if (supported) {
            "On the camera: Wi-Fi Settings → Private, then launch the Wi-Fi and enter the " +
                "hotspot's name and password here. The first connection shows a one-time " +
                "Android prompt; after that it connects automatically."
        } else {
            "OM System PEN support is on the way (Milestone 1 of the PEN plan). Connect will " +
                "unlock once the PEN backend is implemented. On the camera, Wi-Fi Settings → " +
                "Private is the mode it will use."
        }
}

/** Small status banner reflecting the current [ConnectionState]. */
@Composable
private fun ConnectionStatusCard(state: ConnectionState) {
    val message = when (state) {
        is ConnectionState.NeedsSetup -> "Enter your camera's Wi-Fi details to begin."
        is ConnectionState.Idle -> "Not connected."
        is ConnectionState.Connecting -> "Connecting… accept the Android prompt if it appears."
        is ConnectionState.Connected -> "Connected to ${state.ssid}."
        is ConnectionState.Failed -> state.reason
    }
    val containerColor = when (state) {
        is ConnectionState.Connected -> MaterialTheme.colorScheme.primaryContainer
        is ConnectionState.Failed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
