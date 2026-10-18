package ru.smennik.app.domain

/**
 * Live timer figures are derived from absolute persisted timestamps.
 * There is no background ticking state to lose when Android kills the process.
 */
data class WorkdayMetrics(
    val paidSeconds: Long,
    val unpaidBreakSeconds: Long,
    val earnedKopecks: Long,
    val plannedMinutes: Int?
) {
    val overtimeMinutes: Long get() =
        plannedMinutes?.let { (paidSeconds / 60 - it).coerceAtLeast(0L) } ?: 0L
    val remainingMinutes: Long? get() =
        plannedMinutes?.let { (it - paidSeconds / 60).coerceAtLeast(0L) }
}

fun calculateWorkdayMetrics(
    session: WorkSession, nowMs: Long, planned: WorkShift? = null
): WorkdayMetrics {
    val end = session.endedMs ?: maxOf(nowMs, session.startedMs)
    val openBreak = session.pauseStartedMs?.let { (end - it).coerceAtLeast(0L) } ?: 0L
    val counted = (end-session.startedMs-session.totalPausedMs-openBreak).coerceAtLeast(0)
    return WorkdayMetrics(
        paidSeconds = counted / 1000,
        unpaidBreakSeconds = (session.totalPausedMs+openBreak)/1000,
        earnedKopecks = (counted / 60_000) * session.hourlyKopecks / 60,
        plannedMinutes = planned?.takeIf { it.type == DayType.WORK }?.durationMinutes
    )
}
