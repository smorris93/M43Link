package com.lumilink.camera

import com.lumilink.network.LiveViewTelemetry

/**
 * Per-camera live-view parameters: the UDP port to listen on and how to assemble JPEG frames
 * from the incoming datagrams (see [FrameAssembler]).
 */
interface LiveViewConfig {
    val port: Int

    /**
     * A fresh, stateful assembler for one streaming session. [onTelemetry] receives per-frame
     * telemetry for cameras that embed it (ignored by cameras that don't).
     */
    fun newFrameAssembler(onTelemetry: ((LiveViewTelemetry) -> Unit)? = null): FrameAssembler
}
