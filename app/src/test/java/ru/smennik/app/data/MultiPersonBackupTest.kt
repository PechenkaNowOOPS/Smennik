package ru.smennik.app.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import ru.smennik.app.domain.*
import java.security.MessageDigest
import java.time.LocalDate

class MultiPersonBackupTest {
    private val date = LocalDate.of(2026,10,20)
    private val people = listOf(Person(1,"Я"),Person(2,"Мария"))
    private val employers = listOf(
        Workplace(1,"Завод",personId=1),
        Workplace(2,"Офис",hourlyKopecks=60_000,personId=2))
    @Test fun v9PreservesPersonsAndIndependentPlannedShifts() {
        val shifts=listOf(
            WorkShift(date,DayType.WORK,480,1020,workplaceId=1),
            WorkShift(date,DayType.WORK,720,1200,workplaceId=2))
        val copy=ShiftBackup.decodeSnapshot(ShiftBackup.encode(
            shifts,emptyList(),emptyList(),employers,people))
        assertEquals(people,copy.persons)
        assertEquals(employers,copy.workplaces)
        assertEquals(shifts.toSet(),copy.shifts.toSet())
    }

    @Test fun v9AllowsConcurrentActiveTimersForDifferentPersons() {
        val started=1_800_000_000_000L
        val sessions=listOf(
            WorkSession(1,date,started,workplaceId=1),
            WorkSession(2,date,started,workplaceId=2))
        val copy=ShiftBackup.decodeSnapshot(ShiftBackup.encode(
            emptyList(),sessions,emptyList(),employers,people))
        assertEquals(2,copy.sessions.count { it.isRunning })
    }

    @Test fun v9RejectsTwoActiveTimersForOnePerson() {
        val jobs=employers+Workplace(3,"Вторая работа",personId=1)
        val started=1_800_000_000_000L
        val sessions=listOf(WorkSession(1,date,started,workplaceId=1),
            WorkSession(2,date,started,workplaceId=3))
        val serialized=ShiftBackup.encode(emptyList(),sessions,emptyList(),jobs,people)
        try { ShiftBackup.decodeSnapshot(serialized);fail("same person double timer") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun v8BackupMigratesRecordsToFirstPerson() {
        val serialized=ShiftBackup.encode(
            listOf(WorkShift(date,DayType.WORK,workplaceId=1)),
            emptyList(),emptyList(),listOf(Workplace(1,"Завод")))
        val wrapper=JSONObject(String(serialized))
        val payload=JSONObject(wrapper.getString("payload"))
        payload.put("version",8)
        payload.remove("persons")
        payload.getJSONArray("workplaces").getJSONObject(0).remove("personId")
        val raw=payload.toString()
        wrapper.put("payload",raw)
        wrapper.put("sha256",MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) })
        val restored=ShiftBackup.decodeSnapshot(wrapper.toString().toByteArray())
        assertEquals(listOf(Person(1,"Я")),restored.persons)
        assertEquals(1L,restored.workplaces.single().personId)
        assertEquals(1,restored.shifts.size)
    }

    @Test fun invalidPersonReferenceFailsExport() {
        try {
            ShiftBackup.encode(emptyList(),emptyList(),emptyList(),
                listOf(Workplace(1,"Работа",personId=3)),people)
            fail("unknown owner must fail")
        } catch (_: IllegalArgumentException) {}
    }
}
