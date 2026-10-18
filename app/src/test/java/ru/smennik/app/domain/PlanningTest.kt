package ru.smennik.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class PlanningTest {
    private val day=LocalDate.of(2026,10,18)
    @Test fun overlappingJobsAreDetected() {
        val first=WorkShift(day,DayType.WORK,15*60,22*60,workplaceId=1)
        val second=WorkShift(day,DayType.WORK,21*60,23*60,workplaceId=2)
        val problems=findShiftConflicts(listOf(first,second))
        assertEquals(1,problems.size)
        assertEquals(60,problems.single().overlapMinutes)
    }
    @Test fun overnightConflictsCrossCalendarDate() {
        val night=WorkShift(day,DayType.WORK,20*60,6*60,workplaceId=1)
        val morning=WorkShift(day.plusDays(1),DayType.WORK,5*60,12*60,workplaceId=3)
        assertEquals(60L,findShiftConflicts(listOf(morning,night)).single().overlapMinutes)
    }
    @Test fun adjacentShiftsAndSameEmployerDoNotConflict() {
        val a=WorkShift(day,DayType.WORK,8*60,12*60,workplaceId=1)
        val b=WorkShift(day,DayType.WORK,12*60,16*60,workplaceId=2)
        val c=WorkShift(day,DayType.WORK,9*60,15*60,workplaceId=1)
        assertTrue(findShiftConflicts(listOf(a,b)).isEmpty())
        assertTrue(findShiftConflicts(listOf(a,c)).isEmpty())
    }
    @Test fun leaveDaysHaveNoWorkHoursOrIncome() {
        for(type in listOf(DayType.OFF,DayType.VACATION,DayType.SICK,DayType.HOLIDAY)) {
            val entry=WorkShift(day,type)
            assertEquals(0,entry.durationMinutes)
            assertEquals(0L,entry.projectedKopecks)
        }
        assertEquals(0,monthlySummary(listOf(WorkShift(day,DayType.VACATION)),
            YearMonth.of(2026,10)).shifts)
    }
    @Test fun shiftMoveMustBeBoundedAndNonzero() {
        val accepted = ShiftMove(2,listOf(day),7)
        assertEquals(day.plusDays(7),accepted.dates.single().plusDays(accepted.offsetDays.toLong()))
        try { ShiftMove(1,listOf(day),0); fail("must not allow no-op") }
        catch (_: IllegalArgumentException) {}
        try { ShiftMove(1,listOf(day),32); fail("must limit shift") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun bulkPlanningValidatesDatesAndEmployer() {
        try { BulkPlan(0,listOf(day),DayType.WORK); fail("should reject employer") }
        catch (_: IllegalArgumentException) {}
        try { BulkPlan(1,listOf(day,day),DayType.WORK); fail("should reject duplicates") }
        catch (_: IllegalArgumentException) {}
        assertEquals(DayType.VACATION,BulkPlan(2,listOf(day),DayType.VACATION).type)
    }
}
