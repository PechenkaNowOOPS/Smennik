package ru.smennik.app.domain

import org.junit.Assert.*
import org.junit.Test

class ShiftTimeRangeTest {
    @Test fun dayAndOvernightDuration() {
        assertEquals(720,ShiftTimeRange(480,1200).durationMinutes)
        assertFalse(ShiftTimeRange(480,1200).crossesMidnight)
        assertEquals(720,ShiftTimeRange(1200,480).durationMinutes)
        assertTrue(ShiftTimeRange(1200,480).crossesMidnight)
    }
    @Test fun fullDayAndOneMinuteShifts() {
        assertEquals(1440,ShiftTimeRange(480,480).durationMinutes)
        assertTrue(ShiftTimeRange(480,480).crossesMidnight)
        assertEquals(1,ShiftTimeRange(1439,0).durationMinutes)
    }
    @Test fun pickerMatchesSavedShiftAndPay() {
        for(start in listOf(0,59,480,720,1200,1439)) {
            for(end in listOf(0,59,480,720,1200,1439)) {
                val d=ShiftTimeRange(start,end)
                val shift=WorkShift(java.time.LocalDate.of(2026,10,18),DayType.WORK,
                    start,end,hourlyKopecks=35_000)
                assertEquals(shift.durationMinutes,d.durationMinutes)
                assertEquals(shift.projectedKopecks,d.estimatedKopecks(35_000))
            }
        }
    }
}
