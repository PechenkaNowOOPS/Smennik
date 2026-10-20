package ru.smennik.app.domain

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AutomaticShiftStartTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val day = LocalDate.of(2026, 10, 20)
    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun startsExactlyAtScheduledTimeNotBefore() {
        val shift = WorkShift(day, DayType.WORK, 8 * 60, 20 * 60)
        assertTrue(automaticStartCandidates(listOf(shift), emptyList(), at(day, 7, 59), zone).isEmpty())
        assertEquals(listOf(shift),
            automaticStartCandidates(listOf(shift), emptyList(), at(day, 8), zone))
        assertTrue(automaticStartCandidates(listOf(shift), emptyList(), at(day, 20), zone).isEmpty())
    }

    @Test fun delayedAlarmStartsAtScheduledBeginningWhileShiftStillActive() {
        val shift = WorkShift(day, DayType.WORK, 8 * 60, 20 * 60)
        assertEquals(listOf(shift),
            automaticStartCandidates(listOf(shift), emptyList(), at(day, 10), zone))
    }

    @Test fun overnightShiftRemainsEligibleNextDay() {
        val shift = WorkShift(day, DayType.WORK, 20 * 60, 8 * 60)
        assertEquals(listOf(shift), automaticStartCandidates(
            listOf(shift), emptyList(), at(day.plusDays(1), 7), zone))
        assertTrue(automaticStartCandidates(
            listOf(shift), emptyList(), at(day.plusDays(1), 8), zone).isEmpty())
    }

    @Test fun alreadyFinishedSessionNeverRestarts() {
        val shift = WorkShift(day, DayType.WORK, 8 * 60, 20 * 60)
        val finished = WorkSession(1, day, at(day, 8), at(day, 9))
        assertTrue(automaticStartCandidates(listOf(shift), listOf(finished), at(day, 10), zone).isEmpty())
        assertNull(millisUntilNextAutomaticStart(listOf(shift), listOf(finished), at(day, 10), zone))
    }

    @Test fun ignoresSessionForDifferentWorkplaceAndShowsUpcomingDeadline() {
        val shift = WorkShift(day, DayType.WORK, 8 * 60, 20 * 60, workplaceId = 2)
        val other = WorkSession(1, day, at(day, 8), workplaceId = 1)
        assertEquals(60_000L, millisUntilNextAutomaticStart(
            listOf(shift), listOf(other), at(day, 7, 59), zone))
        assertEquals(0L, millisUntilNextAutomaticStart(
            listOf(shift), listOf(other), at(day, 8), zone))
        assertEquals(listOf(shift), automaticStartCandidates(
            listOf(shift), listOf(other), at(day, 8), zone))
    }

    @Test fun ignoresDaysOffAndShiftsThatHaveEnded() {
        val off = WorkShift(day, DayType.OFF)
        val shift = WorkShift(day.minusDays(1), DayType.WORK, 8 * 60, 20 * 60)
        assertTrue(automaticStartCandidates(listOf(off, shift), emptyList(), at(day, 8), zone).isEmpty())
        assertNull(millisUntilNextAutomaticStart(listOf(off, shift), emptyList(), at(day, 8), zone))
    }

    @Test fun daylightSavingNightWorksAcrossRepeatedClockHour() {
        val riga = ZoneId.of("Europe/Riga")
        val shift = WorkShift(LocalDate.of(2026, 10, 24), DayType.WORK, 20 * 60, 8 * 60)
        val moment = LocalDate.of(2026, 10, 25).atTime(7, 0).atZone(riga)
            .toInstant().toEpochMilli()
        assertEquals(listOf(shift), automaticStartCandidates(
            listOf(shift), emptyList(), moment, riga))
    }
}
