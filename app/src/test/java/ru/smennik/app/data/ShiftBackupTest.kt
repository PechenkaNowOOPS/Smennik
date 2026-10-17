package ru.smennik.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.smennik.app.domain.DayType
import ru.smennik.app.domain.ShiftOrigin
import ru.smennik.app.domain.WorkShift
import java.security.MessageDigest
import java.time.LocalDate

class ShiftBackupTest {
    private val day = LocalDate.of(2026, 10, 17)

    @Test fun newBackupsPreserveManualAndGeneratedOrigins() {
        val shifts = listOf(WorkShift(day, DayType.WORK),
            WorkShift(day.plusDays(1), DayType.OFF, origin = ShiftOrigin.GENERATED))
        assertEquals(shifts, ShiftBackup.decode(ShiftBackup.encode(shifts)))
    }

    @Test fun oldBackupsCanStillBeRestored() {
        val payload = JSONObject().put("version", 1).put("shifts", JSONArray()
            .put(JSONObject().put("date", day.toString()).put("type", "WORK")
                .put("startMinutes", 480).put("endMinutes", 1200)
                .put("hourlyKopecks", 35000))).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val envelope = JSONObject().put("format", "ru.smennik.backup")
            .put("payload", payload).put("sha256", digest)
        val shifts = ShiftBackup.decode(envelope.toString().toByteArray())
        assertEquals(1, shifts.size)
        assertEquals(ShiftOrigin.MANUAL, shifts.single().origin)
    }

    @Test fun tamperedBackupsAreRejected() {
        val obj = JSONObject(String(ShiftBackup.encode(listOf(WorkShift(day, DayType.WORK)))))
        obj.put("sha256", "0".repeat(64))
        try {
            ShiftBackup.decode(obj.toString().toByteArray())
            fail("Checksum should have been rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Контрольная сумма"))
        }
    }

    @Test fun duplicateDatesAreRejected() {
        val original = JSONObject(String(ShiftBackup.encode(listOf(WorkShift(day, DayType.WORK)))))
        val payload = JSONObject(original.getString("payload"))
        payload.getJSONArray("shifts").put(payload.getJSONArray("shifts").getJSONObject(0))
        val updated = payload.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(updated.toByteArray())
            .joinToString("") { "%02x".format(it) }
        original.put("payload", updated).put("sha256", digest)
        try {
            ShiftBackup.decode(original.toString().toByteArray())
            fail("Duplicate dates should have been rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("повторяется"))
        }
    }
}
