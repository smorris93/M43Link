package com.lumilink.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.lumilink.model.CameraPhoto
import com.lumilink.ui.appContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Gallery screen — the camera's photos in a lazy, selectable grid.
 *
 * Interaction (the standard gallery pattern): tap a photo to preview it full-screen; long-press to
 * start multi-selecting (then tap toggles selection). A background warmer pre-caches every
 * thumbnail so scrolling becomes instant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen() {
    val container = appContainer()
    val viewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Returning to this tab (e.g. from Control's record mode) re-asserts playback mode so the
    // media server serves photos; a no-op on the very first load, which enters it itself.
    LaunchedEffect(Unit) { viewModel.onScreenEntered() }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.statusMessage) {
        val message = state.statusMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeStatusMessage()
        }
    }

    val gridState = rememberLazyGridState()
    LaunchedEffect(state.sortOrder) { gridState.scrollToItem(0) }

    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val currentPhotos = rememberUpdatedState(state.orderedPhotos)

    // Prefetch a few rows ahead once scrolling settles (keeps just-below content ready).
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .debounce(PREFETCH_DEBOUNCE_MS)
            .collect { lastVisible ->
                val photos = currentPhotos.value
                val end = minOf(lastVisible + PREFETCH_AHEAD, photos.lastIndex)
                for (i in (lastVisible + 1)..end) {
                    val url = photos.getOrNull(i)?.thumbnailUrl ?: continue
                    imageLoader.enqueue(ImageRequest.Builder(context).data(url).build())
                }
            }
    }

    // Background warmer: gently pre-cache the whole thumbnail set (small batches, paced) so the
    // entire gallery becomes instant to scroll — without flooding the camera's fragile server.
    // Paused while a download batch runs: the PEN's file server crosses response streams between
    // concurrent requests (measured on-device), so thumbnail traffic during a transfer risks
    // corrupting the file being downloaded. Restarts automatically when the batch finishes.
    LaunchedEffect(state.allPhotos, state.downloading) {
        if (state.downloading) return@LaunchedEffect
        val urls = state.allPhotos.mapNotNull { it.thumbnailUrl }
        if (urls.isEmpty()) return@LaunchedEffect
        delay(WARM_START_DELAY_MS) // let the first visible screen load first
        var i = 0
        while (i < urls.size) {
            val end = minOf(i + WARM_BATCH, urls.size)
            for (j in i until end) {
                imageLoader.enqueue(ImageRequest.Builder(context).data(urls[j]).build())
            }
            i = end
            delay(WARM_BATCH_DELAY_MS)
        }
    }

    var previewPhoto by remember { mutableStateOf<CameraPhoto?>(null) }
    val selectionMode = state.selectedIds.isNotEmpty()

    // System / gesture back leaves an active selection first (like the top-bar Close). Otherwise
    // it falls through to normal nav. Disabled while previewing so the preview closes itself.
    BackHandler(enabled = selectionMode && previewPhoto == null) {
        viewModel.clearSelection()
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${state.selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::selectAll) {
                            Icon(Icons.Default.Done, contentDescription = "Select all")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Photos on camera") },
                    actions = {
                        IconButton(onClick = viewModel::load, enabled = !state.loading) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (state.selectedIds.isNotEmpty() || state.downloading) {
                DownloadBar(
                    selectedCount = state.selectedIds.size,
                    selectedBytes = state.selectedBytes,
                    downloading = state.downloading,
                    progress = state.downloadProgress,
                    onDownload = viewModel::downloadSelected,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (state.allPhotos.isNotEmpty()) {
                GalleryControls(
                    sortOrder = state.sortOrder,
                    totalCount = state.allPhotos.size,
                    onSort = viewModel::setSortOrder,
                )
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    state.loading -> CircularProgressIndicator()
                    state.error != null -> LoadError(state.error!!, onRetry = viewModel::load)
                    state.allPhotos.isEmpty() -> Text("No photos found on the camera.")
                    else -> PhotoGrid(
                        gridState = gridState,
                        photos = state.orderedPhotos,
                        selectedIds = state.selectedIds,
                        onClick = { photo ->
                            if (selectionMode) viewModel.toggleSelection(photo.id) else previewPhoto = photo
                        },
                        onLongClick = { photo -> viewModel.toggleSelection(photo.id) },
                    )
                }
            }
        }
    }

    previewPhoto?.let { photo ->
        PhotoPreview(
            photo = photo,
            downloading = state.downloading,
            onDownload = { viewModel.downloadSingle(photo) },
            onClose = { previewPhoto = null },
        )
    }
}

@Composable
private fun GalleryControls(sortOrder: SortOrder, totalCount: Int, onSort: (SortOrder) -> Unit) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { sortMenuOpen = true }) {
                Text(if (sortOrder == SortOrder.NEWEST) "Newest ▾" else "Oldest ▾")
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Newest first") },
                    onClick = { onSort(SortOrder.NEWEST); sortMenuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text("Oldest first") },
                    onClick = { onSort(SortOrder.OLDEST); sortMenuOpen = false },
                )
            }
        }
        Text(
            text = "$totalCount photos",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun PhotoGrid(
    gridState: LazyGridState,
    photos: List<CameraPhoto>,
    selectedIds: Set<String>,
    onClick: (CameraPhoto) -> Unit,
    onLongClick: (CameraPhoto) -> Unit,
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(photos, key = { it.id }) { photo ->
            PhotoCell(
                photo = photo,
                selected = photo.id in selectedIds,
                onClick = { onClick(photo) },
                onLongClick = { onLongClick(photo) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    photo: CameraPhoto,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = photo.thumbnailUrl,
            contentDescription = photo.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        ) {
            Text(
                text = when {
                    photo.isVideo -> "VID"
                    photo.isRaw -> "RAW"
                    else -> "JPG"
                },
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
            )
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(2.dp),
                )
            }
        }
    }
}

/** Full-screen preview of a single photo. */
@Composable
private fun PhotoPreview(
    photo: CameraPhoto,
    downloading: Boolean,
    onDownload: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    // Cameras that serve a medium preview (the PEN's screennail) use it — loading the full
    // original for a viewer swipe is slow over camera Wi-Fi. RAW files can't be shown as an
    // image at all, so those fall back to the thumbnail.
    val previewUrl = photo.previewUrl
        ?: if (photo.isRaw) photo.thumbnailUrl else photo.originalUrl

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
            AsyncImage(
                model = previewUrl,
                contentDescription = photo.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }

            Button(
                onClick = onDownload,
                enabled = !downloading,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            ) {
                Text(
                    if (downloading) {
                        "Downloading…"
                    } else {
                        "Download " + when {
                            photo.isVideo -> "video"
                            photo.isRaw -> "RAW"
                            else -> "JPG"
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DownloadBar(
    selectedCount: Int,
    selectedBytes: Long,
    downloading: Boolean,
    progress: Float,
    onDownload: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            if (downloading) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$selectedCount selected · ${selectedBytes / 1_000_000} MB",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onDownload, enabled = !downloading) {
                    Text(if (downloading) "Downloading…" else "Download")
                }
            }
        }
    }
}

@Composable
private fun LoadError(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

private const val PREFETCH_AHEAD = 6
private const val PREFETCH_DEBOUNCE_MS = 200L
private const val WARM_START_DELAY_MS = 1_500L
private const val WARM_BATCH = 6
private const val WARM_BATCH_DELAY_MS = 250L
