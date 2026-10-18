package ru.smennik.app.data

import org.json.JSONArray
import org.json.JSONObject
import ru.smennik.app.domain.Workplace
import ru.smennik.app.domain.WorkSession
import ru.smennik.app.domain.WagePayment
import ru.smennik.app.domain.PaymentType
import ru.smennik.app.domain.DayType
import ru.smennik.app.domain.ShiftOrigin
import ru.smennik.app.domain.WorkShift
import java.security.MessageDigest
import java.time.LocalDate

/** Versioned JSON backup with a checksum over the exact serialized payload. */
object ShiftBackup {
    const val MAX_BYTES = 5 * 1024 * 1024
    private const val FORMAT = "ru.smennik.backup"
    private const val VERSION = 6
    private const val MAX_SHIFTS = 100_000

    data class Snapshot(
        val shifts: List<WorkShift>,
        val sessions: List<WorkSession>,
        val payments: List<WagePayment>,
        val workplaces: List<Workplace> = listOf(Workplace(1, "Основная работа"))
    )

    fun encode(shifts: List<WorkShift>): ByteArray =
        encode(shifts, emptyList(), emptyList(), listOf(Workplace(1, "Основная работа")))

    fun encode(shifts: List<WorkShift>, sessions: List<WorkSession>, payments: List<WagePayment>): ByteArray =
        encode(shifts,sessions,payments,listOf(Workplace(1,"Основная работа")))

    fun encode(
        shifts: List<WorkShift>, sessions: List<WorkSession>,
        payments: List<WagePayment>, workplaces: List<Workplace>
    ): ByteArray {
        require(workplaces.isNotEmpty() && workplaces.size <= 300)
        require(workplaces.any { it.id == 1L })
        val ids = workplaces.map { it.id }.toSet()
        require(ids.size == workplaces.size)
        require(shifts.all { it.workplaceId in ids } &&
            sessions.all { it.workplaceId in ids } && payments.all { it.workplaceId in ids }) {
            "В резервной копии есть запись без места работы."
        }
        require(shifts.size <= MAX_SHIFTS) { "Слишком много записей для резервной копии." }
        val entries = JSONArray()
        shifts.sortedBy { it.date }.forEach { shift ->
            entries.put(JSONObject()
                .put("date", shift.date.toString())
                .put("type", shift.type.name)
                .put("startMinutes", shift.startMinutes)
                .put("endMinutes", shift.endMinutes)
                .put("hourlyKopecks", shift.hourlyKopecks)
                .put("origin", shift.origin.name)
                .put("workplaceId",shift.workplaceId))
        }
        require(sessions.size <= MAX_SHIFTS && payments.size <= MAX_SHIFTS)
        val sessionEntries = JSONArray()
        sessions.sortedBy { it.startedMs }.forEach { s ->
            sessionEntries.put(JSONObject()
                .put("date", s.date.toString())
                .put("startedMs", s.startedMs)
                .put("endedMs", s.endedMs ?: JSONObject.NULL)
                .put("pauseStartedMs", s.pauseStartedMs ?: JSONObject.NULL)
                .put("totalPausedMs", s.totalPausedMs)
                .put("hourlyKopecks", s.hourlyKopecks)
                .put("workplaceId", s.workplaceId))
        }
        val paymentEntries = JSONArray()
        payments.sortedBy { it.date }.forEach { p ->
            paymentEntries.put(JSONObject()
                .put("date", p.date.toString())
                .put("amountKopecks", p.amountKopecks)
                .put("type", p.type.name)
                .put("workplaceId",p.workplaceId))
        }
        val workplaceEntries = JSONArray()
        workplaces.sortedBy { it.id }.forEach { w ->
            workplaceEntries.put(JSONObject()
                .put("id",w.id).put("name",w.name).put("hourlyKopecks",w.hourlyKopecks)
                .put("nightBonusPercent",w.nightBonusPercent)
                .put("overtimeBonusPercent",w.overtimeBonusPercent)
                .put("overtimeAfterMinutes",w.overtimeAfterMinutes))
        }
        val payload = JSONObject()
            .put("version", VERSION)
            .put("workplaces",workplaceEntries)
            .put("shifts", entries)
            .put("sessions", sessionEntries)
            .put("payments", paymentEntries)
            .toString()
        val envelope = JSONObject()
            .put("format", FORMAT)
            .put("payload", payload)
            .put("sha256", sha256(payload.toByteArray(Charsets.UTF_8)))
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(envelope.size <= MAX_BYTES) { "Резервная копия превышает допустимый размер." }
        return envelope
    }

