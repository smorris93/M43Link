package com.lumilink.network

import android.util.Log
import com.lumilink.camera.CameraControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/**
 * Receives the camera's live-view MJPEG stream over UDP.
 *
 * The GX80 pushes one datagram per frame: a ~178-byte proprietary Panasonic header followed by a
 * full JPEG (starting at the `FF D8` SOI marker). We find the SOI and hand the JPEG to the caller.
 * (The header does NOT carry a decodable exposure read-out on this body — ISO comes from the
 * curmenu HTTP query instead; see the Lumix control backend.)
 *
 * All app sockets are already bound to the camera network by [CameraNetworkManager], so the UDP
 * socket receives on the camera Wi-Fi.
 */
class LiveViewStreamer(private val control: CameraControl) {

    /**
     * Opens the UDP socket on [port], asks the camera to stream to it, and invokes [onFrame] with
     * each JPEG frame until the coroutine is cancelled. Suspends for the whole session; runs on IO.
     */
    suspend fun run(port: Int = DEFAULT_PORT, onFrame: (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        // Bind the socket before telling the camera to stream, so no early frames are lost.
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = SOCKET_TIMEOUT_MS
            bind(InetSocketAddress(port))
        }
        try {
            control.startLiveViewStream(port)
            val buffer = ByteArray(MAX_DATAGRAM)
            var diagFrames = 0
            var received = 0L
            while (coroutineContext.isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "No live-view packet in ${SOCKET_TIMEOUT_MS}ms (received=$received)")
                    continue
                }
                received++
                val start = indexOfSoi(buffer, packet.length)
                if (start < 0) {
                    if (diagFrames < DIAG_FRAMES) Log.w(TAG, "datagram ${packet.length}B had no JPEG SOI")
                    continue
                }
                val jpeg = buffer.copyOfRange(start, packet.length)
                if (diagFrames < DIAG_FRAMES) {
                    Log.i(TAG, "frame: total=${packet.length}B header=${start}B jpeg=${jpeg.size}B")
                    diagFrames++
                }
                onFrame(jpeg)
            }
        } finally {
            socket.close()
            // Tell the camera to stop even though we're being cancelled.
            withContext(NonCancellable) { control.stopLiveViewStream() }
        }
    }

    /** Index of the JPEG start-of-image marker (FF D8) within the first [len] bytes, or -1. */
    private fun indexOfSoi(buf: ByteArray, len: Int): Int {
        var i = 0
        while (i < len - 1) {
            if (buf[i] == SOI_0 && buf[i + 1] == SOI_1) return i
            i++
        }
        return -1
    }

    companion object {
        private const val TAG = "LiveViewStreamer"
        const val DEFAULT_PORT = 49152
        private const val MAX_DATAGRAM = 65535
        private const val SOCKET_TIMEOUT_MS = 4000
        private const val DIAG_FRAMES = 3
        private const val SOI_0 = 0xFF.toByte()
        private const val SOI_1 = 0xD8.toByte()
    }
}
