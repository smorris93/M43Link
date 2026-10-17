package com.lumilink.ui.gallery

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumilink.camera.PhotoLister
import com.lumilink.data.PhotoDownloader
import com.lumilink.di.AppContainer
import com.lumilink.model.CameraPhoto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** Order photos are shown in. The camera lists oldest-first, so NEWEST just reverses that. */
enum class SortOrder { NEWEST, OLDEST }

/**
 * Drives the gallery: loads the camera's photos, tracks selection, sorts, and downloads. The grid
 * itself is lazy (only visible thumbnails load), so no manual paging is needed.
 */
class GalleryViewModel(
    private val photoLister: PhotoLister,
    private val photoDownloader: PhotoDownloader,
    /** Per-camera cap on simultaneous downloads (see DownloadProfile; 1 = sequential). */
    private val maxConcurrentDownloads: Int = 1,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        /** All photos as fetched from the camera (oldest-first order). */
        val allPhotos: List<CameraPhoto> = emptyList(),
        val sortOrder: SortOrder = SortOrder.NEWEST,
        val selectedIds: Set<String> = emptySet(),
        val error: String? = null,
        val downloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val statusMessage: String? = null,
    ) {
        /** All photos in the chosen order (newest = reversed fetch order). */
        val orderedPhotos: List<CameraPhoto>
            get() = if (sortOrder == SortOrder.OLDEST) allPhotos else allPhotos.asReversed()

        val selectedPhotos: List<CameraPhoto> get() = allPhotos.filter { it.id in selectedIds }
        val selectedBytes: Long get() = selectedPhotos.sumOf { it.sizeBytes ?: 0L }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /**
     * Called when the Photos tab becomes visible. If photos are already loaded, re-assert playback
     * mode (a sibling tab may have put the camera in record mode); the first load handles mode itself.
     */
    fun onScreenEntered() {
        if (_uiState.value.allPhotos.isEmpty()) return
        viewModelScope.launch { runCatching { photoLister.ensurePlaybackMode() } }
    }

    fun load() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val photos = photoLister.listPhotos()
                _uiState.update { it.copy(loading = false, allPhotos = photos) }
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "Couldn't load photos") }
            }
        }
    }

    fun setSortOrder(order: SortOrder) {
        _uiState.update { it.copy(sortOrder = order) }
    }

    fun toggleSelection(id: String) {
        _uiState.update { state ->
            val updated = state.selectedIds.toMutableSet()
            if (!updated.add(id)) updated.remove(id)
            state.copy(selectedIds = updated)
        }
    }

    fun selectAll() {
        _uiState.update { it.copy(selectedIds = it.allPhotos.map { photo -> photo.id }.toSet()) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    /** Download the currently-selected photos, then clear the selection. */
    fun downloadSelected() = downloadPhotos(_uiState.value.selectedPhotos, clearSelectionAfter = true)

    /** Download a single photo (e.g. from the preview) without touching the selection. */
    fun downloadSingle(photo: CameraPhoto) = downloadPhotos(listOf(photo), clearSelectionAfter = false)

    private fun downloadPhotos(photos: List<CameraPhoto>, clearSelectionAfter: Boolean) {
        if (photos.isEmpty() || _uiState.value.downloading) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(downloading = true, downloadProgress = 0f, error = null, statusMessage = null)
            }
            val completed = AtomicInteger()
            val failed = AtomicInteger()
            val batchStart = System.currentTimeMillis()
            // Camera safety: a run of consecutive failures means the camera is struggling —
            // stop hammering it and report, instead of dragging the whole batch through retries.
            val failureStreak = AtomicInteger()
            try {
                coroutineScope {
                    val gate = Semaphore(maxConcurrentDownloads.coerceAtLeast(1))
                    photos.forEach { photo ->
                        launch {
                            gate.withPermit {
                                if (failureStreak.get() >= ABORT_FAILURE_STREAK) return@withPermit
                                try {
                                    val fileStart = System.currentTimeMillis()
                                    val sizeMb = (photo.sizeBytes ?: 0L) / 1024.0 / 1024.0
                                    photoDownloader.download(photo) { fileProgress ->
                                        val overall = (
                                            (completed.get() + failed.get() + fileProgress).toFloat() /
                                                photos.size
                                            ).coerceIn(0f, 1f)
                                        _uiState.update { it.copy(downloadProgress = overall) }
                                    }
                                    Log.i(
                                        TAG,
                                        "downloaded ${photo.title} (" + "%.1f".format(sizeMb) +
                                            " MB) in ${System.currentTimeMillis() - fileStart} ms",
                                    )
                                    completed.incrementAndGet()
                                    failureStreak.set(0)
                                } catch (e: Exception) {
                                    failed.incrementAndGet()
                                    failureStreak.incrementAndGet()
                                    Log.w(TAG, "Download failed: ${photo.title}: ${e.message}")
                                }
                            }
                        }
                    }
                }
                val elapsed = System.currentTimeMillis() - batchStart
                val totalMb = photos.sumOf { it.sizeBytes ?: 0L } / 1024.0 / 1024.0
                Log.i(
                    TAG,
                    "batch done: ${completed.get()}/${photos.size} ok, " + "%.1f".format(totalMb) +
                        " MB in ${elapsed} ms (x$maxConcurrentDownloads)",
                )
                _uiState.update {
                    val saved = completed.get()
                    val broken = failed.get()
                    val message = when {
                        broken == 0 -> "Saved $saved photo(s) to your device"
                        failureStreak.get() >= ABORT_FAILURE_STREAK ->
                            "Stopped after $broken failures — the camera seems busy. Saved $saved " +
                                "of ${photos.size}."
                        else -> "Saved $saved of ${photos.size} ($broken failed)"
                    }
                    it.copy(
                        downloading = false,
                        downloadProgress = 1f,
                        selectedIds = if (clearSelectionAfter) emptySet() else it.selectedIds,
                        statusMessage = message,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(downloading = false, statusMessage = "Download stopped: ${e.message}")
                }
            }
        }
    }

    fun consumeStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    companion object {
        private const val TAG = "GalleryViewModel"
        private const val ABORT_FAILURE_STREAK = 3

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val backends = container.backends()
                GalleryViewModel(
                    backends.photoLister,
                    backends.photoDownloader,
                    backends.downloadProfile.maxConcurrentDownloads,
                )
            }
        }
    }
}
