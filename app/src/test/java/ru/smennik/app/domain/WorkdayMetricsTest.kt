package ru.smennik.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WorkdayMetricsTest {
    private val day = LocalDate.of(2026,10,18)
    private val start = 1_800_000_000_000L

    @Test fun activeTimerAccruesBySecondButPayUsesWholeMinutes() {
        val session = WorkSession(1,day,start,hourlyKopecks=60_000)
        val stats = calculateWorkdayMetrics(session,start+65_000L)
        assertEquals(65L,stats.paidSeconds)
        assertEquals(1_000L,stats.earnedKopecks)
    }

    @Test fun pauseDoesNotAccrueWorkOrPay() {
        val session = WorkSession(1,day,start,
            pauseStartedMs=start+30*60_000L,hourlyKopecks=60_000)
        val stats = calculateWorkdayMetrics(session,start+50*60_000L)
        assertEquals(30*60L,stats.paidSeconds)
        assertEquals(20*60L,stats.unpaidBreakSeconds)
        assertEquals(30_000L,stats.earnedKopecks)
    }

    @Test fun plannedHoursYieldSafeOvertime() {
        val session = WorkSession(1,day,start,
            endedMs=start+9*60*60_000L,hourlyKopecks=60_000)
        val plan = WorkShift(day,DayType.WORK,8*60,16*60)
        val stats = calculateWorkdayMetrics(session,start+12*60*60_000L,plan)
        assertEquals(60L,stats.overtimeMinutes)
        assertEquals(0L,stats.remainingMinutes)
    }

    @Test fun restartDoesNotResetTime() {
        val session = WorkSession(1,day,start,totalPausedMs=10*60_000L)
        val now = start+80*60_000L
        assertEquals(calculateWorkdayMetrics(session,now),calculateWorkdayMetrics(session,now))
        assertEquals(70*60L,calculateWorkdayMetrics(session,now).paidSeconds)
    }
}
