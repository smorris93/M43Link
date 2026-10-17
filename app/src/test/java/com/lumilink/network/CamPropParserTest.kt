package com.lumilink.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the desclist parser. The real-body test uses the exact PEN fixture captured
 * on-device in record mode (trimmed to the first properties); the rest cover parsing edges.
 */
class CamPropParserTest {

    // Verbatim from the PEN's desclist reply (see docs/PROJECT_CONTEXT.md, M3).
    private val realBody = """<?xml version="1.0"?><desclist><desc><propname>shutspeedvalue</propname><attribute>getset</attribute><value>250</value><enum>60" 50" 40" 30" 25" 20" 15" 13" 10" 8" 6" 5" 4" 3.2" 2.5" 2" 1.6" 1.3" 1" 1.3 1.6 2 2.5 3 4 5 6 8 10 13 15 20 25 30 40 50 60 80 100 125 160 200 250 320 400 500 640 800 1000 1250 1600 2000 2500 3200 4000</enum></desc><desc><propname>touchactiveframe</propname><attribute>get</attribute><value>0048-0036_0224x0168</value></desc><desc><propname>takemode</propname><attribute>getset</attribute><value>A</value><enum>iAuto P A S M Scene2 ART movie B</enum></desc><desc><propname>focalvalue</propname><attribute>getset</attribute><value>1.6</value><enum>1.0 1.1 1.2 1.4 1.6 1.8 2.0 2.2 2.5 2.8</enum></desc><desc><propname>isospeedvalue</propname><attribute>getset</attribute><value>Auto</value><enum>Auto 64 100 200 250 320 400 500 640 800 1000 1250 1600</enum></desc></desclist>"""

    @Test
    fun `parses the real PEN desclist`() {
        val props = CamPropParser.parseDesclist(realBody)

        assertEquals(5, props.size)
        val byName = props.associateBy { it.name }

        assertEquals("250", byName.getValue("shutspeedvalue").value)
        assertTrue(byName.getValue("shutspeedvalue").canSet)
        assertEquals(55, byName.getValue("shutspeedvalue").options.size)
        assertEquals("Auto", byName.getValue("isospeedvalue").value)
        assertEquals("1.6", byName.getValue("focalvalue").value)
        assertEquals("A", byName.getValue("takemode").value)
    }

    @Test
    fun `get-only properties are not settable and may lack an enum`() {
        val props = CamPropParser.parseDesclist(realBody)
        val touchFrame = props.first { it.name == "touchactiveframe" }

        assertFalse(touchFrame.canSet)
        assertEquals("0048-0036_0224x0168", touchFrame.value)
        assertEquals(emptyList<String>(), touchFrame.options)
    }

    @Test
    fun `skips malformed blocks without losing the rest`() {
        val body = """<desclist>
            <desc><propname>good</propname><attribute>get</attribute><value>1</value></desc>
            <desc><attribute>get</attribute><value>2</value></desc>
            <desc><propname>alsoGood</propname><attribute>getset</attribute><value>3</value><enum>a b</enum></desc>
        </desclist>"""

        val props = CamPropParser.parseDesclist(body)

        assertEquals(listOf("good", "alsoGood"), props.map { it.name })
        assertEquals(listOf("a", "b"), props.last().options)
    }

    @Test
    fun `returns empty for a body without descs`() {
        assertEquals(emptyList<OpcProp>(), CamPropParser.parseDesclist(""))
        assertEquals(emptyList<OpcProp>(), CamPropParser.parseDesclist("random junk"))
    }
}
