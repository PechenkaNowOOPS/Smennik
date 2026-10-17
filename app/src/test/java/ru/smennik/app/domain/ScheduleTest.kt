package ru.smennik.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class ScheduleTest {
    @Test fun overnightShiftSpansMidnight() {
        val shift = WorkShift(LocalDate.of(2026, 10, 17), DayType.WORK, 20 * 60, 8 * 60)
        assertEquals(720, shift.durationMinutes)
        assertEquals(420_000L, shift.projectedKopecks)
    }

    @Test fun twoTwoPatternIsStable() {
        val dates = WorkPattern(2, 2).generatedWorkDays(LocalDate.of(2026, 1, 1), 8)
        assertEquals(listOf(1, 2, 5, 6), dates.map { it.dayOfMonth })
    }

    @Test fun monthlySummaryIgnoresNextMonthAndRest() {
        val days = listOf(
            WorkShift(LocalDate.of(2026, 10, 31), DayType.WORK),
            WorkShift(LocalDate.of(2026, 11, 1), DayType.WORK),
            WorkShift(LocalDate.of(2026, 10, 30), DayType.OFF)
        )
        assertEquals(MonthSummary(1, 12, 420_000), monthlySummary(days, YearMonth.of(2026, 10)))
    }

    @Test fun twentyFourHourShift() {
        val shift = WorkShift(LocalDate.of(2026, 10, 17), DayType.WORK, 480, 480)
        assertEquals(1440, shift.durationMinutes)
        assertEquals(LocalDateTime.of(2026, 10, 18, 8, 0), shift.endsAt)
        assertEquals(840_000L, shift.projectedKopecks)
    }

    @Test fun overnightShiftIsActiveOnFollowingDay() {
        val night = WorkShift(LocalDate.of(2026, 10, 17), DayType.WORK, 1200, 480)
        val rest = WorkShift(LocalDate.of(2026, 10, 18), DayType.OFF)
        assertEquals(night, activeShiftAt(listOf(night, rest), LocalDateTime.of(2026, 10, 18, 6, 30)))
        assertNull(activeShiftAt(listOf(night, rest), LocalDateTime.of(2026, 10, 18, 8, 0)))
    }

    @Test fun fiveTwoIsMondayThroughFridayEvenWithThursdayAnchor() {
        val days = WorkPattern(5, 2).generatedWorkDays(LocalDate.of(2026, 10, 15), 7)
        assertEquals(listOf(15, 16, 19, 20, 21), days.map { it.dayOfMonth })
    }

    @Test fun monthlySummaryPreservesPartialHours() {
        val d = LocalDate.of(2026, 10, 17)
        val shifts = listOf(WorkShift(d, DayType.WORK, 480, 510),
            WorkShift(d.plusDays(1), DayType.WORK, 480, 525))
        val summary = monthlySummary(shifts, YearMonth.of(2026, 10))
        assertEquals(1, summary.hours)
        assertEquals(15, summary.remainderMinutes)
    }

    @Test fun invalidClockInputRejected() {
        assertNull(parseTime("24:00"))
        assertNull(parseTime("08:60"))
        assertNull(parseTime("hello"))
        assertEquals(8 * 60 + 30, parseTime("08:30"))
    }
}
