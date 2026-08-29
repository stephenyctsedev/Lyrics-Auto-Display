package com.stephen.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses two-digit centisecond timestamps`() {
        val parsed = LrcParser.parse("[00:12.34]line one")
        assertEquals(1, parsed.lines.size)
        assertEquals(12_340L, parsed.lines[0].timeMs)
        assertEquals("line one", parsed.lines[0].text)
    }

    @Test
    fun `parses three-digit millisecond timestamps`() {
        val parsed = LrcParser.parse("[00:12.345]line one")
        assertEquals(12_345L, parsed.lines[0].timeMs)
    }

    @Test
    fun `parses minutes correctly`() {
        val parsed = LrcParser.parse("[02:05.00]line one")
        assertEquals(125_000L, parsed.lines[0].timeMs)
    }

    @Test
    fun `expands multiple timestamps on one line into multiple entries`() {
        val parsed = LrcParser.parse("[00:10.00][00:20.00]repeated line")
        assertEquals(2, parsed.lines.size)
        assertEquals(10_000L, parsed.lines[0].timeMs)
        assertEquals(20_000L, parsed.lines[1].timeMs)
        assertEquals("repeated line", parsed.lines[1].text)
    }

    @Test
    fun `skips metadata tags`() {
        val parsed = LrcParser.parse("[ar:Some Artist]\n[ti:Some Title]\n[00:01.00]line one")
        assertEquals(1, parsed.lines.size)
        assertEquals("line one", parsed.lines[0].text)
    }

    @Test
    fun `sorts out-of-order timestamps`() {
        val parsed = LrcParser.parse("[00:20.00]second\n[00:10.00]first")
        assertEquals(listOf("first", "second"), parsed.lines.map { it.text })
    }

    @Test
    fun `handles CRLF line endings`() {
        val parsed = LrcParser.parse("[00:01.00]line one\r\n[00:02.00]line two")
        assertEquals(2, parsed.lines.size)
        assertEquals("line one", parsed.lines[0].text)
    }

    @Test
    fun `strips UTF-8 BOM`() {
        val parsed = LrcParser.parse("﻿[00:01.00]line one")
        assertEquals(1, parsed.lines.size)
        assertEquals("line one", parsed.lines[0].text)
    }

    @Test
    fun `keeps timestamped blank lines as empty text`() {
        val parsed = LrcParser.parse("[00:01.00]line one\n[00:05.00]\n[00:09.00]line two")
        assertEquals(3, parsed.lines.size)
        assertEquals("", parsed.lines[1].text)
    }

    @Test
    fun `ignores lines with no timestamp`() {
        val parsed = LrcParser.parse("just plain text\n[00:01.00]line one")
        assertEquals(1, parsed.lines.size)
    }

    @Test
    fun `returns empty for blank input`() {
        assertTrue(LrcParser.parse("").lines.isEmpty())
        assertTrue(LrcParser.parse("   \n  ").lines.isEmpty())
    }

    @Test
    fun `trims surrounding whitespace from text`() {
        val parsed = LrcParser.parse("[00:01.00]   line one   ")
        assertEquals("line one", parsed.lines[0].text)
    }
}
