package ru.smennik.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class WidgetModelsTest {
    private val day=LocalDate.of(2026,10,18)
    @Test fun nextWidgetIncludesTonightOvernightShift() {
        val night=WorkShift(day,DayType.WORK,20*60,8*60,workplaceId=2)
        val tomorrow=WorkShift(day.plusDays(1),DayType.WORK,10*60,18*60)
        assertEquals(night,nearestPlannedShift(listOf(tomorrow,night),
            day.plusDays(1).atTime(1,0)))
    }
    @Test fun leaveAndSickDaysAreNotSelected() {
        val leave=WorkShift(day,DayType.VACATION)
        val work=WorkShift(day.plusDays(1),DayType.WORK)
        assertEquals(work,nearestPlannedShift(listOf(leave,work),day.atStartOfDay()))
    }
    @Test fun zeroGoalIsDisabledAndPercentClamps() {
        assertFalse(GoalProgress(12300,0).hasGoal)
        assertEquals(0,GoalProgress(12300,0).progressPercent)
        assertEquals(50,GoalProgress(50,100).progressPercent)
        assertEquals(999,GoalProgress(1200,100).progressPercent)
        assertEquals(0L,GoalProgress(120,100).remainingKopecks)
    }
    @Test fun moneyGoalDoesNotCountPaymentAsEarnings() {
        val job=Workplace(1,"Основная работа",monthlyGoalKopecks=100_000)
        val session=WorkSession(1,day,1_800_000_000_000L,
            1_800_003_600_000L,hourlyKopecks=35_000)
        val payment=WagePayment(1,day,20_000,PaymentType.ADVANCE)
        val goal=monthlyGoalProgress(listOf(job),listOf(session),
            listOf(payment),YearMonth.of(2026,10),1_800_003_600_000L)
        assertEquals(35_000L,goal.actualKopecks)
        assertEquals(35,goal.progressPercent)
    }
}
