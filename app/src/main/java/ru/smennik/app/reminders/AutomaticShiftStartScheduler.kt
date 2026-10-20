package ru.smennik.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import ru.smennik.app.data.ShiftStore
import ru.smennik.app.domain.DayType
import ru.smennik.app.domain.WorkShift
import ru.smennik.app.domain.automaticStartCandidates
import ru.smennik.app.widgets.ShiftWidgetUpdater
import java.time.ZoneId

/**
 * Automatically starts the real work timer when a scheduled shift begins,
 * independent of whether shift reminder notifications are enabled.
 *
 * Alarms may be delayed by Android battery optimizations. On delivery the
 * persisted session is backdated to the exact scheduled start; opening the
 * app or a daily refresh also catches up an ongoing overnight/day shift.
 * No exact-alarm permission or foreground service is required.
 */
object AutomaticShiftStartScheduler {
    const val START_ACTION = "ru.smennik.app.AUTO_START_SHIFT"
    const val REFRESH_ACTION = "ru.smennik.app.AUTO_START_REFRESH"
    private const val PREFS = "smennik_auto_start"
    private const val SCHEDULED = "scheduled_dates"

    private fun intent(context: Context, token: String): Intent =
        Intent(context, ReminderReceiver::class.java)
            .setAction(START_ACTION)
            .setData(Uri.parse("smennik://automatic-start/$token"))

    private fun pending(context: Context, token: String, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, 0, intent(context,token),
            flags or PendingIntent.FLAG_IMMUTABLE)

    fun sync(context: Context, shifts: List<WorkShift>) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Cancel removed / modified calendar alarms before programming new ones.
        prefs.getStringSet(SCHEDULED, emptySet()).orEmpty().forEach { token ->
            pending(app,token,PendingIntent.FLAG_NO_CREATE)?.let(manager::cancel)
        }
        val refresh = PendingIntent.getBroadcast(app, 0,
            Intent(app,ReminderReceiver::class.java).setAction(REFRESH_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.cancel(refresh)
        val now = System.currentTimeMillis()
        manager.setInexactRepeating(AlarmManager.RTC_WAKEUP,
            now + AlarmManager.INTERVAL_DAY,AlarmManager.INTERVAL_DAY,refresh)
        val windowEnd = now + 60L * AlarmManager.INTERVAL_DAY
        val ids = linkedSetOf<String>()
        shifts.asSequence()
            .filter { it.type == DayType.WORK }
            .map { it to it.startsAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .filter { (_, startMs) -> startMs > now && startMs <= windowEnd }
            .sortedWith(compareBy<Pair<WorkShift,Long>> { it.second }
                .thenBy { it.first.workplaceId })
            .take(120)
            .forEach { (shift, startMs) ->
                val token = "${shift.workplaceId}/${shift.date}"
                val pi = pending(app,token,PendingIntent.FLAG_UPDATE_CURRENT)!!
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,startMs,pi)
                ids.add(token)
            }
        prefs.edit().putStringSet(SCHEDULED,ids).apply()
    }

    /** One atomic start per scheduled shift; never restart a manually finished session. */
    fun catchUp(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val app = context.applicationContext
        val zone = ZoneId.systemDefault()
        val didStart = ShiftStore(app).use { store ->
            val candidates = automaticStartCandidates(store.all(),store.allSessions(),nowMs,zone)
            candidates.count { store.startScheduledSessionIfDue(it,nowMs,zone) } > 0
        }
        if (didStart) {
            runCatching { WorkdayNotifications.sync(app) }
            runCatching { ShiftWidgetUpdater.updateAll(app) }
        }
        return didStart
    }
}
