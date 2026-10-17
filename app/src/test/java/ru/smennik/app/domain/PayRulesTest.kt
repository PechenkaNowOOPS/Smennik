package ru.smennik.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class PayRulesTest {
    private val zone = ZoneId.of("Europe/Riga")
    private val day = LocalDate.of(2026, 10, 19)
    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day): Long =
        date.atTime(hour,minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun nightShiftEarnsConfiguredPremiumAcrossMidnight() {
        val session = WorkSession(1, day, at(22), at(6,date=day.plusDays(1)),
            hourlyKopecks = 60_000)
        val workplace = Workplace(1,"Работа",60_000,nightBonusPercent=25)
        assertEquals(480L, estimatedNightMinutes(session, at(7,date=day.plusDays(1)),zone))
        val data = actualMonthSummary(listOf(session),emptyList(),
            YearMonth.of(2026,10),at(7,date=day.plusDays(1)),workplace,zone)
        assertEquals(480_000L,data.baseKopecks)
        assertEquals(120_000L,data.nightBonusKopecks)
        assertEquals(600_000L,data.earnedKopecks)
    }

    @Test fun overtimeBonusesAndManualAdjustmentsAreNotPayments() {
        val session = WorkSession(1,day,at(8),at(15),hourlyKopecks=60_000)
        val workplace = Workplace(1,"Работа",60_000,
            overtimeBonusPercent=50,overtimeAfterMinutes=360)
        val money = listOf(
            WagePayment(1,day,10_000,PaymentType.BONUS),
            WagePayment(2,day,5_000,PaymentType.DEDUCTION),
            WagePayment(3,day,100_000,PaymentType.ADVANCE))
        val d = actualMonthSummary(listOf(session),money,YearMonth.of(2026,10),
            at(16),workplace,zone)
        assertEquals(420_000L,d.baseKopecks)
        assertEquals(60L,d.overtimeMinutes)
        assertEquals(30_000L,d.overtimeBonusKopecks)
        assertEquals(10_000L,d.manualBonusesKopecks)
        assertEquals(5_000L,d.deductionsKopecks)
        assertEquals(455_000L,d.earnedKopecks)
        assertEquals(100_000L,d.paidKopecks)
        assertEquals(355_000L,d.outstandingKopecks)
    }

    @Test fun zeroBonusDefaultsPreserveOldCalculation() {
        val session = WorkSession(1,day,at(8),at(20),hourlyKopecks=35_000)
        val d = actualMonthSummary(listOf(session),emptyList(),YearMonth.of(2026,10),
            at(21),Workplace(1,"Основная работа"),zone)
        assertEquals(420_000L,d.earnedKopecks)
        assertEquals(0L,d.nightBonusKopecks)
        assertEquals(0L,d.overtimeBonusKopecks)
    }

    @Test fun pausesAreProratedAndNeverExceedWorkedTime() {
        val s = WorkSession(1,day,at(20),at(4,date=day.plusDays(1)),
            totalPausedMs = 4L * 60 * 60 * 1000)
        val mins=estimatedNightMinutes(s,at(5,date=day.plusDays(1)),zone)
        assertEquals(180L,mins)
        assertTrue(mins <= s.workedMinutes(at(5,date=day.plusDays(1))))
    }
}
