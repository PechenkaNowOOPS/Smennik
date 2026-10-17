package ru.smennik.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.smennik.app.domain.Workplace
import ru.smennik.app.domain.CustomCycle
import ru.smennik.app.domain.WorkSession
import ru.smennik.app.domain.WagePayment
import ru.smennik.app.domain.PaymentType
import ru.smennik.app.domain.DayType
import ru.smennik.app.domain.ShiftOrigin
import ru.smennik.app.domain.WorkPattern
import ru.smennik.app.domain.WorkShift
import java.time.LocalDate

/** Additive migrations: existing shifts and manual overrides are never erased. */
class ShiftStore(context: Context) : SQLiteOpenHelper(context, "smennik.db", null, 5) {
    private fun createWorkplaces(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS workplaces (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,
            hourly_kopecks INTEGER NOT NULL,
            night_bonus_percent INTEGER NOT NULL DEFAULT 0,
            overtime_bonus_percent INTEGER NOT NULL DEFAULT 0,
            overtime_after_minutes INTEGER NOT NULL DEFAULT 480
        )""")
        db.execSQL("INSERT OR IGNORE INTO workplaces(id,name,hourly_kopecks) VALUES (1,'Основная работа',35000)")
        // New jobs are stored separately: avoids rebuilding the legacy 'shifts' table.
        db.execSQL("""CREATE TABLE IF NOT EXISTS workplace_shifts (
            workplace_id INTEGER NOT NULL,
            date TEXT NOT NULL,
            type TEXT NOT NULL,
            start_minutes INTEGER NOT NULL,
            end_minutes INTEGER NOT NULL,
            hourly_kopecks INTEGER NOT NULL,
            origin TEXT NOT NULL,
            PRIMARY KEY(workplace_id,date)
        )""")
    }

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
        createWorkplaces(db)
        db.execSQL("ALTER TABLE work_sessions ADD COLUMN workplace_id INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE wage_payments ADD COLUMN workplace_id INTEGER NOT NULL DEFAULT 1")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..4 && newVersion == 5) {
            "Нет безопасной миграции SQLite с $oldVersion на $newVersion"
        }
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE shifts ADD COLUMN origin TEXT NOT NULL DEFAULT 'MANUAL'")
        }
        if (oldVersion < 3) createActualTables(db)
        if (oldVersion < 4) {
            createWorkplaces(db)
            db.execSQL("ALTER TABLE work_sessions ADD COLUMN workplace_id INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE wage_payments ADD COLUMN workplace_id INTEGER NOT NULL DEFAULT 1")
        } else {
            db.execSQL("ALTER TABLE workplaces ADD COLUMN night_bonus_percent INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE workplaces ADD COLUMN overtime_bonus_percent INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE workplaces ADD COLUMN overtime_after_minutes INTEGER NOT NULL DEFAULT 480")
        }
    }

    fun workplaces(): List<Workplace> {
        val result = mutableListOf<Workplace>()
        readableDatabase.query("workplaces", arrayOf("id", "name", "hourly_kopecks",
            "night_bonus_percent", "overtime_bonus_percent", "overtime_after_minutes"),
            null,null,null,null,"id ASC").use { c ->
            while (c.moveToNext()) result += Workplace(
                c.getLong(0), c.getString(1), c.getLong(2),
                c.getInt(3), c.getInt(4), c.getInt(5)
            )
        }
        return result
    }

    fun addWorkplace(name: String, rate: Long) {
        val checked = name.trim()
        require(checked.isNotEmpty() && checked.length <= 60) { "Название: от 1 до 60 символов." }
        require(rate in 0..10_000_000) { "Недопустимая ставка." }
        writableDatabase.insertOrThrow("workplaces",null,ContentValues().apply {
            put("name",checked)
            put("hourly_kopecks",rate)
        })
    }

    fun updatePayRules(id: Long, nightPercent: Int, overtimePercent: Int, afterMinutes: Int) {
        require(nightPercent in 0..200 && overtimePercent in 0..200)
        require(afterMinutes in 60..1440)
        val values = ContentValues().apply {
            put("night_bonus_percent", nightPercent)
            put("overtime_bonus_percent", overtimePercent)
            put("overtime_after_minutes", afterMinutes)
        }
        check(writableDatabase.update("workplaces", values, "id=?", arrayOf(id.toString())) == 1) {
            "Место работы не найдено."
        }
    }

    private fun ensureWorkplace(db: SQLiteDatabase, workplaceId: Long) {
        db.rawQuery("SELECT id FROM workplaces WHERE id=?",arrayOf(workplaceId.toString())).use {
            require(it.moveToFirst()) { "Выбранное место работы не найдено." }
        }
    }

    fun allSessions(): List<WorkSession> {
        val result = mutableListOf<WorkSession>()
        readableDatabase.query(
            "work_sessions",
            arrayOf("id", "shift_date", "started_ms", "ended_ms", "pause_started_ms", "total_paused_ms", "hourly_kopecks", "workplace_id"),
            null, null, null, null, "started_ms DESC"
        ).use { c ->
            while (c.moveToNext()) result += WorkSession(
                id = c.getLong(0),
                date = LocalDate.parse(c.getString(1)),
                startedMs = c.getLong(2),
                endedMs = if (c.isNull(3)) null else c.getLong(3),
                pauseStartedMs = if (c.isNull(4)) null else c.getLong(4),
                totalPausedMs = c.getLong(5),
                hourlyKopecks = c.getLong(6),
                workplaceId = c.getLong(7)
            )
        }
        return result
    }

    fun allPayments(): List<WagePayment> {
        val result = mutableListOf<WagePayment>()
        readableDatabase.query(
            "wage_payments", arrayOf("id", "payment_date", "amount_kopecks", "type", "workplace_id"),
            null, null, null, null, "payment_date DESC, id DESC"
        ).use { c ->
            while (c.moveToNext()) result += WagePayment(
                id = c.getLong(0), date = LocalDate.parse(c.getString(1)),
                amountKopecks = c.getLong(2), type = PaymentType.valueOf(c.getString(3)),
                workplaceId = c.getLong(4)
            )
        }
        return result
    }

    /** At most one unfinished session anywhere. This is checked inside a DB transaction. */
    fun startSession(date: LocalDate, rate: Long, nowMs: Long, workplaceId: Long = 1) {
        require(nowMs > 0 && rate in 0..10_000_000L)
        val db = writableDatabase
        db.beginTransaction()
        try {
            ensureWorkplace(db,workplaceId)
            db.rawQuery("SELECT COUNT(*) FROM work_sessions WHERE ended_ms IS NULL", null).use {
                check(it.moveToFirst() && it.getInt(0) == 0) { "Сначала завершите текущую смену." }
            }
            db.insertOrThrow("work_sessions", null, ContentValues().apply {
                put("shift_date", date.toString())
                put("workplace_id", workplaceId)
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

    fun addPayment(date: LocalDate, amount: Long, type: PaymentType, workplaceId: Long = 1) {
        require(amount in 1..100_000_000_00L)
        ensureWorkplace(writableDatabase,workplaceId)
        writableDatabase.insertOrThrow("wage_payments", null, ContentValues().apply {
            put("workplace_id", workplaceId)
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
            db.delete("workplace_shifts", null, null)
            db.delete("work_sessions", null, null)
            db.delete("wage_payments", null, null)
            db.delete("workplaces", null, null)
            snapshot.workplaces.forEach { job ->
                db.insertOrThrow("workplaces", null, ContentValues().apply {
                    put("id",job.id); put("name",job.name); put("hourly_kopecks",job.hourlyKopecks)
                    put("night_bonus_percent",job.nightBonusPercent)
                    put("overtime_bonus_percent",job.overtimeBonusPercent)
                    put("overtime_after_minutes",job.overtimeAfterMinutes)
                })
            }
            snapshot.shifts.forEach {
                db.insertOrThrow(if (it.workplaceId == 1L) "shifts" else "workplace_shifts",
                    null, values(it))
            }
            snapshot.sessions.forEach { item ->
                db.insertOrThrow("work_sessions", null, ContentValues().apply {
                    put("workplace_id",item.workplaceId)
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
                    put("workplace_id",item.workplaceId)
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
        fun read(table: String, workplace: Boolean) {
            val columns = if (workplace) arrayOf("date","type","start_minutes","end_minutes",
                "hourly_kopecks","origin","workplace_id") else
                arrayOf("date","type","start_minutes","end_minutes","hourly_kopecks","origin")
            readableDatabase.query(table,columns,null,null,null,null,"date ASC").use { c ->
                while (c.moveToNext()) result += WorkShift(
                    date = LocalDate.parse(c.getString(0)),
                    type = DayType.valueOf(c.getString(1)),
                    startMinutes = c.getInt(2),
                    endMinutes = c.getInt(3),
                    hourlyKopecks = c.getLong(4),
                    origin = ShiftOrigin.valueOf(c.getString(5)),
                    workplaceId = if (workplace) c.getLong(6) else 1
                )
            }
        }
        read("shifts",false)
        read("workplace_shifts",true)
        return result.sortedWith(compareBy<WorkShift> { it.date }.thenBy { it.workplaceId })
    }

    private fun values(shift: WorkShift) = ContentValues().apply {
        put("date", shift.date.toString())
        put("type", shift.type.name)
        put("start_minutes", shift.startMinutes)
        put("end_minutes", shift.endMinutes)
        put("hourly_kopecks", shift.hourlyKopecks)
        put("origin", shift.origin.name)
        if (shift.workplaceId != 1L) put("workplace_id",shift.workplaceId)
    }

    fun save(shift: WorkShift) {
        val db = writableDatabase
        ensureWorkplace(db,shift.workplaceId)
        val table = if (shift.workplaceId == 1L) "shifts" else "workplace_shifts"
        val id = db.insertWithOnConflict(table,null,
            values(shift.copy(origin=ShiftOrigin.MANUAL)), SQLiteDatabase.CONFLICT_REPLACE)
        check(id != -1L) { "Не удалось сохранить смену" }
    }

    fun remove(date: LocalDate, workplaceId: Long = 1) {
        if (workplaceId == 1L) writableDatabase.delete("shifts","date=?",arrayOf(date.toString()))
        else writableDatabase.delete("workplace_shifts","date=? AND workplace_id=?",
            arrayOf(date.toString(),workplaceId.toString()))
    }

    /** Import is all-or-nothing, preserving backed up origins (v1 backups default to manual). */
    fun replaceAll(shifts: List<WorkShift>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("shifts",null,null)
            db.delete("workplace_shifts",null,null)
            shifts.forEach { item ->
                db.insertOrThrow(if (item.workplaceId==1L) "shifts" else "workplace_shifts",
                    null,values(item))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * Ordinary regeneration replaces ONLY generated days within 90 days.
     * Explicit replacement removes all records in that range, including manual edits.
     * Every cycle day is recorded (WORK or OFF), to avoid ghost shifts.
     */
    fun fillPattern(
        anchor: LocalDate, pattern: WorkPattern,
        start: Int, end: Int, rate: Long,
        replaceExisting: Boolean = false, workplaceId: Long = 1
    ) = fillWithRule(anchor, start, end, rate, replaceExisting, workplaceId) {
        pattern.isWorkDay(anchor,it)
    }

    fun fillCustomPattern(
        anchor: LocalDate, cycle: CustomCycle,
        start: Int, end: Int, rate: Long,
        replaceExisting: Boolean = false, workplaceId: Long = 1
    ) = fillWithRule(anchor, start, end, rate, replaceExisting, workplaceId) {
        cycle.isWorkDay(anchor,it)
    }

    /** Regeneration changes only generated dates at the chosen job; manual entries remain. */
    private fun fillWithRule(
        anchor: LocalDate, start: Int, end: Int, rate: Long, replaceExisting: Boolean,
        workplaceId: Long, workDay: (LocalDate) -> Boolean
    ) {
        require(workplaceId > 0 && start in 0..1439 && end in 0..1439 && rate in 0..10_000_000)
        val db = writableDatabase
        val last = anchor.plusDays(89)
        db.beginTransaction()
        try {
            ensureWorkplace(db,workplaceId)
            val table = if (workplaceId==1L) "shifts" else "workplace_shifts"
            val args = if (workplaceId==1L) arrayOf(anchor.toString(),last.toString())
                       else arrayOf(anchor.toString(),last.toString(),workplaceId.toString())
            val scope = if (workplaceId==1L) "date>=? AND date<=?"
                        else "date>=? AND date<=? AND workplace_id=?"
            val where = if (replaceExisting) scope else "$scope AND origin='GENERATED'"
            db.delete(table,where,args)
            for(i in 0 until 90) {
                val day = anchor.plusDays(i.toLong())
                val kind = if(workDay(day)) DayType.WORK else DayType.OFF
                db.insertWithOnConflict(table,null,
                    values(WorkShift(day,kind,start,end,rate,ShiftOrigin.GENERATED,workplaceId)),
                    SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

}
