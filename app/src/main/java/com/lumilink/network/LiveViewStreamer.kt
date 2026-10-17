package com.lumilink.network

import android.util.Log
import com.lumilink.camera.CameraControl
import com.lumilink.network.CameraException
import com.lumilink.camera.FrameAssembler
import com.lumilink.camera.LiveViewConfig
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
 * Receives the camera's live-view stream over UDP and hands assembled JPEG frames to the caller.
 *
 * The framing itself is per-camera (see [FrameAssembler]): the GX80 sends one full JPEG per
 * datagram (after a ~178-byte proprietary header), while the OM System PEN sends a continuous
 * stream where each JPEG is split across several datagrams. Everything camera-agnostic — the
 * socket, the start/stop commands, retry diagnostics — lives here.
 *
 * All app sockets are already bound to the camera network by [CameraNetworkManager], so the UDP
 * socket receives on the camera Wi-Fi.
 */
class LiveViewStreamer(
    private val control: CameraControl,
    private val liveView: LiveViewConfig,
    private val wifiLocks: CameraWifiLocks? = null,
) {

    /**
     * Opens the UDP socket, asks the camera to stream to it, and invokes [onFrame] with each
     * assembled JPEG until the coroutine is cancelled. Suspends for the whole session; runs on IO.
     */
    suspend fun run(onFrame: (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        // Keep the radio awake: without the locks Android's Wi-Fi power save buffers the
        // camera's UDP packets and the live view stutters (observed on-device).
        wifiLocks?.acquire()
        // Bind the socket before telling the camera to stream, so no early datagrams are lost.
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = SOCKET_TIMEOUT_MS
            bind(InetSocketAddress(liveView.port))
        }
        val assembler = liveView.newFrameAssembler()
        var diagFrames = 0
        var diagDatagrams = 0
        var received = 0L
        var idleTimeouts = 0
        try {
            control.startLiveViewStream(liveView.port)
            val buffer = ByteArray(MAX_DATAGRAM)
            while (coroutineContext.isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    // The OM System camera stops pushing after a couple of seconds unless the
                    // client re-requests the stream (observed on-device). One nudge per gap; a
                    // long silence means a real problem, so give up and let the caller retry.
                    if (idleTimeouts < MAX_IDLE_NUDGES) {
                        Log.i(TAG, "Stream idle ${SOCKET_TIMEOUT_MS}ms (received=$received) - re-requesting")
                        runCatching { control.startLiveViewStream(liveView.port) }
                    } else {
                        throw CameraException("Live view stopped arriving")
                    }
                    idleTimeouts++
                    continue
                }
                idleTimeouts = 0
                received++
                if (diagDatagrams < DIAG_DATAGRAMS) {
                    // Frame-format diagnostics: enough hex to identify per-datagram headers.
                    Log.i(TAG, "datagram ${packet.length}B: " + buffer.toHex(packet.length))
                    diagDatagrams++
                }
                assembler.feed(buffer, packet.length) { jpeg ->
                    if (diagFrames < DIAG_FRAMES) {
                        Log.i(TAG, "frame: datagram=${packet.length}B jpeg=${jpeg.size}B")
                        diagFrames++
                    }
                    onFrame(jpeg)
                }
            }
        } finally {
            wifiLocks?.release()
            socket.close()
            // Tell the camera to stop even though we're being cancelled.
            withContext(NonCancellable) { control.stopLiveViewStream() }
        }
    }

    private companion object {
        const val TAG = "LiveViewStreamer"
        const val MAX_DATAGRAM = 65535
        const val SOCKET_TIMEOUT_MS = 1500
        const val DIAG_FRAMES = 3
        const val DIAG_DATAGRAMS = 12
        const val MAX_IDLE_NUDGES = 3
    }
}

/** First bytes of a received datagram as hex, for frame-format diagnostics. */
private fun ByteArray.toHex(length: Int): String {
    val n = minOf(length, DIAG_HEX_BYTES)
    val sb = StringBuilder(n * 3)
    for (i in 0 until n) {
        if (i > 0) sb.append(' ')
        sb.append(String.format("%02X", this[i].toInt() and 0xFF))
    }
    return sb.toString()
}

