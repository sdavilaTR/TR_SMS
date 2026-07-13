package com.example.hassiwrapper.ui.qrscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrParserTest {

    @Test
    fun `bare JAFURAH tag parses into all components`() {
        val result = parseQr("821-RP-25107-002-SP01-01A")
        assertTrue(result is QrResult.Spool)
        val spool = result as QrResult.Spool
        assertEquals("821-RP-25107-002", spool.spoolCode)
        assertEquals("SP01", spool.spoolSuffix)
        assertEquals("821", spool.unitCode)
        assertEquals("RP", spool.service)
        assertEquals("25107", spool.lineCode)
        assertEquals("002", spool.sitNumber)
        assertEquals("01A", spool.revision)
    }

    @Test
    fun `case-insensitive JAFURAH tag still parses`() {
        val result = parseQr("821-rp-25107-002-sp01-01a")
        assertTrue(result is QrResult.Spool)
        val spool = result as QrResult.Spool
        assertEquals("SP01", spool.spoolSuffix.orEmpty().uppercase())
        assertEquals("01a", spool.revision)
    }

    @Test
    fun `existing packing list label format still parses unaffected`() {
        val raw = "JAFURAH PACKING LIST\nID: 886-600C-65440-002 Suffix: SP01 Desc: Elbow Diameter: 6 Lenght: 1.2 Priority: 1"
        val result = parseQr(raw)
        assertTrue(result is QrResult.Spool)
        val spool = result as QrResult.Spool
        assertEquals("886-600C-65440-002", spool.spoolCode)
        assertEquals("SP01", spool.spoolSuffix)
        assertNull(spool.unitCode)
    }

    @Test
    fun `vehicle plate with dashes is not misclassified as spool`() {
        val result = parseQr("ABC-1234")
        assertTrue(result is QrResult.VehiclePlate)
        assertEquals("ABC-1234", (result as QrResult.VehiclePlate).plate)
    }

    @Test
    fun `VEH prefix still takes priority`() {
        val result = parseQr("VEH:some-uuid-value")
        assertTrue(result is QrResult.VehicleBadge)
        assertEquals("some-uuid-value", (result as QrResult.VehicleBadge).uuid)
    }

    @Test
    fun `malformed JAFURAH-shaped string missing SPnn falls back to plate`() {
        val result = parseQr("821-RP-25107-002-XX01-01A")
        assertTrue(result is QrResult.VehiclePlate)
    }
}
