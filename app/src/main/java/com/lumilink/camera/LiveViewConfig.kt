package com.lumilink.camera

/**
 * Per-camera live-view parameters: the UDP port to listen on and how to assemble JPEG frames
 * from the incoming datagrams (see [FrameAssembler]).
 */
interface LiveViewConfig {
    val port: Int

    /** A fresh, stateful assembler for one streaming session. */
    fun newFrameAssembler(): FrameAssembler
}
