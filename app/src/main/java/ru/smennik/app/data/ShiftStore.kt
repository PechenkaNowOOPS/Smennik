package ru.smennik.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.smennik.app.domain.DayType
import ru.smennik.app.domain.WorkPattern
import ru.smennik.app.domain.WorkShift
import java.time.LocalDate

/** Локальный SQLite-слой. При переходе на Room потребуется явная миграция схемы. */
class ShiftStore(context: Context) : SQLiteOpenHelper(context, "smennik.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE shifts (
            date TEXT PRIMARY KEY NOT NULL,
            type TEXT NOT NULL,
            start_minutes INTEGER NOT NULL,
            end_minutes INTEGER NOT NULL,
            hourly_kopecks INTEGER NOT NULL
        )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Версия 1 не имеет предыдущих схем. Не удалять пользовательские данные.
        throw IllegalStateException("Нет миграции базы с $oldVersion на $newVersion")
    }

    fun all(): List<WorkShift> {
        val result = mutableListOf<WorkShift>()
        readableDatabase.query(
            "shifts", arrayOf("date", "type", "start_minutes", "end_minutes", "hourly_kopecks"),
            null, null, null, null, "date ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += WorkShift(
                    date = LocalDate.parse(cursor.getString(0)),
                    type = DayType.valueOf(cursor.getString(1)),
                    startMinutes = cursor.getInt(2),
                    endMinutes = cursor.getInt(3),
                    hourlyKopecks = cursor.getLong(4)
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
    }

    fun save(shift: WorkShift) {
        writableDatabase.insertWithOnConflict("shifts", null, values(shift), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun remove(date: LocalDate) {
        writableDatabase.delete("shifts", "date = ?", arrayOf(date.toString()))
    }

    /** Не заменять отредактированные пользователем дни. Даже ручной выходной защищён от генератора. */
    fun fillPattern(anchor: LocalDate, pattern: WorkPattern, start: Int, end: Int, rate: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (date in pattern.generatedWorkDays(anchor)) {
                db.insertWithOnConflict(
                    "shifts", null,
                    values(WorkShift(date, DayType.WORK, start, end, rate)),
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
