package ru.smennik.app.domain

import java.time.ZoneId

/**
 * Automatic work timers follow the local calendar plan. A timer starts at the
 * scheduled wall-clock time even when Android delivers the alarm late.
 *
 * Any session (running OR completed) for the same workplace/date prevents an
 * automatic duplicate. A manually finished shift must stay finished.
 */
fun automaticStartCandidates(
    shifts: List<WorkShift>,
    sessions: List<WorkSession>,
    nowMs: Long,
    zone: ZoneId
): List<WorkShift> {
    val recorded = sessions.asSequence().map { it.workplaceId to it.date }.toSet()
    return shifts.asSequence()
        .filter { it.type == DayType.WORK && (it.workplaceId to it.date) !in recorded }
        .filter {
            val start = it.startsAt.atZone(zone).toInstant().toEpochMilli()
            val end = it.endsAt.atZone(zone).toInstant().toEpochMilli()
            nowMs >= start && nowMs < end
        }
        .sortedWith(compareBy<WorkShift> { it.startsAt }.thenBy { it.workplaceId })
        .toList()
}

/**
 * Next foreground wake-up. A due shift returns 0; a completed shift returns
 * null rather than continually re-triggering the automatic start callback.
 */
fun millisUntilNextAutomaticStart(
    shifts: List<WorkShift>,
    sessions: List<WorkSession>,
    nowMs: Long,
    zone: ZoneId
): Long? {
    val recorded = sessions.asSequence().map { it.workplaceId to it.date }.toSet()
    return shifts.asSequence()
        .filter { it.type == DayType.WORK && (it.workplaceId to it.date) !in recorded }
        .map {
            it.startsAt.atZone(zone).toInstant().toEpochMilli() to
                it.endsAt.atZone(zone).toInstant().toEpochMilli()
        }
        .filter { (_, end) -> end > nowMs }
        .map { (start, _) -> (start - nowMs).coerceAtLeast(0L) }
        .minOrNull()
}
