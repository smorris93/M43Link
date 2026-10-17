package com.lumilink.model

/**
 * A camera setting the user can change remotely: its current value, its display form, and the
 * camera's own list of allowed values (from the desclist on the PEN). [rawValue]/[options] are
 * the wire format sent back to the camera; [displayValue] is what the UI shows.
 *
 * An empty list from a camera means it exposes nothing settable (the GX80) and the Control
 * screen shows the plain read-out grid instead.
 */
data class SettableProp(
    /** Camera property name, e.g. `isospeedvalue`. */
    val name: String,
    /** UI label, e.g. `ISO`. */
    val displayName: String,
    val rawValue: String,
    val displayValue: String,
    /** The camera's allowed values, in dial order. */
    val options: List<String>,
    /**
     * False when the camera's current mode keeps this setting automatic (e.g. shutter in
     * Aperture priority) — the value still displays, the steppers grey out.
     */
    val canAdjust: Boolean = true,
)
