package ru.smennik.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.smennik.app.domain.DayType
import ru.smennik.app.domain.ShiftOrigin
import ru.smennik.app.domain.WorkPattern
import ru.smennik.app.domain.WorkShift
import java.time.LocalDate

/** v1 -> v2 is additive; existing entries are conservatively treated as manual edits. */
class ShiftStore(context: Context) : SQLiteOpenHelper(context, "smennik.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE shifts (
            date TEXT PRIMARY KEY NOT NULL,
            type TEXT NOT NULL,
            start_minutes INTEGER NOT NULL,
            end_minutes INTEGER NOT NULL,
            hourly_kopecks INTEGER NOT NULL,
            origin TEXT NOT NULL DEFAULT 'MANUAL'
        )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE shifts ADD COLUMN origin TEXT NOT NULL DEFAULT 'MANUAL'")
        } else {
            throw IllegalStateException("Нет безопасной миграции SQLite с $oldVersion на $newVersion")
        }
    }

    fun all(): List<WorkShift> {
        val result = mutableListOf<WorkShift>()
        readableDatabase.query(
            "shifts", arrayOf("date", "type", "start_minutes", "end_minutes", "hourly_kopecks", "origin"),
            null, null, null, null, "date ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += WorkShift(
                    date = LocalDate.parse(cursor.getString(0)),
                    type = DayType.valueOf(cursor.getString(1)),
                    startMinutes = cursor.getInt(2),
                    endMinutes = cursor.getInt(3),
                    hourlyKopecks = cursor.getLong(4),
                    origin = ShiftOrigin.valueOf(cursor.getString(5))
                )
            }
        }
        return result
    }

    private fun values(shift: WorkShift) = ContentValues().apply {
        put("date", shift.date.toString())
        put("type", shift.type.name)
        put("start_minutes", shift.startMinutes)
        put("end_minutes", shift.endMinutes)
        put("hourly_kopecks", shift.hourlyKopecks)
        put("origin", shift.origin.name)
    }

    fun save(shift: WorkShift) {
        val id = writableDatabase.insertWithOnConflict(
            "shifts", null, values(shift.copy(origin = ShiftOrigin.MANUAL)), SQLiteDatabase.CONFLICT_REPLACE
        )
        check(id != -1L) { "Не удалось сохранить смену" }
    }

    fun remove(date: LocalDate) {
        writableDatabase.delete("shifts", "date = ?", arrayOf(date.toString()))
    }

    /** Import is all-or-nothing, preserving backed up origins (v1 backups default to manual). */
    fun replaceAll(shifts: List<WorkShift>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("shifts", null, null)
            shifts.forEach { db.insertOrThrow("shifts", null, values(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * Ordinary regeneration replaces ONLY generated days within 90 days.
     * Explicit replacement removes all records in that range, including manual edits.
     * Every cycle day is recorded (WORK or OFF), to avoid ghost shifts.
     */
    fun fillPattern(
        anchor: LocalDate,
        pattern: WorkPattern,
        start: Int,
        end: Int,
        rate: Long,
        replaceExisting: Boolean = false
    ) {
        val db = writableDatabase
        val last = anchor.plusDays(89)
        db.beginTransaction()
        try {
            val where = if (replaceExisting) "date >= ? AND date <= ?" else
                "date >= ? AND date <= ? AND origin = 'GENERATED'"
            db.delete("shifts", where, arrayOf(anchor.toString(), last.toString()))
            for (i in 0 until 90) {
                val date = anchor.plusDays(i.toLong())
                val kind = if (pattern.isWorkDay(anchor, date)) DayType.WORK else DayType.OFF
                db.insertWithOnConflict(
                    "shifts", null,
                    values(WorkShift(date, kind, start, end, rate, ShiftOrigin.GENERATED)),
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
