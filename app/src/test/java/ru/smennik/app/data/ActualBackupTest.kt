package ru.smennik.app.data

import org.junit.Assert.*
import org.junit.Test
import ru.smennik.app.domain.*
import java.time.LocalDate

class ActualBackupTest {
    @Test fun actualWorkAndPaymentsRoundTrip() {
        val date = LocalDate.of(2026, 10, 17)
        val sessions = listOf(WorkSession(13, date, 1_800_000_000_000L,
            endedMs = 1_800_007_200_000L, totalPausedMs = 600_000L, hourlyKopecks = 35_000L))
        val payments = listOf(WagePayment(8, date, 15_000L, PaymentType.ADVANCE))
        val restored = ShiftBackup.decodeSnapshot(ShiftBackup.encode(
            listOf(WorkShift(date, DayType.WORK)), sessions, payments))
        assertEquals(1, restored.shifts.size)
        assertEquals(sessions.single().workedMinutes(0), restored.sessions.single().workedMinutes(0))
        assertEquals(payments.single().amountKopecks, restored.payments.single().amountKopecks)
    }
}
