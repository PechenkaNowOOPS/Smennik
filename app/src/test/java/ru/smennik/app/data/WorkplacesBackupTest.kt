package ru.smennik.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.smennik.app.domain.*
import java.security.MessageDigest
import java.time.LocalDate

class WorkplacesBackupTest {
    private val day = LocalDate.of(2026, 10, 18)
    private val jobs = listOf(Workplace(1, "Основная работа"), Workplace(7, "Подработка", 47_000L))

    @Test fun v4BackupKeepsTwoShiftsOnSameDateFromDifferentJobs() {
        val shifts = listOf(
            WorkShift(day,DayType.WORK,workplaceId=1),
            WorkShift(day,DayType.OFF,workplaceId=7)
        )
        val sessions = listOf(WorkSession(13,day,1_800_000_000_000L,
            hourlyKopecks=47_000L,workplaceId=7))
        val payments = listOf(WagePayment(17,day,100_000L,PaymentType.ADVANCE,workplaceId=7))
        val restored = ShiftBackup.decodeSnapshot(ShiftBackup.encode(shifts,sessions,payments,jobs))
        assertEquals(jobs,restored.workplaces)
        assertEquals(shifts,restored.shifts)
        assertEquals(7L,restored.sessions.single().workplaceId)
        assertEquals(7L,restored.payments.single().workplaceId)
    }

    @Test fun v3BackupsImportIntoDefaultJob() {
        val envelope = JSONObject(String(ShiftBackup.encode(
            listOf(WorkShift(day,DayType.WORK)),emptyList(),emptyList(),jobs)))
        val old = JSONObject(envelope.getString("payload"))
        old.put("version",3)
        old.remove("workplaces")
        val rows = old.getJSONArray("shifts")
        for(i in 0 until rows.length()) rows.getJSONObject(i).remove("workplaceId")
        val payload=old.toString()
        envelope.put("payload",payload)
        envelope.put("sha256",MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) })
        val snapshot=ShiftBackup.decodeSnapshot(envelope.toString().toByteArray())
        assertEquals(listOf(Workplace(1,"Основная работа")),snapshot.workplaces)
        assertEquals(1L,snapshot.shifts.single().workplaceId)
    }
}
