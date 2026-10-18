package ru.smennik.app.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import ru.smennik.app.domain.*
import java.security.MessageDigest
import java.time.LocalDate

class DetailedBreakBackupTest {
    private val day=LocalDate.of(2026,10,18)
    private val start=1_813_348_800_000L
    @Test fun v8RoundTripKeepsPaidAndUnpaidBreaks() {
        val breaks=listOf(
            WorkBreak(start+60*60*1000,start+90*60*1000,false),
            WorkBreak(start+3*60*60*1000,start+4*60*60*1000,true))
        val session=WorkSession(42,day,start,start+8*60*60*1000,
            totalPausedMs=30*60*1000,hourlyKopecks=35_000,breaks=breaks)
        val restored=ShiftBackup.decodeSnapshot(ShiftBackup.encode(
            emptyList(),listOf(session),emptyList()))
        assertEquals(breaks,restored.sessions.single().breaks)
        assertEquals(30*60*1000L,restored.sessions.single().totalPausedMs)
    }
    @Test fun v7BackupDefaultsToNoTimedBreaks() {
        val session=WorkSession(42,day,start,start+8*60*60*1000,
            totalPausedMs=30*60*1000,hourlyKopecks=35_000)
        val envelope=JSONObject(String(ShiftBackup.encode(
            emptyList(),listOf(session),emptyList())))
        val payload=JSONObject(envelope.getString("payload"))
        payload.put("version",7)
        payload.getJSONArray("sessions").getJSONObject(0).remove("breaks")
        val p=payload.toString()
        envelope.put("payload",p)
        envelope.put("sha256",MessageDigest.getInstance("SHA-256")
            .digest(p.toByteArray()).joinToString("") { "%02x".format(it) })
        val restored=ShiftBackup.decodeSnapshot(envelope.toString().toByteArray())
        assertTrue(restored.sessions.single().breaks.isEmpty())
        assertEquals(session.totalPausedMs,restored.sessions.single().totalPausedMs)
    }
}
