package io.legado.app.help.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XPointerTest {

    @Test
    fun `parses classic text pointer`() {
        val pointer = XPointerParser.parse("/body/DocFragment[8]/body/div[2]/p[4]/text()[1].96")
        assertEquals(8, pointer?.fragmentIndex)
        assertEquals(3, pointer?.steps?.size)
        assertEquals(XPointerStep.Element("div", 2), pointer?.steps?.get(0))
        assertEquals(XPointerStep.Element("p", 4), pointer?.steps?.get(1))
        assertEquals(XPointerStep.Text(1), pointer?.steps?.get(2))
        assertEquals(96, pointer?.point)
        assertEquals(
            "/body/DocFragment[8]/body/div[2]/p[4]/text()[1].96",
            pointer?.let(XPointerParser::serialize),
        )
    }

    @Test
    fun `parses single spine pointer without fragment index`() {
        val pointer = XPointerParser.parse("/body/DocFragment/body/p[42]")
        assertNull(pointer?.fragmentIndex)
        assertEquals(listOf(XPointerStep.Element("p", 42)), pointer?.steps)
        assertEquals("/body/DocFragment/body/p[42]", pointer?.let(XPointerParser::serialize))
    }

    @Test
    fun `parses body-only pointer`() {
        val pointer = XPointerParser.parse("/body/DocFragment[1]/body")
        assertEquals(1, pointer?.fragmentIndex)
        assertEquals(emptyList<XPointerStep>(), pointer?.steps)
        assertEquals("/body/DocFragment[1]/body", pointer?.let(XPointerParser::serialize))
    }

    @Test
    fun `parses text without index and zero point`() {
        val pointer = XPointerParser.parse("/body/DocFragment[1]/body/p[1]/text().0")
        assertEquals(XPointerStep.Text(null), pointer?.steps?.last())
        assertEquals(0, pointer?.point)
        assertEquals(
            "/body/DocFragment[1]/body/p[1]/text().0",
            pointer?.let(XPointerParser::serialize),
        )
    }

    @Test
    fun `parses element point`() {
        val pointer = XPointerParser.parse("/body/DocFragment[2]/body/div[3].7")
        assertEquals(listOf(XPointerStep.Element("div", 3)), pointer?.steps)
        assertEquals(7, pointer?.point)
    }

    @Test
    fun `rejects malformed pointers`() {
        assertNull(XPointerParser.parse(""))
        assertNull(XPointerParser.parse("/body/p[1]"))
        assertNull(XPointerParser.parse("/body/DocFragment[x]/body/p[1]"))
        assertNull(XPointerParser.parse("/body/DocFragment[0]/body/p[1]"))
        assertNull(XPointerParser.parse("/body/DocFragment[1]/body/p[1]text()"))
        assertNull(XPointerParser.parse("/body/DocFragment[1]/body/p[1]/text()[2].x"))
    }
}
