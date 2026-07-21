package com.example.hassiwrapper.services

/**
 * Pure decision logic for whether a delta batch's deactivated spool rows are safe to purge
 * locally. Extracted out of MainActivity.syncSmsData so it's unit-testable without
 * Room/Retrofit/Activity — see the 2026-07-07 139k-spool purge incident this guards against
 * (a backend bug returned an entire project as is_active=0 in one delta call).
 *
 * minCount matches the backend's SmsRepository.IsAnomalousDeltaBatch floor (5), but maxRatio
 * is intentionally stricter than the backend's thresholdRatio (0.2 here vs 0.8 there): a false
 * trip here only costs a cheap full resync, while the backend rejecting a batch means a 500 to
 * every client, so it only blocks truly extreme (>80%) ratios. This is a second, more paranoid
 * layer behind the backend guard, not a mirror of it.
 */
object SpoolDeltaGuard {

    data class Decision(
        /** True: apply deleteByIds on the deactivated rows. False: skip the purge entirely. */
        val shouldPurge: Boolean,
        /** True: clear the delta cursor so the next sync cycle falls back to a full sync. */
        val shouldResetCursor: Boolean
    )

    fun evaluate(deactivatedCount: Int, localCount: Int, minCount: Int = 5, maxRatio: Double = 0.2): Decision {
        if (deactivatedCount == 0) return Decision(shouldPurge = false, shouldResetCursor = false)
        val ratio = if (localCount > 0) deactivatedCount.toDouble() / localCount else 0.0
        val anomalous = deactivatedCount >= minCount && ratio > maxRatio
        return Decision(shouldPurge = !anomalous, shouldResetCursor = anomalous)
    }
}
