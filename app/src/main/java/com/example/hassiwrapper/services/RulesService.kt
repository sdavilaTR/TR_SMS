package com.example.hassiwrapper.services

import com.example.hassiwrapper.data.ConfigRepository

/**
 * Business rules and constraints — port of rules.service.js.
 */
class RulesService {

    data class WorkRules(
        val maxHoursPerDay: Int = 10,
        val minHoursPerDay: Int = 4,
        val scheduleStart: String = "06:00",
        val scheduleEnd: String = "20:00",
        val allowWeekends: Boolean = false
    )

    suspend fun resolvePersonRules(
        person: com.example.hassiwrapper.data.db.entities.PersonEntity,
        configRepo: ConfigRepository
    ): WorkRules {
        if (person.project_id != null) {
            val stored = configRepo.get("projectRules_${person.project_id}")
            // If stored rules exist, parse them (simplified: use defaults)
        }
        return WorkRules()
    }

    fun isWithinSchedule(rules: WorkRules, now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
        val dayOfWeek = now.get(java.util.Calendar.DAY_OF_WEEK)
        val isWeekend = dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY
        if (isWeekend && !rules.allowWeekends) return false

        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = now.get(java.util.Calendar.MINUTE)
        val currentTime = String.format("%02d:%02d", hour, minute)
        return currentTime >= rules.scheduleStart && currentTime <= rules.scheduleEnd
    }

    fun calculateWorkedHours(entryTime: String?, exitTime: String?): Double {
        if (entryTime == null || exitTime == null) return 0.0
        return try {
            val entry = java.time.Instant.parse(entryTime)
            val exit = java.time.Instant.parse(exitTime)
            val diffMs = exit.toEpochMilli() - entry.toEpochMilli()
            val hours = diffMs / (1000.0 * 60.0 * 60.0)
            Math.round(hours * 100.0) / 100.0
        } catch (_: Exception) { 0.0 }
    }
}