    fun decode(bytes: ByteArray): List<WorkShift> = decodeSnapshot(bytes).shifts

    fun decodeSnapshot(bytes: ByteArray): Snapshot {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Размер файла резервной копии недопустим." }
        val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
        require(envelope.getString("format") == FORMAT) { "Это не файл резервной копии Сменника." }
        val payload = envelope.getString("payload")
        val expectedHash = envelope.getString("sha256")
        val actualHash = sha256(payload.toByteArray(Charsets.UTF_8))
        require(MessageDigest.isEqual(expectedHash.toByteArray(), actualHash.toByteArray())) {
            "Контрольная сумма не совпадает. Файл повреждён или изменён."
        }

        val content = JSONObject(payload)
        val fileVersion = content.getInt("version")
        require(fileVersion in 1..VERSION) { "Версия резервной копии не поддерживается." }
        val entries = content.getJSONArray("shifts")
        require(entries.length() <= MAX_SHIFTS) { "В резервной копии слишком много записей." }
        val workplaces = if (fileVersion >= 4) {
            val items = content.getJSONArray("workplaces")
            require(items.length() in 1..300) { "Неверное количество мест работы." }
            val result = List(items.length()) { index ->
                val w = items.getJSONObject(index)
                Workplace(w.getLong("id"), w.getString("name"), w.getLong("hourlyKopecks"),
                    nightBonusPercent = if (fileVersion >= 5) w.getInt("nightBonusPercent") else 0,
                    overtimeBonusPercent = if (fileVersion >= 5) w.getInt("overtimeBonusPercent") else 0,
                    overtimeAfterMinutes = if (fileVersion >= 5) w.getInt("overtimeAfterMinutes") else 480)
            }
            require(result.any { it.id == 1L } && result.map { it.id }.toSet().size == result.size)
            result
        } else listOf(Workplace(1,"Основная работа"))
        val dates = HashSet<Pair<Long,LocalDate>>(entries.length())
        val restoredShifts = List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            val date = LocalDate.parse(entry.getString("date"))
            val workplaceId = if (fileVersion >= 4) entry.getLong("workplaceId") else 1L
            require(workplaces.any { it.id == workplaceId }) { "Неизвестное место работы." }
            require(dates.add(workplaceId to date)) { "В резервной копии повторяется дата $date." }
            WorkShift(
                date = date,
                type = DayType.valueOf(entry.getString("type")),
                startMinutes = entry.getInt("startMinutes"),
                endMinutes = entry.getInt("endMinutes"),
                hourlyKopecks = entry.getLong("hourlyKopecks"),
                origin = if (fileVersion == 1) ShiftOrigin.MANUAL else
                    ShiftOrigin.valueOf(entry.getString("origin")),
                workplaceId = workplaceId
            )
        }
        if (fileVersion < 3) return Snapshot(restoredShifts, emptyList(), emptyList(), workplaces)

        val sessionArray = content.getJSONArray("sessions")
        val paymentArray = content.getJSONArray("payments")
        require(sessionArray.length() <= MAX_SHIFTS && paymentArray.length() <= MAX_SHIFTS) {
            "Слишком много записей в резервной копии."
        }
        val sessions = List(sessionArray.length()) { index ->
            val x = sessionArray.getJSONObject(index)
            WorkSession(
                id = index.toLong() + 1,
                date = LocalDate.parse(x.getString("date")),
                startedMs = x.getLong("startedMs"),
                endedMs = if (x.isNull("endedMs")) null else x.getLong("endedMs"),
                pauseStartedMs = if (x.isNull("pauseStartedMs")) null else x.getLong("pauseStartedMs"),
                totalPausedMs = x.getLong("totalPausedMs"),
                hourlyKopecks = x.getLong("hourlyKopecks"),
                workplaceId = if (fileVersion >= 4) x.getLong("workplaceId") else 1L
            )
        }
        require(sessions.count { it.isRunning } <= 1) { "В копии несколько активных смен." }
        val payments = List(paymentArray.length()) { index ->
            val x = paymentArray.getJSONObject(index)
            WagePayment(
                id = index.toLong() + 1,
                date = LocalDate.parse(x.getString("date")),
                amountKopecks = x.getLong("amountKopecks"),
                type = PaymentType.valueOf(x.getString("type")),
                workplaceId = if (fileVersion >= 4) x.getLong("workplaceId") else 1L
            )
        }
        require(sessions.all { it.workplaceId in workplaces.map(Workplace::id) } &&
            payments.all { it.workplaceId in workplaces.map(Workplace::id) })
        return Snapshot(restoredShifts, sessions, payments, workplaces)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
