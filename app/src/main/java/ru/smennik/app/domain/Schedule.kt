package ru.smennik.app.domain

import java.time.LocalDate
import java.time.YearMonth

enum class DayType { WORK, OFF }

data class WorkShift(
    val date: LocalDate,
    val type: DayType,
    val startMinutes: Int = 8 * 60,
    val endMinutes: Int = 20 * 60,
    val hourlyKopecks: Long = 35_000L
) {
    init {
        require(startMinutes in 0..1439 && endMinutes in 0..1439)
        require(hourlyKopecks in 0..10_000_000L)
        if (type == DayType.WORK) require(startMinutes != endMinutes) { "Нулевая смена недопустима" }
    }

    val durationMinutes: Int
        get() = if (type == DayType.OFF) 0
        else (endMinutes - startMinutes + 1440) % 1440

    /** Прогноз начислений, а не факт выплаты. Дробные копейки отбрасываются. */
    val projectedKopecks: Long get() = durationMinutes * hourlyKopecks / 60
}

data class WorkPattern(val daysOn: Int, val daysOff: Int) {
    init { require(daysOn > 0 && daysOff > 0) }

    /** Первый день цикла — рабочий. Генератор создаёт только рабочие дни. */
    fun generatedWorkDays(anchor: LocalDate, lengthDays: Int = 90): List<LocalDate> {
        require(lengthDays in 1..366)
        return (0 until lengthDays).asSequence()
            .filter { it % (daysOn + daysOff) < daysOn }
            .map { anchor.plusDays(it.toLong()) }
            .toList()
    }
}

data class MonthSummary(val shifts: Int, val hours: Int, val projectedKopecks: Long)

fun monthlySummary(shifts: List<WorkShift>, month: YearMonth): MonthSummary {
    val selected = shifts.filter { it.type == DayType.WORK && YearMonth.from(it.date) == month }
    return MonthSummary(
        shifts = selected.size,
        hours = selected.sumOf { it.durationMinutes } / 60,
        projectedKopecks = selected.sumOf { it.projectedKopecks }
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
