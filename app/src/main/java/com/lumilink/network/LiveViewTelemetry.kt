package com.lumilink.network

/**
 * Per-frame status from the OM System live-view RTP extension header ("Individual Information
 * Fields", OPC spec p.28-42): TLV records the camera pushes with every frame at streaming rate.
 * Everything here is fresher than the desclist snapshot, which can lag physical dial changes.
 */
data class LiveViewTelemetry(
    /** AF result: 0 = not performed, 1 = succeeded, 2 = failed (spec, field 2). */
    val afResult: Int? = null,
    /** AF frame in live-view pixels (x, y, w, h) — null while no frame is active. */
    val afFrame: IntArray? = null,
    /** Mounted-media status (field 3); observed 2 = card present. */
    val mediaStatus: Int? = null,
    /** Remaining shots on the card (field 5). */
    val remainingShots: Int? = null,
    /** Current shutter, e.g. "1/250" or "60s" (field 8: numerator/denominator). */
    val shutter: String? = null,
    /** Current aperture, e.g. "f/1.6" (field 9: F-number × 10). */
    val aperture: String? = null,
    /** Current exposure compensation, e.g. "+0.7" (field 10: value × 10, signed). */
    val exposureComp: String? = null,
    /** Current ISO: "Auto", "LOW" or the plain value (field 12; 0xFFFE = LOW). */
    val iso: String? = null,
    /** Focus mode: "S-AF", "C-AF" or "MF" (field 17). */
    val focusMode: String? = null,
)

/** Parses the RTP extension payload (after its 4-byte size header) into [LiveViewTelemetry]. */
object RtpTelemetryParser {

    fun parse(extension: ByteArray, offset: Int, length: Int): LiveViewTelemetry {
        var afResult: Int? = null
        var afFrame: IntArray? = null
        var mediaStatus: Int? = null
        var remainingShots: Int? = null
        var shutter: String? = null
        var aperture: String? = null
        var exposureComp: String? = null
        var iso: String? = null
        var focusMode: String? = null

        var i = offset
        val end = offset + length
        while (i + 4 <= end) {
            val id = u16(extension, i)
            val words = u16(extension, i + 2)
            val dataStart = i + 4
            val dataEnd = minOf(dataStart + words * 4, end)
            if (dataEnd > dataStart) {
                when (id) {
                    2 -> { // AF information: [color:u16][pad:u16][x:u32][y:u32][w:u32][h:u32]
                        afResult = u16(extension, dataStart)
                        if (afResult != 0 && dataEnd - dataStart >= 20) {
                            afFrame = intArrayOf(
                                u32(extension, dataStart + 4),
                                u32(extension, dataStart + 8),
                                u32(extension, dataStart + 12),
                                u32(extension, dataStart + 16),
                            )
                        }
                    }
                    3 -> mediaStatus = u32(extension, dataStart)
                    5 -> remainingShots = u32(extension, dataStart)
                    8 -> if (dataEnd - dataStart >= 12) { // shutter: min, max, current (num/den)
                        val num = u16(extension, dataStart + 8)
                        val den = u16(extension, dataStart + 10)
                        shutter = formatShutter(num, den)
                    }
                    9 -> if (dataEnd - dataStart >= 12) { // F-number: max, min, current (x10)
                        aperture = "f/" + formatDecimal(u32(extension, dataStart + 8))
                    }
                    10 -> if (dataEnd - dataStart >= 12) { // EV: max, min, current (x10, signed)
                        val raw = s32(extension, dataStart + 8)
                        exposureComp = (if (raw >= 0) "+" else "") + formatDecimal(raw)
                    }
                    12 -> if (dataEnd - dataStart >= 4) { // ISO: [value:u16][auto:u8][…]
                        val value = u16(extension, dataStart)
                        val auto = extension[dataStart + 2].toInt() and 0xFF
                        iso = when {
                            auto == 1 -> "Auto"
                            value == 0xFFFE -> "LOW"
                            else -> value.toString()
                        }
                    }
                    17 -> focusMode = when (u16(extension, dataStart)) {
                        0 -> "S-AF"
                        1 -> "C-AF"
                        2 -> "MF"
                        else -> null
                    }
                }
            }
            i = dataEnd
        }
        return LiveViewTelemetry(
            afResult = afResult,
            afFrame = afFrame,
            mediaStatus = mediaStatus,
            remainingShots = remainingShots,
            shutter = shutter,
            aperture = aperture,
            exposureComp = exposureComp,
            iso = iso,
            focusMode = focusMode,
        )
    }

    private fun formatShutter(numerator: Int, denominator: Int): String = when {
        denominator == 1 -> "${numerator}s"
        numerator == 1 -> "1/$denominator"
        else -> "$numerator/$denominator"
    }

    /** Whole-tenths value → display ("16" → "1.6"; "0" → "0.0"). */
    private fun formatDecimal(value: Int): String {
        val sign = if (value < 0) "-" else ""
        val abs = kotlin.math.abs(value)
        return "$sign${abs / 10}.${abs % 10}"
    }

    private fun u16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, i: Int): Int =
        (u16(b, i) shl 16) or u16(b, i + 2)

    private fun s32(b: ByteArray, i: Int): Int = u32(b, i)
}
