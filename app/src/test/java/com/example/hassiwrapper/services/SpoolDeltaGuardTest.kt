package com.example.hassiwrapper.services

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the guard that stopped the 2026-07-07 139k-spool purge incident from recurring.
 * A prior version of this guard required localCount>200, which left small projects
 * unprotected — these cases exist to pin down the fixed behavior.
 */
class SpoolDeltaGuardTest {

    @Test
    fun `no deactivated rows is always a no-op`() {
        val decision = SpoolDeltaGuard.evaluate(deactivatedCount = 0, localCount = 5000)
        assertEquals(false, decision.shouldPurge)
        assertEquals(false, decision.shouldResetCursor)
    }

    @Test
    fun `small legitimate deactivation below the count floor purges normally`() {
        // 1 of 6 spools legitimately deactivated — below the size-5 floor, not an anomaly.
        val decision = SpoolDeltaGuard.evaluate(deactivatedCount = 1, localCount = 6)
        assertEquals(true, decision.shouldPurge)
        assertEquals(false, decision.shouldResetCursor)
    }

    @Test
    fun `large project mass wipe is caught (the original MERAM incident shape)`() {
        // 139733 of 139733 spools returned inactive in one delta batch.
        val decision = SpoolDeltaGuard.evaluate(deactivatedCount = 139733, localCount = 139733)
        assertEquals(false, decision.shouldPurge)
        assertEquals(true, decision.shouldResetCursor)
    }

    @Test
    fun `small project mass wipe is now also caught (the localCount over 200 gap that was closed)`() {
        // A 50-spool project entirely wiped used to sail through the old localCount>200 floor.
        val decision = SpoolDeltaGuard.evaluate(deactivatedCount = 50, localCount = 50)
        assertEquals(false, decision.shouldPurge)
        assertEquals(true, decision.shouldResetCursor)
    }

    @Test
    fun `ratio exactly at the threshold is not anomalous, strictly above is`() {
        val atThreshold = SpoolDeltaGuard.evaluate(deactivatedCount = 20, localCount = 100)
        assertEquals(true, atThreshold.shouldPurge)
        assertEquals(false, atThreshold.shouldResetCursor)

        val justAbove = SpoolDeltaGuard.evaluate(deactivatedCount = 21, localCount = 100)
        assertEquals(false, justAbove.shouldPurge)
        assertEquals(true, justAbove.shouldResetCursor)
    }

    @Test
    fun `count floor alone does not trip the guard without a high ratio`() {
        // 10 deactivated (above the size-5 floor) out of 10000 local — legitimate daily churn.
        val decision = SpoolDeltaGuard.evaluate(deactivatedCount = 10, localCount = 10000)
        assertEquals(true, decision.shouldPurge)
        assertEquals(false, decision.shouldResetCursor)
    }

    @Test
    fun `zero local count never divides by zero (nothing local to wipe either way)`() {
        val decision = SpoolDeltaGuard.evaluate(deactivatedCount = 5, localCount = 0)
        assertEquals(true, decision.shouldPurge)
        assertEquals(false, decision.shouldResetCursor)
    }

    // MainActivity.syncSmsData reuses this same evaluate() for the full-sync path: instead of
    // "deactivatedCount", it passes (localSpools.size - merged.size) — how many previously-local
    // spools the full response failed to account for. Same shape, same guard, no separate logic
    // to test — these cases just pin down that reuse is correct for that interpretation.

    @Test
    fun `full sync returning zero rows for a populated project is caught (not just 100% deactivation)`() {
        // 34890 local spools, full response comes back with 3 usable rows -> dropped = 34887.
        val decision = SpoolDeltaGuard.evaluate(deactivatedCount = 34887, localCount = 34890)
        assertEquals(false, decision.shouldPurge)
        assertEquals(true, decision.shouldResetCursor)
    }

    @Test
    fun `full sync legitimate shrink under the ratio floor is not caught`() {
        // 500 of 10000 spools legitimately gone (soft-deleted/completed) between full syncs.
        val decision = SpoolDeltaGuard.evaluate(deactivatedCount = 500, localCount = 10000)
        assertEquals(true, decision.shouldPurge)
        assertEquals(false, decision.shouldResetCursor)
    }

    @Test
    fun `full sync on a project with no local spools never trips (nothing to protect)`() {
        // droppedCount is coerced to >=0 by the caller, so a brand-new/empty project always
        // evaluates as deactivatedCount=0 here regardless of merged.size.
        val decision = SpoolDeltaGuard.evaluate(deactivatedCount = 0, localCount = 0)
        assertEquals(false, decision.shouldPurge)
        assertEquals(false, decision.shouldResetCursor)
    }
}
