package com.lumilink.camera

/**
 * Per-camera live-view parameters. The frame format itself lives in the streamer; this is the
 * part the UI/ViewModels need (the UDP port to listen on). The PEN's port/format is confirmed
 * on-device in Milestone 3.
 */
interface LiveViewConfig {
    val port: Int
}
