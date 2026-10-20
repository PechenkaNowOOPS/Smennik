package ru.smennik.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.smennik.app.domain.ShiftMove
import ru.smennik.app.domain.BulkPlan
import ru.smennik.app.domain.Person
import ru.smennik.app.domain.Workplace
import ru.smennik.app.domain.CustomCycle
import ru.smennik.app.domain.WorkSession
import ru.smennik.app.domain.WorkBreak
import ru.smennik.app.domain.validateBreaks
import ru.smennik.app.domain.WagePayment
import ru.smennik.app.domain.PaymentType
import ru.smennik.app.domain.DayType
import ru.smennik.app.domain.ShiftOrigin
import ru.smennik.app.domain.WorkPattern
import ru.smennik.app.domain.WorkShift
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

/** Additive migrations: existing shifts and manual overrides are never erased. */
class ShiftStore(context: Context) : SQLiteOpenHelper(context, "smennik.db", null, 8) {
    private fun createPeople(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS persons (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL
        )""")
        db.execSQL("INSERT OR IGNORE INTO persons(id,name) VALUES (1,'Я')")
    }

    fun persons(): List<Person> {
        val result=mutableListOf<Person>()
        readableDatabase.query("persons",arrayOf("id","name"),null,null,
            null,null,"id ASC").use { c ->
            while (c.moveToNext()) result += Person(c.getLong(0),c.getString(1))
        }
        return result
    }

    private fun ensurePerson(db: SQLiteDatabase, personId: Long) {
        db.query("persons",arrayOf("id"),"id=?",arrayOf(personId.toString()),
            null,null,null).use { check(it.moveToFirst()) { "Человек не найден." } }
    }

    /** Create a separate default workplace atomically with every new person. */
    fun addPerson(name: String): Long {
        val checked=name.trim()
        require(checked.isNotBlank() && checked.length<=60)
        val db=writableDatabase
        db.beginTransaction()
        try {
            val count=db.rawQuery("SELECT COUNT(*) FROM persons",null).use { c ->
                c.moveToFirst(); c.getInt(0)
            }
            require(count < 50) { "Можно добавить не больше 50 человек." }
            val id=db.insertOrThrow("persons",null,ContentValues().apply { put("name",checked) })
            db.insertOrThrow("workplaces",null,ContentValues().apply {
                put("name","Основная работа")
                put("hourly_kopecks",35_000L)
                put("person_id",id)
            })
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    fun renamePerson(id: Long, name: String) {
        val checked=name.trim()
        require(checked.isNotBlank() && checked.length<=60)
        check(writableDatabase.update("persons",ContentValues().apply {
            put("name",checked)
        },"id=?",arrayOf(id.toString()))==1) { "Человек не найден." }
    }

    private fun createWorkplaces(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS workplaces (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,
            hourly_kopecks INTEGER NOT NULL,
            night_bonus_percent INTEGER NOT NULL DEFAULT 0,
            overtime_bonus_percent INTEGER NOT NULL DEFAULT 0,
            overtime_after_minutes INTEGER NOT NULL DEFAULT 480,
            monthly_goal_kopecks INTEGER NOT NULL DEFAULT 0,
            person_id INTEGER NOT NULL DEFAULT 1
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

    private fun createBreaksTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS session_breaks (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            session_id INTEGER NOT NULL,
            started_ms INTEGER NOT NULL,
            ended_ms INTEGER NOT NULL,
            paid INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_breaks_session ON session_breaks(session_id)")
    }

    private fun insertBreaks(db: SQLiteDatabase, sessionId: Long, breaks: List<WorkBreak>) {
        breaks.forEach { pause ->
            db.insertOrThrow("session_breaks", null, ContentValues().apply {
                put("session_id",sessionId)
                put("started_ms",pause.startMs)
                put("ended_ms",pause.endMs)
                put("paid",if (pause.paid) 1 else 0)
            })
        }
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
        createPeople(db)
        createWorkplaces(db)
        db.execSQL("ALTER TABLE work_sessions ADD COLUMN workplace_id INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE wage_payments ADD COLUMN workplace_id INTEGER NOT NULL DEFAULT 1")
        createBreaksTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..7 && newVersion == 8) {
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
        } else if (oldVersion < 5) {
            db.execSQL("ALTER TABLE workplaces ADD COLUMN night_bonus_percent INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE workplaces ADD COLUMN overtime_bonus_percent INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE workplaces ADD COLUMN overtime_after_minutes INTEGER NOT NULL DEFAULT 480")
        }
        if (oldVersion in 4..5) {
            db.execSQL("ALTER TABLE workplaces ADD COLUMN monthly_goal_kopecks INTEGER NOT NULL DEFAULT 0")
        }
        createBreaksTable(db)
        createPeople(db)
        // On v1-v3, createWorkplaces() already created person_id.
        if (oldVersion >= 4)
            db.execSQL("ALTER TABLE workplaces ADD COLUMN person_id INTEGER NOT NULL DEFAULT 1")
    }

    fun workplaces(): List<Workplace> {
        val result = mutableListOf<Workplace>()
        readableDatabase.query("workplaces", arrayOf("id", "name", "hourly_kopecks",
            "night_bonus_percent", "overtime_bonus_percent", "overtime_after_minutes",
            "monthly_goal_kopecks","person_id"),
            null,null,null,null,"id ASC").use { c ->
            while (c.moveToNext()) result += Workplace(
                c.getLong(0), c.getString(1), c.getLong(2),
                c.getInt(3), c.getInt(4), c.getInt(5), c.getLong(6),c.getLong(7)
            )
        }
        return result
    }

    fun addWorkplace(name: String, rate: Long, personId: Long = 1L) {
        val checked = name.trim()
        require(checked.isNotEmpty() && checked.length <= 60) { "Название: от 1 до 60 символов." }
        require(rate in 0..10_000_000) { "Недопустимая ставка." }
        ensurePerson(writableDatabase,personId)
        writableDatabase.insertOrThrow("workplaces",null,ContentValues().apply {
            put("name",checked)
            put("hourly_kopecks",rate)
            put("person_id",personId)
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

    /** The monthly target is per workplace; zero means no target configured. */
    fun updateMonthlyGoal(workplaceId: Long, goalKopecks: Long) {
        require(goalKopecks in 0..100_000_000_00L) { "Цель должна быть от 0 до 100 млн ₽." }
        val values = ContentValues().apply { put("monthly_goal_kopecks", goalKopecks) }
        check(writableDatabase.update("workplaces",values,"id=?",
            arrayOf(workplaceId.toString())) == 1) { "Место работы не найдено." }
    }

    private fun ensureWorkplace(db: SQLiteDatabase, workplaceId: Long) {
        db.rawQuery("SELECT id FROM workplaces WHERE id=?",arrayOf(workplaceId.toString())).use {
            require(it.moveToFirst()) { "Выбранное место работы не найдено." }
        }
    }

    /** Correct finished sessions only. Their row IDs remain stable for backups and history. */
    fun editFinishedSession(id: Long, startMs: Long, endMs: Long, breakMinutes: Int) {
        require(startMs > 0 && endMs > startMs)
        require(endMs - startMs <= 48L * 60 * 60 * 1000) { "Смена не может длиться более 48 часов." }
        require(breakMinutes in 0..2880 && breakMinutes.toLong() * 60_000 < endMs - startMs) {
            "Перерывы должны быть короче смены."
        }
        val shiftDate = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val values = ContentValues().apply {
            put("shift_date", shiftDate.toString())
            put("started_ms", startMs)
            put("ended_ms", endMs)
            putNull("pause_started_ms")
            put("total_paused_ms", breakMinutes.toLong() * 60_000)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(db.update("work_sessions", values,
                "id = ? AND ended_ms IS NOT NULL", arrayOf(id.toString())) == 1) {
                "Завершённая смена не найдена."
            }
            // Old editor edits a total duration, so obsolete itemized breaks must be removed.
            db.delete("session_breaks","session_id=?",arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * Add a historical work session; no live timer or planned calendar row is changed.
     */
    fun addManualSession(
        workplaceId: Long, startMs: Long, endMs: Long, hourlyKopecks: Long,
        breaks: List<WorkBreak>, legacyMinutes: Int = 0
    ) {
        require(startMs > 0 && endMs > startMs && endMs-startMs <= 48L*60*60*1000)
        require(hourlyKopecks in 0..10_000_000L)
        validateBreaks(startMs,endMs,breaks)
        require(legacyMinutes in 0..2880)
        val unpaid = breaks.filterNot { it.paid }.sumOf { it.endMs-it.startMs }
        require(unpaid + legacyMinutes*60_000L < endMs-startMs)
        val db = writableDatabase
        db.beginTransaction()
        try {
            ensureWorkplace(db,workplaceId)
            val id = db.insertOrThrow("work_sessions",null,ContentValues().apply {
                put("workplace_id",workplaceId)
                put("shift_date",Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault())
                    .toLocalDate().toString())
                put("started_ms",startMs)
                put("ended_ms",endMs)
                putNull("pause_started_ms")
                put("total_paused_ms",unpaid+legacyMinutes*60_000L)
                put("hourly_kopecks",hourlyKopecks)
            })
            insertBreaks(db,id,breaks)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun editFinishedSessionDetailed(
        id: Long, startMs: Long, endMs: Long, hourlyKopecks: Long,
        breaks: List<WorkBreak>, legacyMinutes: Int = 0
    ) {
        require(startMs > 0 && endMs > startMs && endMs-startMs <= 48L*60*60*1000)
        require(hourlyKopecks in 0..10_000_000L)
        validateBreaks(startMs,endMs,breaks)
        require(legacyMinutes in 0..2880)
        val unpaid = breaks.filterNot { it.paid }.sumOf { it.endMs-it.startMs }
        require(unpaid + legacyMinutes*60_000L < endMs-startMs)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("shift_date",Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault())
                    .toLocalDate().toString())
                put("started_ms",startMs)
                put("ended_ms",endMs)
                putNull("pause_started_ms")
                put("total_paused_ms",unpaid+legacyMinutes*60_000L)
                put("hourly_kopecks",hourlyKopecks)
            }
            check(db.update("work_sessions",values,"id=? AND ended_ms IS NOT NULL",
                arrayOf(id.toString()))==1) { "Завершённая смена не найдена." }
            db.delete("session_breaks","session_id=?",arrayOf(id.toString()))
            insertBreaks(db,id,breaks)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun deleteFinishedSession(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(db.delete("work_sessions",
                "id = ? AND ended_ms IS NOT NULL", arrayOf(id.toString())) == 1) {
                "Завершённая смена не найдена."
            }
            db.delete("session_breaks","session_id=?",arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
        if (result.isEmpty()) return result
        val grouped = mutableMapOf<Long,MutableList<WorkBreak>>()
        readableDatabase.query("session_breaks",
            arrayOf("session_id","started_ms","ended_ms","paid"),
            null,null,null,null,"started_ms ASC").use { c ->
            while (c.moveToNext()) {
                grouped.getOrPut(c.getLong(0)) { mutableListOf() }.add(
                    WorkBreak(c.getLong(1),c.getLong(2),c.getInt(3)!=0)
                )
            }
        }
        return result.map { session ->
            session.copy(breaks=grouped[session.id].orEmpty())
        }
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

    /** At most one live work timer per PERSON. Two different people can work simultaneously. */
    fun startSession(date: LocalDate, rate: Long, nowMs: Long, workplaceId: Long = 1) {
        require(nowMs > 0 && rate in 0..10_000_000L)
        val db = writableDatabase
        db.beginTransaction()
        try {
            ensureWorkplace(db,workplaceId)
            db.rawQuery("""SELECT COUNT(*) FROM work_sessions s
                JOIN workplaces w ON w.id=s.workplace_id
                WHERE s.ended_ms IS NULL AND w.person_id=(
                    SELECT person_id FROM workplaces WHERE id=?
                )""", arrayOf(workplaceId.toString())).use {
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
                if (nowMs > item.pauseStartedMs)
                    insertBreaks(db,id,listOf(WorkBreak(item.pauseStartedMs,nowMs)))
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
            if (item.pauseStartedMs != null && nowMs > item.pauseStartedMs)
                insertBreaks(db,id,listOf(WorkBreak(item.pauseStartedMs,nowMs)))
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
            db.delete("session_breaks", null, null)
            db.delete("work_sessions", null, null)
            db.delete("wage_payments", null, null)
            db.delete("workplaces", null, null)
            db.delete("persons", null, null)
            snapshot.persons.forEach { person ->
                db.insertOrThrow("persons", null,ContentValues().apply {
                    put("id",person.id); put("name",person.name)
                })
            }
            snapshot.workplaces.forEach { job ->
                db.insertOrThrow("workplaces", null, ContentValues().apply {
                    put("id",job.id); put("name",job.name); put("hourly_kopecks",job.hourlyKopecks)
                    put("night_bonus_percent",job.nightBonusPercent)
                    put("overtime_bonus_percent",job.overtimeBonusPercent)
                    put("overtime_after_minutes",job.overtimeAfterMinutes)
                    put("monthly_goal_kopecks",job.monthlyGoalKopecks)
                    put("person_id",job.personId)
                })
            }
            snapshot.shifts.forEach {
                db.insertOrThrow(if (it.workplaceId == 1L) "shifts" else "workplace_shifts",
                    null, values(it))
            }
            snapshot.sessions.forEach { item ->
                val sessionId = db.insertOrThrow("work_sessions", null, ContentValues().apply {
                    put("workplace_id",item.workplaceId)
                    put("shift_date", item.date.toString())
                    put("started_ms", item.startedMs)
                    if (item.endedMs == null) putNull("ended_ms") else put("ended_ms", item.endedMs)
                    if (item.pauseStartedMs == null) putNull("pause_started_ms") else put("pause_started_ms", item.pauseStartedMs)
                    put("total_paused_ms", item.totalPausedMs)
                    put("hourly_kopecks", item.hourlyKopecks)
                })
                insertBreaks(db,sessionId,item.breaks)
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

    /**
     * One SQLite transaction per batch; skip existing dates unless the user explicitly
     * confirms overwriting them. No writes to other workplaces or session/pay records.
     */
    fun applyBulkPlan(plan: BulkPlan): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ensureWorkplace(db, plan.workplaceId)
            val table = if (plan.workplaceId == 1L) "shifts" else "workplace_shifts"
            var changed = 0
            plan.dates.forEach { day ->
                val where = if (plan.workplaceId == 1L) "date = ?" else
                    "date = ? AND workplace_id = ?"
                val args = if (plan.workplaceId == 1L) arrayOf(day.toString()) else
                    arrayOf(day.toString(), plan.workplaceId.toString())
                val existing = db.query(table,arrayOf("date"),where,args,
                    null,null,null).use { it.moveToFirst() }
                if (existing && !plan.replaceExisting) return@forEach
                if (plan.type == null) {
                    if (existing) changed += db.delete(table,where,args)
                } else {
                    val entry = WorkShift(day,plan.type,plan.startMinutes,plan.endMinutes,
                        plan.hourlyKopecks,ShiftOrigin.MANUAL,plan.workplaceId)
                    check(db.insertWithOnConflict(table,null,values(entry),
                        SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                    changed++
                }
            }
            db.setTransactionSuccessful()
            return changed
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Atomic calendar move: delete source dates and place them at offset dates.
     * If any target is occupied and overwrite was not approved, roll back all rows.
     */
    fun movePlannedShifts(move: ShiftMove): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ensureWorkplace(db, move.workplaceId)
            val originals = all().filter {
                it.workplaceId == move.workplaceId && it.date in move.dates
            }
            require(originals.size == move.dates.size) {
                "Можно переносить только дни, в которых есть записи графика."
            }
            val table = if (move.workplaceId == 1L) "shifts" else "workplace_shifts"
            val where = if (move.workplaceId == 1L) "date=?" else "date=? AND workplace_id=?"
            fun args(date: LocalDate): Array<String> =
                if (move.workplaceId == 1L) arrayOf(date.toString())
                else arrayOf(date.toString(), move.workplaceId.toString())
            originals.forEach { source ->
                check(db.delete(table,where,args(source.date)) == 1)
            }
            originals.forEach { source ->
                val target = source.copy(
                    date = source.date.plusDays(move.offsetDays.toLong()),
                    origin = ShiftOrigin.MANUAL
                )
                val conflict = db.query(table,arrayOf("date"),where,args(target.date),
                    null,null,null).use { it.moveToFirst() }
                if (conflict && !move.replaceDestinations) {
                    error("На ${target.date} уже есть запись. Разрешите замену или выберите другой период.")
                }
                val outcome = db.insertWithOnConflict(table,null,values(target),
                    if (move.replaceDestinations) SQLiteDatabase.CONFLICT_REPLACE else
                        SQLiteDatabase.CONFLICT_ABORT)
                check(outcome != -1L)
            }
            db.setTransactionSuccessful()
            return originals.size
        } finally {
            db.endTransaction()
        }
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
