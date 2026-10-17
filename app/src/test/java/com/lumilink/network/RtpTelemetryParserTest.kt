package com.lumilink.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the live-view RTP telemetry parser. The frame-start fixture uses the exact
 * extension bytes captured from the PEN on-device; the exposure fields are crafted to the
 * OPC spec's documented formats (fields 8/9/10/12/17).
 */
class RtpTelemetryParserTest {

    /** Helper to build TLV records: [id:u16][lengthWords:u16][data...]. */
    private fun tlv(id: Int, data: ByteArray): ByteArray {
        val words = (data.size + 3) / 4
        val padded = data.copyOf(words * 4)
        return byteArrayOf(
            (id shr 8).toByte(), id.toByte(),
            (words shr 8).toByte(), words.toByte(),
        ) + padded
    }

    private fun u32(v: Int) = byteArrayOf(
        (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte(),
    )

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    @Test
    fun `parses the real PEN frame-start extension`() {
        // Captured on-device (the first 64 bytes of a frame-start packet's extension):
        // ID 1 (frame size 7622), ID 4 (orientation 1), ID 5 (5992 shots left),
        // ID 3 (media status 2), ID 2 (AF not performed, zeros).
        val hex = "00 01 00 01 00 00 1D C6 00 04 00 01 00 00 00 01 00 05 00 01 00 00 17 68 " +
            "00 03 00 01 00 00 00 02 00 02 00 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
            "00 00 00 00 00 06 00 01 00 00 00 00"
        val bytes = hex.split(' ').map { it.toInt(16).toByte() }.toByteArray()

        val t = RtpTelemetryParser.parse(bytes, 0, bytes.size)

        assertEquals(2, t.mediaStatus)
        assertEquals(5992, t.remainingShots)
        assertEquals(0, t.afResult)
        assertNull(t.afFrame)
    }

    @Test
    fun `parses AF success with frame coordinates`() {
        // Post-tap capture: color=1 (succeeded), frame x=174 y=116 w=36 h=36.
        val af = tlv(2, u16(1) + u16(0) + u32(174) + u32(116) + u32(36) + u32(36))

        val t = RtpTelemetryParser.parse(af, 0, af.size)

        assertEquals(1, t.afResult)
        assertArrayEquals(intArrayOf(174, 116, 36, 36), t.afFrame)
    }

    @Test
    fun `parses exposure fields per the spec formats`() {
        val shutter = tlv(8, u16(1) + u16(4000) + u16(60) + u16(1) + u16(1) + u16(250))
        val fnumber = tlv(9, u32(220) + u32(17) + u32(16))
        val ev = tlv(10, u32(30) + u32(-30) + u32(7))
        val iso = tlv(12, u16(0xFFFE) + byteArrayOf(1, 0, 0, 0) + u32(0) + u32(0))
        val focus = tlv(17, u16(2) + u16(0))

        val all = shutter + fnumber + ev + iso + focus
        val t = RtpTelemetryParser.parse(all, 0, all.size)

        assertEquals("1/250", t.shutter)
        assertEquals("f/1.6", t.aperture)
        assertEquals("+0.7", t.exposureComp)
        assertEquals("Auto", t.iso)
        assertEquals("MF", t.focusMode)
    }

    @Test
    fun `manual ISO value and negative EV render correctly`() {
        val iso = tlv(12, u16(800) + byteArrayOf(0, 0, 0, 0) + u32(0) + u32(0))
        val ev = tlv(10, u32(30) + u32(-30) + u32(-13))

        val all = iso + ev
        val t = RtpTelemetryParser.parse(all, 0, all.size)

        assertEquals("800", t.iso)
        assertEquals("-1.3", t.exposureComp)
    }

    @Test
    fun `long-exposure shutter renders as seconds`() {
        val shutter = tlv(8, u16(1) + u16(4000) + u16(60) + u16(1) + u16(60) + u16(1))
        val t = RtpTelemetryParser.parse(shutter, 0, shutter.size)
        assertEquals("60s", t.shutter)
    }

    @Test
    fun `skips unknown and truncated records`() {
        val unknown = tlv(999, u32(1))
        val t = RtpTelemetryParser.parse(unknown, 0, unknown.size)
        assertNull(t.mediaStatus)
        assertEquals(LiveViewTelemetry(), t)
    }
}
