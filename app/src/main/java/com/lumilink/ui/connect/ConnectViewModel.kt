package com.lumilink.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumilink.di.AppContainer
import com.lumilink.data.CameraCredentialsStore
import com.lumilink.model.CameraCredentials
import com.lumilink.model.CameraType
import com.lumilink.model.ConnectionState
import com.lumilink.network.CameraNetworkManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Holds the Connect screen's state and actions: which camera type is selected, its saved
 * credentials, and the live connection state. The UI never touches the network layer directly.
 *
 * Kotlin/Android note: a `ViewModel` survives configuration changes (e.g. screen rotation).
 * `viewModelScope` is a coroutine scope automatically cancelled when the ViewModel is cleared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModel(
    private val networkManager: CameraNetworkManager,
    private val credentialsStore: CameraCredentialsStore,
) : ViewModel() {

    /** Live connection state from the network manager, surfaced straight to the UI. */
    val connectionState: StateFlow<ConnectionState> = networkManager.state

    /** The camera type selected on this screen (persisted; defaults to the Lumix). */
    val selectedCameraType: StateFlow<CameraType> = credentialsStore.selectedCameraType.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = credentialsStore.currentCameraType(),
    )

    /**
     * The selected camera's saved credentials, or null. Switching camera type re-emits that
     * camera's own saved credentials so the fields prefill per camera.
     */
    val savedCredentials: StateFlow<CameraCredentials?> = selectedCameraType
        .flatMapLatest { credentialsStore.credentialsFor(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    fun selectCameraType(type: CameraType) {
        viewModelScope.launch { credentialsStore.selectCameraType(type) }
    }

    /** Save the entered credentials for the selected camera and start connecting. */
    fun connect(ssid: String, passphrase: String) {
        val credentials = CameraCredentials(ssid.trim(), passphrase)
        viewModelScope.launch { credentialsStore.save(selectedCameraType.value, credentials) }
        networkManager.connect(credentials)
    }

    fun disconnect() {
        networkManager.disconnect()
    }

    companion object {
        /**
         * Builds this ViewModel with its dependencies pulled from the DI container.
         * `viewModelFactory { initializer { ... } }` is the idiomatic way to construct a
         * ViewModel that takes constructor arguments.
         */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ConnectViewModel(container.cameraNetworkManager, container.credentialsStore)
            }
        }
    }
}
