package ru.smennik.app.domain

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.min
import kotlin.math.max
import java.time.LocalDate
import java.time.YearMonth

/** Timed break. Paid breaks do not reduce working minutes. */
data class WorkBreak(val startMs: Long, val endMs: Long, val paid: Boolean = false) {
    init { require(startMs > 0 && endMs > startMs) }
    val minutes: Long get() = (endMs-startMs)/60_000L
}

/** Validates a complete set of timed breaks before persisting a transaction. */
fun validateBreaks(startMs: Long, endMs: Long, breaks: List<WorkBreak>) {
    require(breaks.size <= 64) { "Слишком много перерывов (максимум 64)." }
    var previousEnd = startMs
    for (item in breaks.sortedBy { it.startMs }) {
        require(item.startMs >= startMs && item.endMs <= endMs) {
            "Перерывы должны находиться внутри смены."
        }
        require(item.startMs >= previousEnd) { "Перерывы не должны пересекаться." }
        previousEnd = item.endMs
    }
    val unpaid = breaks.filterNot { it.paid }.sumOf { it.endMs-it.startMs }
    require(unpaid < endMs-startMs) { "Неоплачиваемые перерывы не могут занимать всю смену." }
}

/** Actual work is stored independently from the planned shifts, including across midnight. */
data class WorkSession(
    val id: Long,
    val date: LocalDate,
    val startedMs: Long,
    val endedMs: Long? = null,
    val pauseStartedMs: Long? = null,
    val totalPausedMs: Long = 0,
    val hourlyKopecks: Long = 35_000L,
    val workplaceId: Long = 1,
    val breaks: List<WorkBreak> = emptyList()
) {
    init {
        require(id >= 0 && workplaceId > 0)
        require(startedMs > 0)
        require(endedMs == null || endedMs >= startedMs)
        require(pauseStartedMs == null || (endedMs == null && pauseStartedMs >= startedMs))
        require(totalPausedMs >= 0)
        require(hourlyKopecks in 0..10_000_000L)
        if (endedMs != null) validateBreaks(startedMs, endedMs, breaks)
    }

    val isRunning: Boolean get() = endedMs == null
    val isPaused: Boolean get() = pauseStartedMs != null

    fun workedMinutes(atMs: Long): Long {
        val end = endedMs ?: maxOf(atMs, startedMs)
        val elapsed = (end - startedMs).coerceAtLeast(0)
        val openPause = pauseStartedMs?.let { (end - it).coerceAtLeast(0) } ?: 0
        return ((elapsed - totalPausedMs - openPause).coerceAtLeast(0)) / 60_000
    }

    fun earnedKopecks(atMs: Long): Long = workedMinutes(atMs) * hourlyKopecks / 60
}

enum class PaymentType { ADVANCE, SALARY, BONUS, DEDUCTION }
data class WagePayment(
    val id: Long,
    val date: LocalDate,
    val amountKopecks: Long,
    val type: PaymentType,
    val workplaceId: Long = 1
) {
    init { require(id >= 0 && workplaceId > 0 && amountKopecks in 1..100_000_000_00L) }
}

/**
 * Night hours are 22:00–06:00 device-local time. Pauses from v0.2 are stored as a
 * total without individual timestamps: night minutes are therefore prorated
 * against total worked minutes. Display the bonus as an estimate, not payroll.
 */
/**
 * Exact night overlap for intervalized unpaid breaks. Older sessions without
 * interval details keep their old proportional estimation as a fallback.
 * Night interval is 22:00–06:00 in the device-local zone.
 */