private const val DIAG_HEX_BYTES = 40

/** The Lumix framing: every datagram is one complete JPEG, optionally preceded by a header. */
class OneDatagramJpegAssembler : FrameAssembler {

    override fun feed(datagram: ByteArray, length: Int, emit: (ByteArray) -> Unit) {
        val start = indexOfSoi(datagram, length)
        if (start < 0) {
            Log.w(TAG, "datagram ${length}B had no JPEG SOI")
            return
        }
        emit(datagram.copyOfRange(start, length))
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

    private companion object {
        const val TAG = "OneDatagramJpeg"
        const val SOI_0 = 0xFF.toByte()
        const val SOI_1 = 0xD8.toByte()
    }
}

/**
 * The OM System framing: a continuous byte stream in which frames are split across many
 * datagrams and delimited only by the JPEG markers themselves (observed on-device: strings of
 * 1472-byte datagrams with bridge datagrams carrying one frame's tail and the next frame's
 * start). Buffers the stream and emits each complete SOI..EOI segment as a frame.
 */
class StreamJpegAssembler : FrameAssembler {

    private var buffer = ByteArray(INITIAL_BUFFER)
    private var length = 0
    private var scanFrom = 0

    override fun feed(datagram: ByteArray, len: Int, emit: (ByteArray) -> Unit) =
        feedRange(datagram, 0, len, emit)

    /** Feeds [len] bytes of [datagram] starting at [offset] into the stream. */
    fun feedRange(datagram: ByteArray, offset: Int, len: Int, emit: (ByteArray) -> Unit) {
        append(datagram, offset, len)
        while (true) {
            val soi = find(SOI, scanFrom)
            if (soi < 0) {
                // No frame start. Keep only the tail bytes that could be a split SOI marker.
                val keep = minOf(length, SOI.size - 1)
                consumeThrough(length - keep)
                scanFrom = 0
                break
            }
            val eoi = find(EOI, soi + SOI.size)
            if (eoi < 0) {
                // Frame is still arriving. Drop everything before its SOI and wait for more.
                if (length - soi > MAX_FRAME_BYTES) {
                    reset()
                    break
                }
                consumeThrough(soi)
                scanFrom = 0
                break
            }
            emit(buffer.copyOfRange(soi, eoi + EOI.size))
            consumeThrough(eoi + EOI.size)
            scanFrom = 0
        }
    }

    private fun append(bytes: ByteArray, offset: Int, len: Int) {
        if (length + len > buffer.size) {
            var newCapacity = buffer.size
            while (newCapacity < length + len) newCapacity *= 2
            buffer = buffer.copyOf(newCapacity.coerceAtMost(MAX_FRAME_BYTES + len))
        }
        System.arraycopy(bytes, offset, buffer, length, len)
        length += len
    }

