package ru.smennik.app.domain

import org.junit.Assert.*
import org.junit.Test

class ShiftTimeFormatTest {
    @Test fun pickerOutputHasLeadingZerosAndParses() {
        assertEquals("08:05",displayTime(8*60+5))
        assertEquals(8*60+5,parseTime(displayTime(8*60+5)))
        assertEquals(23*60+59,parseTime(displayTime(23*60+59)))
        assertNull(parseTime("24:00"))
    }
    @Test fun equalEndsMeanFullDayAndEarlierEndMeansOvernight() {
        val day=java.time.LocalDate.of(2026,10,18)
        val full=WorkShift(day,DayType.WORK,8*60,8*60)
        val overnight=WorkShift(day,DayType.WORK,20*60,8*60)
        assertEquals(1440,full.durationMinutes)
        assertEquals(720,overnight.durationMinutes)
        assertEquals(day.plusDays(1),overnight.endsAt.toLocalDate())
    }
}
