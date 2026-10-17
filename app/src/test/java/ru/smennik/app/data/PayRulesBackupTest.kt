package ru.smennik.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.smennik.app.domain.*
import java.security.MessageDigest
import java.time.LocalDate

class PayRulesBackupTest {
    private val date = LocalDate.of(2026,10,18)
    private val workplaces = listOf(
        Workplace(1,"Основная работа",nightBonusPercent=30,overtimeBonusPercent=75,
            overtimeAfterMinutes=600),
        Workplace(3,"Подработка",45_000L,nightBonusPercent=15)
    )

    @Test fun v5BackupPreservesRulesAndPaymentTypes() {
        val shifts = listOf(WorkShift(date,DayType.WORK,workplaceId=3))
        val money = listOf(WagePayment(1,date,500_000,PaymentType.BONUS,3))
        val restored=ShiftBackup.decodeSnapshot(ShiftBackup.encode(shifts,
            emptyList(),money,workplaces))
        assertEquals(workplaces,restored.workplaces)
        assertEquals(PaymentType.BONUS,restored.payments.single().type)
        assertEquals(3L,restored.payments.single().workplaceId)
    }

    @Test fun v4BackupWithNoRulesRestoresDefaults() {
        val envelope = JSONObject(String(ShiftBackup.encode(
            emptyList(),emptyList(),emptyList(),workplaces)))
        val old = JSONObject(envelope.getString("payload"))
        old.put("version",4)
        val jobs = old.getJSONArray("workplaces")
        for(i in 0 until jobs.length()) {
            jobs.getJSONObject(i).remove("nightBonusPercent")
            jobs.getJSONObject(i).remove("overtimeBonusPercent")
            jobs.getJSONObject(i).remove("overtimeAfterMinutes")
        }
        val data=old.toString()
        envelope.put("payload",data)
        envelope.put("sha256",MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
            .joinToString("") { "%02x".format(it) })
        val restored=ShiftBackup.decodeSnapshot(envelope.toString().toByteArray())
        assertEquals(0,restored.workplaces[0].nightBonusPercent)
        assertEquals(0,restored.workplaces[0].overtimeBonusPercent)
        assertEquals(480,restored.workplaces[0].overtimeAfterMinutes)
        assertEquals(45_000L,restored.workplaces[1].hourlyKopecks)
    }
}
