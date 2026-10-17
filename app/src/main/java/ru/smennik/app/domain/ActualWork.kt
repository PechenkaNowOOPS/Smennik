package ru.smennik.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

/** Actual work is stored independently from the planned shifts, including across midnight. */
data class WorkSession(
    val id: Long,
    val date: LocalDate,
    val startedMs: Long,
    val endedMs: Long? = null,
    val pauseStartedMs: Long? = null,
    val totalPausedMs: Long = 0,
    val hourlyKopecks: Long = 35_000L
) {
    init {
        require(id >= 0)
        require(startedMs > 0)
        require(endedMs == null || endedMs >= startedMs)
        require(pauseStartedMs == null || (endedMs == null && pauseStartedMs >= startedMs))
        require(totalPausedMs >= 0)
        require(hourlyKopecks in 0..10_000_000L)
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

enum class PaymentType { ADVANCE, SALARY }
data class WagePayment(
    val id: Long,
    val date: LocalDate,
    val amountKopecks: Long,
    val type: PaymentType
) {
    init { require(id >= 0 && amountKopecks in 1..100_000_000_00L) }
}

/** Earnings and payments are attributed to their start/payment date, not split at midnight. */
data class ActualMonthSummary(
    val completedSessions: Int,
    val minutes: Long,
    val earnedKopecks: Long,
    val paidKopecks: Long,
    val activeSessions: Int
) {
    val outstandingKopecks: Long get() = earnedKopecks - paidKopecks
}

fun actualMonthSummary(
    sessions: List<WorkSession>,
    payments: List<WagePayment>,
    month: YearMonth,
    nowMs: Long
): ActualMonthSummary {
    val selected = sessions.filter { YearMonth.from(it.date) == month }
    val received = payments.filter { YearMonth.from(it.date) == month }
    return ActualMonthSummary(
        completedSessions = selected.count { !it.isRunning },
        minutes = selected.sumOf { it.workedMinutes(nowMs) },
        earnedKopecks = selected.sumOf { it.earnedKopecks(nowMs) },
        paidKopecks = received.sumOf { it.amountKopecks },
        activeSessions = selected.count { it.isRunning }
    )
}
