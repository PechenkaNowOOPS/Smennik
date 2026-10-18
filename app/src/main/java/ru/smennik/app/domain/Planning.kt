package ru.smennik.app.domain

import java.time.LocalDate
import java.time.LocalDateTime

/** All-day leave is not a scheduled work interval. Overnight shifts extend into the next day. */
data class ShiftConflict(val first: WorkShift, val second: WorkShift) {
    val from: LocalDateTime = maxOf(first.startsAt, second.startsAt)
    val until: LocalDateTime = minOf(first.endsAt, second.endsAt)
    val overlapMinutes: Long get() = java.time.Duration.between(from, until).toMinutes()
}

/** Detect conflicts between different employers, including shifts crossing midnight. */
fun findShiftConflicts(shifts: List<WorkShift>): List<ShiftConflict> {
    val ordered = shifts.filter { it.type == DayType.WORK }
        .sortedWith(compareBy<WorkShift> { it.startsAt }.thenBy { it.workplaceId })
    val result = mutableListOf<ShiftConflict>()
    for (i in ordered.indices) {
        val a = ordered[i]
        for (j in i + 1 until ordered.size) {
            val b = ordered[j]
            if (!b.startsAt.isBefore(a.endsAt)) break
            if (a.workplaceId == b.workplaceId) continue
            if (a.startsAt.isBefore(b.endsAt) && b.startsAt.isBefore(a.endsAt))
                result += ShiftConflict(a, b)
        }
    }
    return result
}

/** Calendar operations never touch another employer's shifts. */
data class BulkPlan(
    val workplaceId: Long,
    val dates: List<LocalDate>,
    val type: DayType?,
    val startMinutes: Int = 8 * 60,
    val endMinutes: Int = 20 * 60,
    val hourlyKopecks: Long = 35_000,
    val replaceExisting: Boolean = false
) {
    init {
        require(workplaceId > 0)
        require(dates.isNotEmpty() && dates.size <= 93 && dates.toSet().size == dates.size)
        require(startMinutes in 0..1439 && endMinutes in 0..1439)
        require(hourlyKopecks in 0..10_000_000)
    }
}
