package ru.smennik.app.domain

import java.time.LocalDateTime
import java.time.YearMonth

/** Selection is pure: can be unit-tested without Android context. */
fun nearestPlannedShift(shifts: List<WorkShift>, now: LocalDateTime): WorkShift? {
    val planned = shifts.filter { it.type == DayType.WORK }
    return planned.filter { !now.isBefore(it.startsAt) && now.isBefore(it.endsAt) }
        .minByOrNull { it.endsAt }
        ?: planned.filter { !it.startsAt.isBefore(now) }.minByOrNull { it.startsAt }
}

data class GoalProgress(val actualKopecks: Long, val goalKopecks: Long) {
    init { require(goalKopecks >= 0) }
    val hasGoal get() = goalKopecks > 0
    val progressPercent: Int get() =
        if (goalKopecks <= 0) 0 else ((actualKopecks.coerceAtLeast(0).toDouble() /
            goalKopecks) * 100).coerceIn(0.0, 999.0).toInt()
    val remainingKopecks: Long get() = (goalKopecks - actualKopecks).coerceAtLeast(0)
}

fun monthlyGoalProgress(
    workplaces: List<Workplace>,
    sessions: List<WorkSession>,
    payments: List<WagePayment>,
    month: YearMonth,
    nowMs: Long
): GoalProgress {
    val goals = workplaces.sumOf { it.monthlyGoalKopecks }
    val earned = workplaces.sumOf { job ->
        actualMonthSummary(sessions.filter { it.workplaceId == job.id },
            payments.filter { it.workplaceId == job.id },month,nowMs,job).earnedKopecks
    }
    return GoalProgress(earned,goals)
}
