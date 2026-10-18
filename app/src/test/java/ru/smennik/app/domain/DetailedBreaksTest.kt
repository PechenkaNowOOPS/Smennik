package ru.smennik.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class DetailedBreaksTest {
    private val day = LocalDate.of(2026,10,18)
    private val zone = ZoneId.of("Europe/Riga")
    private fun at(date: LocalDate,hour: Int): Long =
        date.atTime(hour,0).atZone(zone).toInstant().toEpochMilli()

    @Test fun oneHourUnpaidBreakDeductsExactNightTime() {
        val begin=at(day,22)
        val end=at(day.plusDays(1),6)
        val pause=WorkBreak(at(day,23),at(day.plusDays(1),0),false)
        val shift=WorkSession(10,day,begin,end,totalPausedMs=3_600_000,
            hourlyKopecks=60_000,breaks=listOf(pause))
        assertEquals(420L,shift.workedMinutes(end))
        assertEquals(420L,estimatedNightMinutes(shift,end,zone))
        val workplace=Workplace(1,"Работа",60_000,nightBonusPercent=25)
        val earnings=actualMonthSummary(listOf(shift),emptyList(),YearMonth.of(2026,10),
            end,workplace,zone)
        assertEquals(420_000L,earnings.baseKopecks)
        assertEquals(105_000L,earnings.nightBonusKopecks)
    }

    @Test fun paidBreakDoesNotReduceEarningsOrNightWork() {
        val begin=at(day,22)
        val end=at(day.plusDays(1),6)
        val paid=WorkBreak(at(day,23),at(day.plusDays(1),0),true)
        val shift=WorkSession(3,day,begin,end,hourlyKopecks=60_000,breaks=listOf(paid))
        assertEquals(480L,shift.workedMinutes(end))
        assertEquals(480L,estimatedNightMinutes(shift,end,zone))
    }

    @Test fun overlappingAndOutOfBoundsBreaksAreRejected() {
        val begin=at(day,8)
        val end=at(day,20)
        val first=WorkBreak(at(day,10),at(day,11))
        val overlapping=WorkBreak(at(day,10)+30*60_000,at(day,12))
        try {
            validateBreaks(begin,end,listOf(first,overlapping))
            fail("must reject overlap")
        } catch (_: IllegalArgumentException) {}
        try {
            validateBreaks(begin,end,listOf(WorkBreak(at(day,7),at(day,8))))
            fail("must reject outside")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun legacyMinutesPreservePreviousNetHours() {
        val start=at(day,8); val end=at(day,20)
        val older=WorkSession(2,day,start,end,totalPausedMs=60L*60*1000)
        assertEquals(660L,older.workedMinutes(end))
        assertEquals(0,older.breaks.size)
    }
}
