package ru.smennik.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import ru.smennik.app.MainActivity
import ru.smennik.app.data.ShiftStore
import ru.smennik.app.domain.DayType
import ru.smennik.app.domain.WorkShift
import ru.smennik.app.widgets.ShiftWidgetUpdater
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Local-only shift reminders. We deliberately use inexact alarms:
 * no SCHEDULE_EXACT_ALARM or battery-optimization exemption required.
 */
object ShiftReminders {
    private const val PREFS = "shift_reminder_settings"
    private const val ENABLED = "enabled"
    private const val LEAD = "lead_minutes"
    private const val SCHEDULED = "scheduled_alarm_ids"
    private const val ACTION_FIRE = "ru.smennik.app.REMINDER_FIRE"
    private const val ACTION_REFRESH = "ru.smennik.app.REMINDER_REFRESH"
    private const val CHANNEL = "smennik_upcoming_shifts"
    val availableLeadMinutes = listOf(15, 30, 60, 120)

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun leadMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(LEAD, 60)
            .takeIf { it in availableLeadMinutes } ?: 60

    fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    fun configure(context: Context, enabled: Boolean, minutesBefore: Int) {
        require(minutesBefore in availableLeadMinutes)
        require(!enabled || notificationsAllowed(context)) {
            "Для уведомлений необходимо разрешение Android."
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, enabled)
            .putInt(LEAD, minutesBefore)
            .apply()
        syncFromDatabase(context)
    }

    fun syncFromDatabase(context: Context) {
        val shifts = ShiftStore(context.applicationContext).use { it.all() }
        sync(context, shifts)
    }

    /** Reconcile all upcoming alarms with the latest calendar. Never leave a deleted shift alarm. */
    fun sync(context: Context, shifts: List<WorkShift>) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        prefs.getStringSet(SCHEDULED, emptySet()).orEmpty().forEach { id ->
            id.toIntOrNull()?.let { token ->
                pending(app, token, null, PendingIntent.FLAG_NO_CREATE)?.let(manager::cancel)
            }
        }
        val ids = linkedSetOf<String>()
        val refreshIntent = Intent(app, ReminderReceiver::class.java).setAction(ACTION_REFRESH)
        val refreshPi = PendingIntent.getBroadcast(app, Int.MIN_VALUE, refreshIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.cancel(refreshPi)
        if (enabled(app) && notificationsAllowed(app)) {
            // Daily inexact refresh ensures shifts farther than 60 days become scheduled
            // even if the user never opens the application again.
            manager.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + AlarmManager.INTERVAL_DAY,
                AlarmManager.INTERVAL_DAY, refreshPi)
            val now = System.currentTimeMillis()
            val end = now + 60L * 24 * 60 * 60 * 1000
            val minutes = leadMinutes(app)
            // Explicitly limit scheduled alarms to avoid exhausting alarm quotas.
            val upcoming = shifts.asSequence()
                .filter { it.type == DayType.WORK }
                .map { shift ->
                    val start = shift.startsAt.atZone(ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                    Triple(shift, start - minutes * 60_000L, start)
                }
                .filter { (_, fire, start) -> fire > now && start <= end }
                .sortedBy { it.second }
                .take(120)
            upcoming.forEach { (shift, fire, start) ->
                val token = alarmId(shift.workplaceId, shift.date)
                val intent = Intent(app, ReminderReceiver::class.java)
                    .setAction(ACTION_FIRE)
                    .putExtra("workplace", shift.workplaceId)
                    .putExtra("date", shift.date.toString())
                    .putExtra("expected_start_ms", start)
                val pi = pending(app, token, intent, PendingIntent.FLAG_UPDATE_CURRENT)
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pi!!)
                ids += token.toString()
            }
        }
        prefs.edit().putStringSet(SCHEDULED, ids).apply()
    }

    private fun alarmId(job: Long, date: LocalDate): Int =
        ("smennik:${job}:${date}").hashCode()

    private fun pending(context: Context, token: Int, intent: Intent?, flag: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, token,
            intent ?: Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE),
            flag or PendingIntent.FLAG_IMMUTABLE
        )

    fun notifyIfCurrent(context: Context, workplaceId: Long, date: LocalDate, expectedStart: Long) {
        if (!enabled(context) || !notificationsAllowed(context)) return
        val store = ShiftStore(context.applicationContext)
        val (shift, workplace) = store.use { s ->
            s.all().firstOrNull { it.workplaceId == workplaceId && it.date == date } to
                s.workplaces().firstOrNull { it.id == workplaceId }
        }
        if (shift == null || shift.type != DayType.WORK) return
        val actualStart = shift.startsAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (actualStart != expectedStart) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ближайшие смены", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val whenText = shift.startsAt.format(DateTimeFormatter.ofPattern("dd.MM, HH:mm"))
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Скоро смена · ${workplace?.name ?: "Работа"}")
            .setContentText("Начало: ${whenText}")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        notificationManager.notify(alarmId(workplaceId,date), notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                if (intent.action == "ru.smennik.app.REMINDER_FIRE") {
                    val date = intent.getStringExtra("date")?.let { LocalDate.parse(it) }
                    if (date != null) ShiftReminders.notifyIfCurrent(
                        context,
                        intent.getLongExtra("workplace", 1L),
                        date,
                        intent.getLongExtra("expected_start_ms", 0L)
                    )
                } else if (intent.action in listOf(
                        "ru.smennik.app.REMINDER_REFRESH",
                        Intent.ACTION_BOOT_COMPLETED,
                        Intent.ACTION_TIME_CHANGED,
                        Intent.ACTION_TIMEZONE_CHANGED,
                        Intent.ACTION_MY_PACKAGE_REPLACED
                    )) {
                    ShiftReminders.syncFromDatabase(context)
                    ShiftWidgetUpdater.updateAll(context)
                }
            } catch (_: Exception) {
                // Android will retry scheduling when the app opens or data changes.
            } finally {
                pending.finish()
            }
        }.start()
    }
}
