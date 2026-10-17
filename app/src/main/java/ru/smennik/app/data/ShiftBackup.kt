package ru.smennik.app.data

import org.json.JSONArray
import org.json.JSONObject
import ru.smennik.app.domain.DayType
import ru.smennik.app.domain.WorkShift
import java.security.MessageDigest
import java.time.LocalDate

/** Versioned JSON backup with a checksum over the exact serialized payload. */
object ShiftBackup {
    const val MAX_BYTES = 5 * 1024 * 1024
    private const val FORMAT = "ru.smennik.backup"
    private const val VERSION = 1
    private const val MAX_SHIFTS = 100_000

    fun encode(shifts: List<WorkShift>): ByteArray {
        require(shifts.size <= MAX_SHIFTS) { "Слишком много записей для резервной копии." }
        val entries = JSONArray()
        shifts.sortedBy { it.date }.forEach { shift ->
            entries.put(JSONObject()
                .put("date", shift.date.toString())
                .put("type", shift.type.name)
                .put("startMinutes", shift.startMinutes)
                .put("endMinutes", shift.endMinutes)
                .put("hourlyKopecks", shift.hourlyKopecks))
        }
        val payload = JSONObject()
            .put("version", VERSION)
            .put("shifts", entries)
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

    fun decode(bytes: ByteArray): List<WorkShift> {
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
        require(content.getInt("version") == VERSION) { "Версия резервной копии не поддерживается." }
        val entries = content.getJSONArray("shifts")
        require(entries.length() <= MAX_SHIFTS) { "В резервной копии слишком много записей." }
        val dates = HashSet<LocalDate>(entries.length())
        return List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            val date = LocalDate.parse(entry.getString("date"))
            require(dates.add(date)) { "В резервной копии повторяется дата $date." }
            WorkShift(
                date = date,
                type = DayType.valueOf(entry.getString("type")),
                startMinutes = entry.getInt("startMinutes"),
                endMinutes = entry.getInt("endMinutes"),
                hourlyKopecks = entry.getLong("hourlyKopecks")
            )
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
