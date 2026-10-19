package ru.smennik.app.domain

import java.time.ZoneId
import java.util.Locale

/**
 * Wall-clock countdown to the scheduled end, independent of actual paid time.
 * Unpaid breaks and a late start do not shift the planned finish time.
 */
data class ShiftEndCountdown(
    val endsAtMs: Long,
    val remainingSeconds: Long,
    val overdueSeconds: Long
) {
    val isFinishedBySchedule: Boolean get() = remainingSeconds == 0L
}

/** Workplaces may have shifts on the same date; only the session's job is relevant. */
fun plannedShiftForSession(shifts: List<WorkShift>, session: WorkSession): WorkShift? =
    shifts.firstOrNull {
        it.type == DayType.WORK &&
            it.date == session.date &&
            it.workplaceId == session.workplaceId
    }

/**
 * Based on the shift's calendar date and its local scheduled end.
 * WorkShift.endsAt already handles overnight and 24-hour shifts.
 */
fun shiftEndCountdown(shift: WorkShift, nowMs: Long, zone: ZoneId): ShiftEndCountdown? {
    if (shift.type != DayType.WORK) return null
    val endMs = shift.endsAt.atZone(zone).toInstant().toEpochMilli()
    val deltaMs = endMs - nowMs
    return ShiftEndCountdown(
        endsAtMs = endMs,
        // Round up so 1..999 milliseconds still display 00:00:01.
        remainingSeconds = if (deltaMs > 0L) deltaMs / 1000L +
            if (deltaMs % 1000L == 0L) 0L else 1L else 0L,
        overdueSeconds = if (deltaMs < 0L) -deltaMs / 1000L else 0L
    )
}

/** Hours are unbounded: never wrap 24+ hour durations back to 00:00:00. */
fun formatClockDuration(seconds: Long): String {
    require(seconds >= 0L)
    return String.format(Locale.ROOT, "%02d:%02d:%02d",
        seconds / 3600L, seconds / 60L % 60L, seconds % 60L)
}
