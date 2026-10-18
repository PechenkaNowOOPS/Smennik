package ru.smennik.app.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

enum class DayType { WORK, OFF, VACATION, SICK, HOLIDAY }

/** Manually changed days are never replaced by ordinary pattern regeneration. */
enum class ShiftOrigin { MANUAL, GENERATED }

data class WorkShift(
    val date: LocalDate,
    val type: DayType,
    val startMinutes: Int = 8 * 60,
    val endMinutes: Int = 20 * 60,
    val hourlyKopecks: Long = 35_000L,
    val origin: ShiftOrigin = ShiftOrigin.MANUAL,
    val workplaceId: Long = 1
) {
    init {
        require(startMinutes in 0..1439 && endMinutes in 0..1439)
        require(hourlyKopecks in 0..10_000_000L)
        require(workplaceId > 0)
    }

    /** Matching clock times mean a full 24-hour shift, not a zero-duration shift. */
    val durationMinutes: Int
        get() = if (type != DayType.WORK) 0
        else (endMinutes - startMinutes + 1440).let { if (it == 1440) 1440 else it % 1440 }

    val startsAt: LocalDateTime get() = date.atStartOfDay().plusMinutes(startMinutes.toLong())
    val endsAt: LocalDateTime get() = startsAt.plusMinutes(durationMinutes.toLong())

    /** Forecast, not a record of actual work or payment. Rounded down to kopecks. */
    val projectedKopecks: Long get() = durationMinutes * hourlyKopecks / 60
}

/** Finds the shift actually in progress, including yesterday's overnight shift. */
fun activeShiftAt(shifts: List<WorkShift>, time: LocalDateTime): WorkShift? =
    shifts.asSequence()
        .filter { it.type == DayType.WORK && !time.isBefore(it.startsAt) && time.isBefore(it.endsAt) }
        .maxByOrNull { it.startsAt }

data class WorkPattern(val daysOn: Int, val daysOff: Int) {
    init { require(daysOn > 0 && daysOff > 0) }

    /** 5/2 is Monday–Friday, all other cycles start with a working day at anchor. */
    fun isWorkDay(anchor: LocalDate, date: LocalDate): Boolean {
        if (daysOn == 5 && daysOff == 2) return date.dayOfWeek.value <= 5
        val offset = java.time.temporal.ChronoUnit.DAYS.between(anchor, date)
        return Math.floorMod(offset, (daysOn + daysOff).toLong()) < daysOn
    }

    fun generatedWorkDays(anchor: LocalDate, lengthDays: Int = 90): List<LocalDate> {
        require(lengthDays in 1..366)
        return (0 until lengthDays).asSequence()
            .map { anchor.plusDays(it.toLong()) }
            .filter { isWorkDay(anchor, it) }
            .toList()
    }
}

data class MonthSummary(
    val shifts: Int,
    val hours: Int,
    val projectedKopecks: Long,
    val remainderMinutes: Int = 0
)

fun monthlySummary(shifts: List<WorkShift>, month: YearMonth): MonthSummary {
    // Attribution follows the shift start date, including hours worked past midnight.
    val selected = shifts.filter { it.type == DayType.WORK && YearMonth.from(it.date) == month }
    val minutes = selected.sumOf { it.durationMinutes }
    return MonthSummary(
        shifts = selected.size,
        hours = minutes / 60,
        projectedKopecks = selected.sumOf { it.projectedKopecks },
        remainderMinutes = minutes % 60
    )
}

fun displayTime(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

fun parseTime(value: String): Int? {
    val parts = value.trim().split(":")
    if (parts.size != 2 || parts.any { it.length !in 1..2 || it.any { c -> !c.isDigit() } }) return null
    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    if (hours !in 0..23 || minutes !in 0..59) return null
    return hours * 60 + minutes
}
