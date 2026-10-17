package com.lumilink.network

/**
 * One property from the OM System camera's `get_camprop.cgi?com=desc&propname=desclist` reply —
 * the camera's own inventory of what it exposes over Wi-Fi (verified against the real PEN
 * fixture captured on-device, see OpcConnection/OpcControl fixtures).
 *
 * @param name Property name, e.g. `isospeedvalue` or `focalvalue` (the aperture).
 * @param canSet True when the attribute is `getset` — i.e. the camera accepts a set call.
 * @param value The property's current value.
 * @param options The camera's allowed values, or empty when the property has no enum.
 */
data class OpcProp(
    val name: String,
    val canSet: Boolean,
    val value: String,
    val options: List<String> = emptyList(),
)

/**
 * Parses the desclist XML: `<desclist><desc><propname>…</propname><attribute>getset</attribute>
 * <value>…</value><enum>…</enum></desc>…</desclist>`. Tolerant — a malformed block is skipped,
 * not fatal, so one bad property can't hide the rest.
 */
object CamPropParser {

    fun parseDesclist(body: String): List<OpcProp> {
        val props = mutableListOf<OpcProp>()
        var cursor = 0
        while (true) {
            val descStart = body.indexOf("<desc>", cursor)
            if (descStart < 0) break
            val descEnd = body.indexOf("</desc>", descStart)
            if (descEnd < 0) break
            val block = body.substring(descStart, descEnd)
            cursor = descEnd

            val name = tag(block, "propname") ?: continue
            props += OpcProp(
                name = name,
                canSet = tag(block, "attribute")?.contains("set", ignoreCase = true) == true,
                value = tag(block, "value") ?: "",
                options = tag(block, "enum")
                    ?.split(' ')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList(),
            )
        }
        return props
    }

    /** Contents of the first `<[tag]>…</[tag]>` in [block], trimmed, or null if absent. */
    private fun tag(block: String, tag: String): String? {
        val start = block.indexOf("<$tag>")
        if (start < 0) return null
        val end = block.indexOf("</$tag>", start)
        if (end < 0) return null
        return block.substring(start + tag.length + 2, end).trim().ifEmpty { null }
    }
}
