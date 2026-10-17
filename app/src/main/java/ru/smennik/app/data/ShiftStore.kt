package ru.smennik.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.smennik.app.domain.WorkSession
import ru.smennik.app.domain.WagePayment
import ru.smennik.app.domain.PaymentType
import ru.smennik.app.domain.DayType
import ru.smennik.app.domain.ShiftOrigin
import ru.smennik.app.domain.WorkPattern
import ru.smennik.app.domain.WorkShift
import java.time.LocalDate

/** Additive migrations: existing shifts and manual overrides are never erased. */
class ShiftStore(context: Context) : SQLiteOpenHelper(context, "smennik.db", null, 3) {
    private fun createActualTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS work_sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            shift_date TEXT NOT NULL,
            started_ms INTEGER NOT NULL,
            ended_ms INTEGER,
            pause_started_ms INTEGER,
            total_paused_ms INTEGER NOT NULL DEFAULT 0,
            hourly_kopecks INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_date ON work_sessions(shift_date)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS wage_payments (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            payment_date TEXT NOT NULL,
            amount_kopecks INTEGER NOT NULL,
            type TEXT NOT NULL
        )""")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE shifts (
            date TEXT PRIMARY KEY NOT NULL,
            type TEXT NOT NULL,
            start_minutes INTEGER NOT NULL,
            end_minutes INTEGER NOT NULL,
            hourly_kopecks INTEGER NOT NULL,
            origin TEXT NOT NULL DEFAULT 'MANUAL'
        )""")
        createActualTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..2 && newVersion == 3) {
            "Нет безопасной миграции SQLite с $oldVersion на $newVersion"
        }
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE shifts ADD COLUMN origin TEXT NOT NULL DEFAULT 'MANUAL'")
        }
        createActualTables(db)
    }

    fun allSessions(): List<WorkSession> {
        val result = mutableListOf<WorkSession>()
        readableDatabase.query(
            "work_sessions",
            arrayOf("id", "shift_date", "started_ms", "ended_ms", "pause_started_ms", "total_paused_ms", "hourly_kopecks"),
            null, null, null, null, "started_ms DESC"
        ).use { c ->
            while (c.moveToNext()) result += WorkSession(
                id = c.getLong(0),
                date = LocalDate.parse(c.getString(1)),
                startedMs = c.getLong(2),
                endedMs = if (c.isNull(3)) null else c.getLong(3),
                pauseStartedMs = if (c.isNull(4)) null else c.getLong(4),
                totalPausedMs = c.getLong(5),
                hourlyKopecks = c.getLong(6)
            )
        }
        return result
    }

    fun allPayments(): List<WagePayment> {
        val result = mutableListOf<WagePayment>()
        readableDatabase.query(
            "wage_payments", arrayOf("id", "payment_date", "amount_kopecks", "type"),
            null, null, null, null, "payment_date DESC, id DESC"
        ).use { c ->
            while (c.moveToNext()) result += WagePayment(
                id = c.getLong(0), date = LocalDate.parse(c.getString(1)),
                amountKopecks = c.getLong(2), type = PaymentType.valueOf(c.getString(3))
            )
        }
        return result
    }

    /** At most one unfinished session anywhere. This is checked inside a DB transaction. */
    fun startSession(date: LocalDate, rate: Long, nowMs: Long) {
        require(nowMs > 0 && rate in 0..10_000_000L)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT COUNT(*) FROM work_sessions WHERE ended_ms IS NULL", null).use {
                check(it.moveToFirst() && it.getInt(0) == 0) { "Сначала завершите текущую смену." }
            }
            db.insertOrThrow("work_sessions", null, ContentValues().apply {
                put("shift_date", date.toString())
                put("started_ms", nowMs)
                put("hourly_kopecks", rate)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun togglePause(id: Long, nowMs: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val item = allSessions().firstOrNull { it.id == id && it.isRunning }
                ?: error("Активная смена не найдена.")
            val cv = ContentValues()
            if (item.pauseStartedMs == null) {
                require(nowMs >= item.startedMs)
                cv.put("pause_started_ms", nowMs)
            } else {
                require(nowMs >= item.pauseStartedMs)
                cv.putNull("pause_started_ms")
                cv.put("total_paused_ms", item.totalPausedMs + nowMs - item.pauseStartedMs)
            }
            check(db.update("work_sessions", cv, "id = ? AND ended_ms IS NULL",
                arrayOf(id.toString())) == 1)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun finishSession(id: Long, nowMs: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val item = allSessions().firstOrNull { it.id == id && it.isRunning }
                ?: error("Активная смена не найдена.")
            require(nowMs >= item.startedMs && (item.pauseStartedMs == null || nowMs >= item.pauseStartedMs))
            val cv = ContentValues().apply {
                put("ended_ms", nowMs)
                putNull("pause_started_ms")
                put("total_paused_ms", item.totalPausedMs +
                    (item.pauseStartedMs?.let { nowMs - it } ?: 0L))
            }
            check(db.update("work_sessions", cv, "id = ? AND ended_ms IS NULL",
                arrayOf(id.toString())) == 1)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun addPayment(date: LocalDate, amount: Long, type: PaymentType) {
        require(amount in 1..100_000_000_00L)
        writableDatabase.insertOrThrow("wage_payments", null, ContentValues().apply {
            put("payment_date", date.toString())
            put("amount_kopecks", amount)
            put("type", type.name)
        })
    }

    fun removePayment(id: Long) {
        check(writableDatabase.delete("wage_payments", "id = ?", arrayOf(id.toString())) == 1) {
            "Выплата не найдена."
        }
    }

    fun replaceAllSnapshot(snapshot: ShiftBackup.Snapshot) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("shifts", null, null)
            db.delete("work_sessions", null, null)
            db.delete("wage_payments", null, null)
            snapshot.shifts.forEach { db.insertOrThrow("shifts", null, values(it)) }
            snapshot.sessions.forEach { item ->
                db.insertOrThrow("work_sessions", null, ContentValues().apply {
                    put("shift_date", item.date.toString())
                    put("started_ms", item.startedMs)
                    if (item.endedMs == null) putNull("ended_ms") else put("ended_ms", item.endedMs)
                    if (item.pauseStartedMs == null) putNull("pause_started_ms") else put("pause_started_ms", item.pauseStartedMs)
                    put("total_paused_ms", item.totalPausedMs)
                    put("hourly_kopecks", item.hourlyKopecks)
                })
            }
            snapshot.payments.forEach { item ->
                db.insertOrThrow("wage_payments", null, ContentValues().apply {
                    put("payment_date", item.date.toString())
                    put("amount_kopecks", item.amountKopecks)
                    put("type", item.type.name)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
