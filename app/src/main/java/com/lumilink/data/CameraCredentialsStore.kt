package com.lumilink.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lumilink.model.CameraCredentials
import com.lumilink.model.CameraType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

// Top-level DataStore delegate. Must be a top-level property (one per file per name); the
// `by` keyword installs a lazy, process-wide singleton bound to this Context extension.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "camera")

/**
 * Persists the selected camera type and each camera's saved hotspot credentials, so later
 * launches connect without re-entering anything.
 *
 * Credentials are stored per camera type (`ssid_<TYPE>`/`passphrase_<TYPE>`). The original
 * single-camera keys (`ssid`/`passphrase`) predate multi-camera support and belong to the
 * Lumix — they're read as a fallback so an existing install's saved Wi-Fi keeps working.
 *
 * Kotlin/Android note: DataStore is the modern replacement for SharedPreferences. Reads are a
 * `Flow` (a stream of values over time) and writes are `suspend` functions (run on a background
 * dispatcher, never blocking the main thread).
 */
class CameraCredentialsStore(context: Context) {

    private val appContext = context.applicationContext

    private val selectedTypeKey = stringPreferencesKey("camera_type")
    private val legacySsidKey = stringPreferencesKey("ssid")
    private val legacyPassphraseKey = stringPreferencesKey("passphrase")

    private fun ssidKey(type: CameraType) = stringPreferencesKey("ssid_${type.name}")
    private fun passphraseKey(type: CameraType) = stringPreferencesKey("passphrase_${type.name}")

    /** The selected camera type; defaults to the Lumix (the app's original camera). */
    val selectedCameraType: Flow<CameraType> = appContext.dataStore.data.map { prefs ->
        val stored = prefs[selectedTypeKey]
        CameraType.entries.firstOrNull { it.name == stored } ?: CameraType.LUMIX_GX80
    }

    /**
     * Synchronous view of the selected type for non-suspend call sites (DI wiring, factories).
     * Seeded with one blocking read on first use — tiny file, once per process — then kept in
     * sync by [selectCameraType]. UI code should observe [selectedCameraType] instead.
     */
    @Volatile
    private var cachedSelectedType: CameraType? = null

    fun currentCameraType(): CameraType =
        cachedSelectedType ?: runBlocking { selectedCameraType.first() }
            .also { cachedSelectedType = it }

    suspend fun selectCameraType(type: CameraType) {
        cachedSelectedType = type
        appContext.dataStore.edit { prefs -> prefs[selectedTypeKey] = type.name }
    }

    /**
     * Emits the saved credentials for [type], or `null` if none saved yet; re-emits on change.
     */
    fun credentialsFor(type: CameraType): Flow<CameraCredentials?> =
        appContext.dataStore.data.map { prefs ->
            val ssid = prefs[ssidKey(type)]
                // Pre-multi-camera installs saved the Lumix's credentials under the bare keys.
                ?: prefs[legacySsidKey]?.takeIf { type == CameraType.LUMIX_GX80 }
            val passphrase = prefs[passphraseKey(type)]
                ?: prefs[legacyPassphraseKey]?.takeIf { type == CameraType.LUMIX_GX80 }
            if (ssid.isNullOrEmpty()) null else CameraCredentials(ssid, passphrase.orEmpty())
        }

    /** Saves (overwrites) the credentials for [type]. */
    suspend fun save(type: CameraType, credentials: CameraCredentials) {
        appContext.dataStore.edit { prefs ->
            prefs[ssidKey(type)] = credentials.ssid
            prefs[passphraseKey(type)] = credentials.passphrase
        }
    }
}
