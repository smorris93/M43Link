package com.lumilink.camera

/**
 * Protocol-specific assembly of live-view JPEG frames from raw UDP datagrams. The Lumix pushes
 * one complete JPEG per datagram; the OM System PEN pushes a continuous byte stream in which
 * frames are split across many datagrams (delimited only by the JPEG SOI/EOI markers), so each
 * camera family gets its own stateful assembler — one instance per streaming session.
 */
interface FrameAssembler {
    /** Feed one received datagram; emit zero or more complete JPEG frames via [emit]. */
    fun feed(datagram: ByteArray, length: Int, emit: (ByteArray) -> Unit)
}