fun estimatedNightMinutes(session: WorkSession, nowMs: Long, zone: ZoneId): Long {
    val end = session.endedMs ?: maxOf(nowMs, session.startedMs)
    if (end <= session.startedMs) return 0L
    fun nightOverlap(startMs: Long, finishMs: Long): Long {
        if (finishMs <= startMs) return 0L
        val firstDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate().minusDays(1)
        val lastDate = Instant.ofEpochMilli(finishMs).atZone(zone).toLocalDate()
        var day = firstDate
        var total = 0L
        while (!day.isAfter(lastDate)) {
            val nightStart = day.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
            val nightEnd = day.plusDays(1).atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
            total += (min(finishMs, nightEnd)-max(startMs, nightStart)).coerceAtLeast(0)
            day = day.plusDays(1)
        }
        return total
    }
    val elapsed = end-session.startedMs
    val rawNight = nightOverlap(session.startedMs,end)
    val knownUnpaid = session.breaks.filterNot { it.paid }
    val knownUnpaidMs = knownUnpaid.sumOf { it.endMs-it.startMs }
    val exactNightPause = knownUnpaid.sumOf { nightOverlap(it.startMs,it.endMs) }
    val unitemizedPause = (session.totalPausedMs-knownUnpaidMs).coerceAtLeast(0) +
        (session.pauseStartedMs?.let { (end-it).coerceAtLeast(0) } ?: 0L)
    val freeNight = (rawNight-exactNightPause).coerceAtLeast(0)
    val freeElapsed = (elapsed-knownUnpaidMs).coerceAtLeast(1)
    val remainingNight = (freeNight-
        freeNight*unitemizedPause.coerceAtMost(freeElapsed)/freeElapsed).coerceAtLeast(0)
    return min(session.workedMinutes(nowMs),remainingNight/60_000L)
}

data class ActualMonthSummary(
    val completedSessions: Int,
    val minutes: Long,
    val earnedKopecks: Long,
    val paidKopecks: Long,
    val activeSessions: Int,
    val baseKopecks: Long = earnedKopecks,
    val nightBonusKopecks: Long = 0,
    val overtimeBonusKopecks: Long = 0,
    val manualBonusesKopecks: Long = 0,
    val deductionsKopecks: Long = 0,
    val nightMinutes: Long = 0,
    val overtimeMinutes: Long = 0
) {
    val outstandingKopecks: Long get() = earnedKopecks - paidKopecks
}

/** Earnings and payments are attributed to their shift/payment date, not split at midnight. */
fun actualMonthSummary(
    sessions: List<WorkSession>,
    payments: List<WagePayment>,
    month: YearMonth,
    nowMs: Long,
    workplace: Workplace? = null,
    zone: ZoneId = ZoneId.systemDefault()
): ActualMonthSummary {
    val selected = sessions.filter { YearMonth.from(it.date) == month }
    val received = payments.filter { YearMonth.from(it.date) == month }
    val base = selected.sumOf { it.earnedKopecks(nowMs) }
    val nightMins = if (workplace != null && workplace.nightBonusPercent > 0)
        selected.sumOf { estimatedNightMinutes(it, nowMs, zone) } else 0L
    val nightExtra = if (workplace == null) 0L else
        selected.sumOf { s ->
            estimatedNightMinutes(s, nowMs, zone) * s.hourlyKopecks *
                workplace.nightBonusPercent / 6000L
        }
    val overtimeMins = if (workplace != null && workplace.overtimeBonusPercent > 0)
        selected.sumOf { (it.workedMinutes(nowMs) - workplace.overtimeAfterMinutes).coerceAtLeast(0) }
    else 0L
    val overtimeExtra = if (workplace == null) 0L else selected.sumOf { s ->
        (s.workedMinutes(nowMs) - workplace.overtimeAfterMinutes).coerceAtLeast(0) *
            s.hourlyKopecks * workplace.overtimeBonusPercent / 6000L
    }
    val bonuses = received.filter { it.type == PaymentType.BONUS }.sumOf { it.amountKopecks }
    val deductions = received.filter { it.type == PaymentType.DEDUCTION }.sumOf { it.amountKopecks }
    val paid = received.filter { it.type == PaymentType.ADVANCE || it.type == PaymentType.SALARY }
        .sumOf { it.amountKopecks }
    return ActualMonthSummary(
        completedSessions = selected.count { !it.isRunning },
        minutes = selected.sumOf { it.workedMinutes(nowMs) },
        earnedKopecks = base + nightExtra + overtimeExtra + bonuses - deductions,
        paidKopecks = paid,
        activeSessions = selected.count { it.isRunning },
        baseKopecks = base, nightBonusKopecks = nightExtra,
        overtimeBonusKopecks = overtimeExtra, manualBonusesKopecks = bonuses,
        deductionsKopecks = deductions, nightMinutes = nightMins,
        overtimeMinutes = overtimeMins
    )
}
