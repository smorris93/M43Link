package com.lumilink.model

/**
 * One photo stored on the camera, as surfaced by the camera's listing (DLNA on the Lumix, the
 * VER_100 directory listing on the PEN).
 *
 * @param id Stable object id/key (UPnP object id, or the camera file path on the PEN).
 * @param title display name / filename without extension (e.g. "P1030413").
 * @param isRaw true when the downloadable original is a RAW file (RW2/ORF).
 * @param thumbnailUrl small preview URL for the grid, or null if none was advertised.
 * @param previewUrl medium-quality preview for the full-screen viewer, or null when the
 * camera has no such endpoint (the Lumix; there the viewer falls back to the original/thumb).
 * @param originalUrl full-resolution file to download.
 * @param sizeBytes size of the original in bytes, or null if the camera didn't report it.
 */
data class CameraPhoto(
    val id: String,
    val title: String,
    val isRaw: Boolean,
    val thumbnailUrl: String?,
    val originalUrl: String,
    val sizeBytes: Long?,
    val previewUrl: String? = null,
)
