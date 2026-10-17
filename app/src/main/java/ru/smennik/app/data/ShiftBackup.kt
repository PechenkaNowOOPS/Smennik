package ru.smennik.app.data

import org.json.JSONArray
import org.json.JSONObject
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
    private const val VERSION = 3
    private const val MAX_SHIFTS = 100_000

    data class Snapshot(
        val shifts: List<WorkShift>,
        val sessions: List<WorkSession>,
        val payments: List<WagePayment>
    )

    fun encode(shifts: List<WorkShift>): ByteArray = encode(shifts, emptyList(), emptyList())

    fun encode(shifts: List<WorkShift>, sessions: List<WorkSession>, payments: List<WagePayment>): ByteArray {
        require(shifts.size <= MAX_SHIFTS) { "Слишком много записей для резервной копии." }
        val entries = JSONArray()
        shifts.sortedBy { it.date }.forEach { shift ->
            entries.put(JSONObject()
                .put("date", shift.date.toString())
                .put("type", shift.type.name)
                .put("startMinutes", shift.startMinutes)
                .put("endMinutes", shift.endMinutes)
                .put("hourlyKopecks", shift.hourlyKopecks)
                .put("origin", shift.origin.name))
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
                .put("hourlyKopecks", s.hourlyKopecks))
        }
        val paymentEntries = JSONArray()
        payments.sortedBy { it.date }.forEach { p ->
            paymentEntries.put(JSONObject()
                .put("date", p.date.toString())
                .put("amountKopecks", p.amountKopecks)
                .put("type", p.type.name))
        }
        val payload = JSONObject()
            .put("version", VERSION)
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
        val dates = HashSet<LocalDate>(entries.length())
        val restoredShifts = List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            val date = LocalDate.parse(entry.getString("date"))
            require(dates.add(date)) { "В резервной копии повторяется дата $date." }
            WorkShift(
                date = date,
                type = DayType.valueOf(entry.getString("type")),
                startMinutes = entry.getInt("startMinutes"),
                endMinutes = entry.getInt("endMinutes"),
                hourlyKopecks = entry.getLong("hourlyKopecks"),
                origin = if (fileVersion == 1) ShiftOrigin.MANUAL else
                    ShiftOrigin.valueOf(entry.getString("origin"))
            )
        }
        if (fileVersion < 3) return Snapshot(restoredShifts, emptyList(), emptyList())

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
                hourlyKopecks = x.getLong("hourlyKopecks")
            )
        }
        require(sessions.count { it.isRunning } <= 1) { "В копии несколько активных смен." }
        val payments = List(paymentArray.length()) { index ->
            val x = paymentArray.getJSONObject(index)
            WagePayment(
                id = index.toLong() + 1,
                date = LocalDate.parse(x.getString("date")),
                amountKopecks = x.getLong("amountKopecks"),
                type = PaymentType.valueOf(x.getString("type"))
            )
        }
        return Snapshot(restoredShifts, sessions, payments)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
