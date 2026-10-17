package com.lumilink.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * JVM tests for the OM System `get_imglist.cgi` parser. The root fixture is the body captured
 * from the real PEN on-device (M1 probe); the rest are synthetic records covering the format's
 * documented edge cases.
 */
class ImgListParserTest {

    @Test
    fun `parses the real PEN root fixture as one directory entry`() {
        val body = "VER_100\r\n/DCIM,100OMSYS,0,16,23885,22261\r\n"

        val entries = ImgListParser.parse(body)

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("/DCIM", entry.directory)
        assertEquals("100OMSYS", entry.name)
        assertEquals("/DCIM/100OMSYS", entry.fullPath)
        assertEquals(0L, entry.sizeBytes)
        assertEquals(true, entry.isDirectory)
    }

    @Test
    fun `decodes the fixture's FAT date-time to a real timestamp`() {
        // Stamped when the folder was created on the actual card.
        val decoded = ImgListParser.decodeFatDateTime(dateRaw = 23885, timeRaw = 22261)

        assertEquals(LocalDateTime.of(2026, 10, 13, 10, 55, 42), decoded)
    }

    @Test
    fun `parses file records and marks them as non-directories`() {
        val body = listOf(
            "VER_100",
            "/DCIM/100OMSYS,P1234567.JPG,2634,32,10273,0",
            "/DCIM/100OMSYS,P1234568.ORF,16777216,32,10273,10",
        ).joinToString(separator = "\r\n")

        val files = ImgListParser.parse(body).filter { !it.isDirectory }

        assertEquals(2, files.size)
        assertEquals("P1234567.JPG", files[0].name)
        assertEquals(2634L, files[0].sizeBytes)
        assertEquals("P1234568.ORF", files[1].name)
        assertEquals(16777216L, files[1].sizeBytes)
    }

    @Test
    fun `skips hidden, system and volume entries`() {
        val body = listOf(
            "VER_100",
            "/DCIM/100OMSYS,HIDDEN.JPG,1,2,10273,0",   // hidden
            "/DCIM/100OMSYS,SYSTEM.JPG,1,4,10273,0",   // system
            "/DCIM/100OMSYS,VOLUME,1,8,10273,0",       // volume label
            "/DCIM/100OMSYS,NORMAL.JPG,1,32,10273,0",  // archive only — kept
        ).joinToString(separator = "\r\n")

        val entries = ImgListParser.parse(body)

        assertEquals(listOf("NORMAL.JPG"), entries.map { it.name })
    }

    @Test
    fun `skips malformed lines without failing the rest`() {
        val body = listOf(
            "VER_100",
            "not,enough,fields",
            "a,b,notanumber,0,0,0",
            "/DCIM/100OMSYS,GOOD.JPG,10,32,10273,0",
            "",
        ).joinToString(separator = "\r\n")

        val entries = ImgListParser.parse(body)

        assertEquals(listOf("GOOD.JPG"), entries.map { it.name })
    }

    @Test
    fun `returns empty for an empty body`() {
        assertEquals(emptyList<OpcListEntry>(), ImgListParser.parse(""))
    }

    @Test
    fun `decodes a known FAT stamp and rejects invalid ones`() {
        // 2026-01-01 00:00:00: date = (46 << 9) | (1 << 5) | 1 = 23585, time = 0
        assertEquals(
            LocalDateTime.of(2026, 1, 1, 0, 0, 0),
            ImgListParser.decodeFatDateTime(23585, 0),
        )
        // Zero month/day = corrupt record.
        assertNull(ImgListParser.decodeFatDateTime(0, 0))
        // Hours out of range.
        assertNull(ImgListParser.decodeFatDateTime(23585, 0x1F shl 11))
    }
}
