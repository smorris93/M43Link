package com.lumilink.camera

import com.lumilink.model.CameraExposure
import com.lumilink.model.CameraStatus

/**
 * Remote control of a camera: mode switching, shutter, autofocus, video, status/exposure
 * polling, and starting/stopping the live-view stream. One implementation per camera family
 * (the Lumix drives `cam.cgi`, the PEN the OPC CGIs); the Control screen knows only this.
 */
interface CameraControl {
    /**
     * Handshake (if the camera needs one) and switch to record mode — the state in which
     * capture, autofocus, video and live view become possible. Idempotent; safe to call on
     * every Control-screen entry.
     */
    suspend fun prepareRecordMode()

    /** Switch the camera to playback mode (required before browsing stored photos). */
    suspend fun enterPlaybackMode()

    /** Trigger the shutter. Record mode must already be active. */
    suspend fun capture()

    /**
     * Touch-to-focus at ([x], [y]) in the camera's own live-view coordinate space (Lumix:
     * 0..1000; the PEN's space is confirmed on-device in Milestone 3). The live-view stream
     * must be running.
     */
    suspend fun touchAf(x: Int, y: Int)

    /** Start / stop video recording. Record mode only. */
    suspend fun startVideoRecord()
    suspend fun stopVideoRecord()

    /** Poll hardware/mode status (battery, rec/play, SD, video-recording flag). */
    suspend fun fetchStatus(): CameraStatus

    /** Poll the current exposure settings (ISO, aperture, shutter, EV — whatever the body reports). */
    suspend fun fetchExposure(): CameraExposure

    /**
     * Ask the camera to push live-view frames over UDP to [port]. Open the UDP socket
     * *before* calling this so no early frames are missed.
     */
    suspend fun startLiveViewStream(port: Int)

    /** Best-effort stop of the live-view stream (some bodies don't expose it; ignore failures). */
    suspend fun stopLiveViewStream()
}
