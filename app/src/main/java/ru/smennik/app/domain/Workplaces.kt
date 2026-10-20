package ru.smennik.app.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** All data remains on-device. A person owns one or more workplaces. */
data class Person(val id: Long, val name: String) {
    init {
        require(id > 0)
        require(name.isNotBlank() && name.length <= 60)
    }
}

/** Workplaces belong to a person; pre-0.10.0 data belongs to person #1. */
data class Workplace(
    val id: Long,
    val name: String,
    val hourlyKopecks: Long = 35_000L,
    val nightBonusPercent: Int = 0,
    val overtimeBonusPercent: Int = 0,
    val overtimeAfterMinutes: Int = 480,
    val monthlyGoalKopecks: Long = 0L,
    val personId: Long = 1L
) {
    init {
        require(id > 0)
        require(name.isNotBlank() && name.length <= 60)
        require(hourlyKopecks in 0..10_000_000L)
        require(nightBonusPercent in 0..200 && overtimeBonusPercent in 0..200)
        require(overtimeAfterMinutes in 60..1440)
        require(monthlyGoalKopecks in 0..100_000_000_00L)
        require(personId > 0)
    }
}

/** Arbitrary repeating work/rest sequence. 'Р' = working, 'В' = rest. */
data class CustomCycle(val mask: String) {
    init {
        require(mask.length in 2..60 && mask.all { it == 'Р' || it == 'В' })
        require('Р' in mask && 'В' in mask)
    }
    fun isWorkDay(anchor: LocalDate, date: LocalDate): Boolean {
        val delta = ChronoUnit.DAYS.between(anchor, date)
        return mask[Math.floorMod(delta, mask.length.toLong()).toInt()] == 'Р'
    }
}
