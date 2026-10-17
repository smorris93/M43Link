package com.lumilink.network

import java.time.LocalDateTime

/**
 * One entry from the OM System `get_imglist.cgi` directory listing (the `VER_100` CSV format):
 * `dirName,fileName,size,attrib,dateRaw,timeRaw` per line. `attrib` carries the FAT attribute
 * bits (16 = directory, 2/4/8 = hidden/system/volume, skipped), and the date/time fields are
 * FAT-packed stamps decoded by [decodeFatDateTime].
 *
 * Verified against the real PEN (`/DCIM` listing captured on-device, see OpcConnection fixtures)
 * and cross-checked with the Olympus View implementation of the same format.
 */
data class OpcListEntry(
    /** Parent directory as the camera reports it, e.g. `/DCIM`. */
    val directory: String,
    /** File or folder name, e.g. `P1234567.JPG` or `100OMSYS`. */
    val name: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val attrib: Int,
    val dateRaw: Int,
    val timeRaw: Int,
) {
    /** Full camera path of this entry, e.g. `/DCIM/100OMSYS/P1234567.JPG`. */
    val fullPath: String get() = "$directory/$name"
}

object ImgListParser {

    /**
     * Parses a `get_imglist.cgi` body. Tolerant by design: the header line, malformed records and
     * hidden/system/volume entries are skipped rather than failing the whole listing — one odd
     * file on the card must not hide the rest.
     */
    fun parse(body: String): List<OpcListEntry> {
        val entries = mutableListOf<OpcListEntry>()
        for (line in body.trim().split(Regex("\\r?\\n"))) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("VER", ignoreCase = true)) continue
            val parts = trimmed.split(",")
            if (parts.size < 6) continue

            val attrib = parts[3].trim().toIntOrNull() ?: continue
            // FAT attributes: hidden (2), system (4), volume label (8).
            if (attrib and 2 != 0 || attrib and 4 != 0 || attrib and 8 != 0) continue

            entries += OpcListEntry(
                directory = parts[0].trim(),
                name = parts[1].trim(),
                sizeBytes = parts[2].trim().toLongOrNull() ?: continue,
                isDirectory = attrib and 16 != 0,
                attrib = attrib,
                dateRaw = parts[4].trim().toIntOrNull() ?: continue,
                timeRaw = parts[5].trim().toIntOrNull() ?: continue,
            )
        }
        return entries
    }

    /**
     * Decodes a FAT packed date/time pair into a [LocalDateTime], or null if the record is
     * invalid (e.g. zero month/day) — the caller skips corrupt stamps rather than guessing.
     */
    fun decodeFatDateTime(dateRaw: Int, timeRaw: Int): LocalDateTime? {
        val year = ((dateRaw shr 9) and 0x7F) + 1980
        val month = (dateRaw shr 5) and 0x0F
        val day = dateRaw and 0x1F
        val hours = (timeRaw shr 11) and 0x1F
        val minutes = (timeRaw shr 5) and 0x3F
        val seconds = (timeRaw and 0x1F) * 2
        if (month < 1 || month > 12 || day < 1 || day > 31) return null
        if (hours > 23 || minutes > 59 || seconds > 59) return null
        return LocalDateTime.of(year, month, day, hours, minutes, seconds)
    }
}