    /** First occurrence of [marker] at or after [from], or -1. */
    private fun find(marker: ByteArray, from: Int): Int {
        if (marker.isEmpty()) return -1
        outer@ for (i in maxOf(from, 0)..(length - marker.size)) {
            for (j in marker.indices) {
                if (buffer[i + j] != marker[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** Drops [end] bytes from the start of the buffer, keeping the rest. */
    private fun consumeThrough(end: Int) {
        val remaining = length - end
        if (remaining > 0) System.arraycopy(buffer, end, buffer, 0, remaining)
        length = remaining
    }

    fun reset() {
        length = 0
        scanFrom = 0
        Log.w(TAG, "live-view assembler reset (no frame end within $MAX_FRAME_BYTES bytes)")
    }

    companion object {
        private const val TAG = "StreamJpegAssembler"
        private const val INITIAL_BUFFER = 64 * 1024

        /** A whole JPEG never exceeds this; a stream without an EOI within it is junk. */
        private const val MAX_FRAME_BYTES = 512 * 1024

        /** SOI plus the marker byte that always follows it (FF D8 FF) — avoids false hits. */
        private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        /** JPEG end-of-image (FF D9). */
        private val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }
}

/**
 * The OM System framing underneath the JPEG stream: standard RTP (RFC 3550, payload type 96).
 * Each datagram is one RTP packet: a 12-byte fixed header (sequence, timestamp), and — on the
 * first packet of each frame — an RTP header extension (bit X) carrying frame metadata before
 * the JPEG begins. Mid-frame packets carry raw JPEG continuation bytes. Decoded on-device from
 * the PEN's live-view datagrams (see docs/PROJECT_CONTEXT.md, M3): the frame-start packets have
 * the extension at bytes 12..16 with a word-counted length, and the JPEG SOI lands exactly after
 * it — matching the observed 260-byte offset. After stripping the RTP headers the remaining
 * payload is a clean JPEG byte stream, which [StreamJpegAssembler] frames by SOI/EOI.
 */
class RtpJpegAssembler : FrameAssembler {
    private val jpegAssembler = StreamJpegAssembler()

    /** Diagnostics: capture the first few frame-metadata extensions for offline analysis. */
    private var diagExtensions = 0

    /** Last seen RTP sequence number, for loss detection; -1 before the first packet. */
    private var lastSequence = -1

    /** Number of frames dropped so far because of packet loss (diagnostics). */
    private var droppedByLoss = 0

    override fun feed(datagram: ByteArray, length: Int, emit: (ByteArray) -> Unit) {
        val payloadStart = rtpPayloadOffset(datagram, length) ?: return
        val sequence = ((datagram[2].toInt() and 0xFF) shl 8) or (datagram[3].toInt() and 0xFF)
        if (lastSequence >= 0 && sequence != (lastSequence + 1) and 0xFFFF) {
            // Packets were lost — the frame being assembled has a hole. Drop it and let the
            // next SOI start a clean frame instead of emitting a corrupt JPEG.
            jpegAssembler.reset()
            droppedByLoss++
            if (droppedByLoss <= 3) {
                Log.w(TAG, "RTP gap (expected ${(lastSequence + 1) and 0xFFFF}, got $sequence) - frame dropped")
            }
        }
        lastSequence = sequence
        jpegAssembler.feedRange(datagram, payloadStart, length - payloadStart, emit)
    }

    /**
     * Offset of the RTP payload (the JPEG stream bytes) in this datagram, or null when the
     * datagram isn't a valid RTP packet we understand (truncated, wrong version).
     */
    private fun rtpPayloadOffset(datagram: ByteArray, length: Int): Int? {
        if (length < RTP_HEADER_BYTES) return null
        val first = datagram[0].toInt() and 0xFF
        if (first and 0xC0 != 0x80) return null // not RTP v2
        val csrcCount = first and 0x0F
        var offset = RTP_HEADER_BYTES + csrcCount * 4
        if (length < offset) return null

        // Header extension (frame metadata on the first packet of each frame).
        if (first and 0x10 != 0) {
            if (length < offset + 4) return null
            val extensionWords =
                ((datagram[offset + 2].toInt() and 0xFF) shl 8) or
                    (datagram[offset + 3].toInt() and 0xFF)
            val extensionStart = offset + 4
            if (diagExtensions < DIAG_EXTENSIONS) {
                diagExtensions++
                val hex = StringBuilder()
                val n = minOf(extensionWords * 4, DIAG_EXT_BYTES)
                for (i in 0 until n) {
                    if (i > 0) hex.append(' ')
                    hex.append(String.format("%02X", datagram[extensionStart + i].toInt() and 0xFF))
                }
                Log.i(TAG, "frame metadata ($extensionWords words): $hex")
            }
            offset = extensionStart + extensionWords * 4
            if (length <= offset) return null
        }
        return offset
    }

    private companion object {
        const val TAG = "RtpJpegAssembler"
        const val RTP_HEADER_BYTES = 12
        const val DIAG_EXTENSIONS = 3
        const val DIAG_EXT_BYTES = 64
    }
}
