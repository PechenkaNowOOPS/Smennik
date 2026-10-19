package ru.smennik.app.domain

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ShiftCountdownTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val day = LocalDate.of(2026, 10, 19)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun dayShiftCountsDownToScheduledFinish() {
        val shift = WorkShift(day, DayType.WORK, 8 * 60, 20 * 60)
        val countdown = shiftEndCountdown(shift, at(day, 19, 30), zone)!!
        assertEquals(30 * 60L, countdown.remainingSeconds)
        assertEquals(0L, countdown.overdueSeconds)
        assertEquals(at(day, 20), countdown.endsAtMs)
        assertEquals("00:30:00", formatClockDuration(countdown.remainingSeconds))
    }

    @Test fun nightShiftContinuesAcrossMidnight() {
        val shift = WorkShift(day, DayType.WORK, 20 * 60, 8 * 60)
        val countdown = shiftEndCountdown(shift, at(day.plusDays(1), 7, 45), zone)!!
        assertEquals(15 * 60L, countdown.remainingSeconds)
        assertEquals(at(day.plusDays(1), 8), countdown.endsAtMs)
    }

    @Test fun fullDayShiftEndsTomorrowNotImmediately() {
        val shift = WorkShift(day, DayType.WORK, 8 * 60, 8 * 60)
        val countdown = shiftEndCountdown(shift, at(day, 8), zone)!!
        assertEquals(24 * 3600L, countdown.remainingSeconds)
        assertEquals("24:00:00", formatClockDuration(countdown.remainingSeconds))
    }

    @Test fun finalFractionOfSecondRoundsUpAndExpiryNeverNegative() {
        val shift = WorkShift(day, DayType.WORK, 8 * 60, 20 * 60)
        val end = at(day, 20)
        assertEquals(1L, shiftEndCountdown(shift, end - 1L, zone)!!.remainingSeconds)
        assertEquals(2L, shiftEndCountdown(shift, end - 1001L, zone)!!.remainingSeconds)
        assertEquals(0L, shiftEndCountdown(shift, end, zone)!!.remainingSeconds)
        assertEquals(0L, shiftEndCountdown(shift, end + 1L, zone)!!.overdueSeconds)
        val overtime = shiftEndCountdown(shift, end + 70_000L, zone)!!
        assertTrue(overtime.isFinishedBySchedule)
        assertEquals(0L, overtime.remainingSeconds)
        assertEquals(70L, overtime.overdueSeconds)
    }

    @Test fun selectionAlwaysMatchesExactWorkplaceAndDate() {
        val otherJob = WorkShift(day, DayType.WORK, 8 * 60, 12 * 60, workplaceId = 2)
        val rightJob = WorkShift(day, DayType.WORK, 9 * 60, 18 * 60, workplaceId = 1)
        val previousDay = WorkShift(day.minusDays(1), DayType.WORK, workplaceId = 1)
        val session = WorkSession(1, day, at(day, 9), workplaceId = 1)
        assertEquals(rightJob, plannedShiftForSession(listOf(otherJob,previousDay,rightJob),session))
        assertNull(plannedShiftForSession(listOf(otherJob,previousDay),session))
    }

    @Test fun offDaysDoNotProduceCountdown() {
        val shift = WorkShift(day, DayType.OFF)
        assertNull(shiftEndCountdown(shift, at(day, 10), zone))
    }

    @Test fun worksDuringDstTransitionInLocalTimezone() {
        // Europe/Riga repeats an hour on Oct 25, 2026: planned 08:00 still wins.
        val localZone = ZoneId.of("Europe/Riga")
        val night = WorkShift(LocalDate.of(2026,10,24),DayType.WORK,20*60,8*60)
        val atSeven = LocalDate.of(2026,10,25).atTime(7,0).atZone(localZone)
            .toInstant().toEpochMilli()
        assertEquals(3600L, shiftEndCountdown(night,atSeven,localZone)!!.remainingSeconds)
    }
}
