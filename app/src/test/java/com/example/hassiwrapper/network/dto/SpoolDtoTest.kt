package com.example.hassiwrapper.network.dto

import com.example.hassiwrapper.data.model.Spool
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class SpoolDtoTest {

    private fun spool(spoolCode: String, spoolSuffix: String?, revision: String?): Spool = Spool(
        spoolId = 1L,
        projectId = 6,
        spoolCode = spoolCode,
        spoolSuffix = spoolSuffix,
        lineCode = null,
        unitId = null,
        service = null,
        train = null,
        module = null,
        isoTypeId = null,
        specId = null,
        isoRevisionDate = null,
        subcontractorId = null,
        areaId = null,
        isActive = true,
        createdAt = "",
        createdBy = "",
        updatedAt = null,
        updatedBy = null,
        revision = revision
    )

    @Test
    fun `displayCode with clean code appends suffix and revision`() {
        val s = spool("774-BD-20041-008", "SP03", "01A")
        assertEquals("774-BD-20041-008-SP03-01A", s.displayCode)
    }

    @Test
    fun `displayCode without revision unchanged`() {
        val s = spool("774-BD-20041-008", "SP03", null)
        assertEquals("774-BD-20041-008-SP03", s.displayCode)
    }

    @Test
    fun `displayCode without suffix but with revision`() {
        val s = spool("774-BD-20041-008", null, "01A")
        assertEquals("774-BD-20041-008-01A", s.displayCode)
    }

    @Test
    fun `displayCode does not duplicate suffix when spoolCode already bakes it in (JAFURAH shape)`() {
        // Real device data: spool_code = "774-BD-20041-008-SP03", spool_suffix = "SP03" (duplicated by backend).
        val s = spool("774-BD-20041-008-SP03", "SP03", "01A")
        assertEquals("774-BD-20041-008-SP03-01A", s.displayCode)
    }

    @Test
    fun `displayCode baked-in shape without revision does not duplicate suffix`() {
        val s = spool("774-BD-20041-008-SP03", "SP03", null)
        assertEquals("774-BD-20041-008-SP03", s.displayCode)
    }

    @Test
    fun `ISO_rev_number JSON key parses into revision via toModel`() {
        val json = """{"spool_id":"774-BD-20041-008","spool_suffix":"SP03","ISO_rev_number":"01A"}"""
        val dto = Gson().fromJson(json, SpoolDto::class.java)
        assertEquals("01A", dto.toModel().revision)
        assertEquals("01A", dto.toEntity().revision)
    }

    @Test
    fun `ISO_rev_number takes precedence over legacy revision key when both present`() {
        val json = """{"spool_id":"774-BD-20041-008","spool_suffix":"SP03","revision":"OLD","ISO_rev_number":"01A"}"""
        val dto = Gson().fromJson(json, SpoolDto::class.java)
        assertEquals("01A", dto.toModel().revision)
    }

    @Test
    fun `falls back to legacy revision key when ISO_rev_number absent`() {
        val json = """{"spool_id":"774-BD-20041-008","spool_suffix":"SP03","revision":"01A"}"""
        val dto = Gson().fromJson(json, SpoolDto::class.java)
        assertEquals("01A", dto.toModel().revision)
    }

    @Test
    fun `blank ISO_rev_number does not clobber missing revision`() {
        val json = """{"spool_id":"774-BD-20041-008","spool_suffix":"SP03","ISO_rev_number":""}"""
        val dto = Gson().fromJson(json, SpoolDto::class.java)
        assertEquals(null, dto.toModel().revision)
    }
}
