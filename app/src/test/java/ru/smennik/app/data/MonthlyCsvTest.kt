package ru.smennik.app.data

import org.junit.Assert.*
import org.junit.Test
import ru.smennik.app.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class MonthlyCsvTest {
    private val day = LocalDate.of(2026,10,18)
    private val start = day.atTime(8,0).atZone(ZoneId.of("Europe/Riga")).toInstant().toEpochMilli()

    @Test fun exportsSelectedMonthAndSalaryComponents() {
        val workplace = Workplace(3, "Подработка", 45_000L)
        val session = WorkSession(15,day,start,start + 8*60*60*1000L,
            hourlyKopecks = 45_000, workplaceId = 3)
        val otherMonth = WorkSession(16,day.plusMonths(1),start + 40L*24*60*60*1000,
            start + 40L*24*60*60*1000 + 60*60*1000,
            hourlyKopecks = 45_000, workplaceId = 3)
        val payments = listOf(WagePayment(1,day,40_000,PaymentType.ADVANCE,3))
        val csv = MonthlyCsv.generate(workplace,YearMonth.of(2026,10),
            listOf(session,otherMonth),payments,start + 60L*60*60*1000,
            ZoneId.of("Europe/Riga"))
        assertTrue(csv.contains("Подработка"))
        assertTrue(csv.contains("3600,00"))
        assertTrue(csv.contains("400,00"))
        assertTrue(csv.contains("3200,00"))
        assertTrue(csv.contains("2026-10-18"))
        assertFalse(csv.contains("2026-11-18"))
    }

    @Test fun spreadsheetFormulaCannotExecuteFromWorkplaceName() {
        val csv=MonthlyCsv.generate(Workplace(1,"=cmd()"),
            YearMonth.of(2026,10),emptyList(),emptyList(),start)
        assertTrue(csv.contains("'=cmd()"))
        assertFalse(csv.contains(";"+ "\"=cmd()\""))
    }
}
