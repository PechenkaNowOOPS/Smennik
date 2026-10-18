package ru.smennik.app.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import ru.smennik.app.domain.Workplace
import java.security.MessageDigest

class GoalBackupTest {
    @Test fun newBackupRetainsConfiguredGoal() {
        val jobs=listOf(Workplace(1,"Основная работа",monthlyGoalKopecks=6_000_000L))
        val restored=ShiftBackup.decodeSnapshot(ShiftBackup.encode(
            emptyList(),emptyList(),emptyList(),jobs))
        assertEquals(6_000_000L,restored.workplaces.single().monthlyGoalKopecks)
    }
    @Test fun olderBackupRestoresGoalAsZero() {
        val jobs=listOf(Workplace(1,"Основная работа",monthlyGoalKopecks=6_000_000L))
        val root=JSONObject(String(ShiftBackup.encode(
            emptyList(),emptyList(),emptyList(),jobs)))
        val payload=JSONObject(root.getString("payload"))
        payload.put("version",6)
        payload.getJSONArray("workplaces").getJSONObject(0).remove("monthlyGoalKopecks")
        val data=payload.toString()
        root.put("payload",data)
        root.put("sha256",MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
            .joinToString("") { "%02x".format(it) })
        val restored=ShiftBackup.decodeSnapshot(root.toString().toByteArray())
        assertEquals(0L,restored.workplaces.single().monthlyGoalKopecks)
    }
}
