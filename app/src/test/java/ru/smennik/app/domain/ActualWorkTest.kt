package ru.smennik.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ActualWorkTest {
    private val date = LocalDate.of(2026, 10, 17)
    private val start = 1_800_000_000_000L
    @Test fun runningSessionAccruesWorkAfterRestart() {
        val shift = WorkSession(1, date, start, hourlyKopecks = 36_000)
        assertEquals(60, shift.workedMinutes(start + 60 * 60_000))
        assertEquals(36_000, shift.earnedKopecks(start + 60 * 60_000))
    }
    @Test fun unpaidBreakIsExcludedWhetherRunningOrFinished() {
        val shift = WorkSession(1, date, start, pauseStartedMs = start + 60 * 60_000, hourlyKopecks = 36_000)
        assertEquals(60, shift.workedMinutes(start + 100 * 60_000))
        val finished = WorkSession(1, date, start, endedMs = start + 120 * 60_000,
            totalPausedMs = 30 * 60_000, hourlyKopecks = 36_000)
        assertEquals(90, finished.workedMinutes(start + 999 * 60_000))
        assertEquals(54_000, finished.earnedKopecks(start + 999 * 60_000))
    }
    @Test fun monthlyReportSeparatesAdvanceFromEarned() {
        val finished = WorkSession(1, date, start, endedMs = start + 3 * 60 * 60_000, hourlyKopecks = 40_000)
        val paid = WagePayment(1, date, 30_000, PaymentType.ADVANCE)
        val report = actualMonthSummary(listOf(finished), listOf(paid), YearMonth.of(2026, 10), start + 99900000)
        assertEquals(180L, report.minutes)
        assertEquals(120_000L, report.earnedKopecks)
        assertEquals(90_000L, report.outstandingKopecks)
        assertEquals(1, report.completedSessions)
    }
    @Test fun futureClockIsNotCountedNegative() {
        val x = WorkSession(1, date, start)
        assertEquals(0L, x.workedMinutes(start - 1_000))
    }
}
