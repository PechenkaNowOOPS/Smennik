package ru.smennik.app.reminders

import android.Manifest
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
import ru.smennik.app.widgets.ShiftWidgetUpdater
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Actionable notification for an active work shift. This is not a foreground
 * service: the timer itself is stored in SQLite and survives process death.
 * Actions run in a receiver with goAsync and verify the current session ID.
 */
object WorkdayNotifications {
    private const val CHANNEL = "smennik_active_workday"
    private const val NOTIFICATION_ID = 7101
    internal const val PAUSE_ACTION = "ru.smennik.app.action.TOGGLE_BREAK"
    internal const val FINISH_ACTION = "ru.smennik.app.action.FINISH_WORK"

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    fun sync(context: Context) {
        val app=context.applicationContext
        val manager=app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val prefs=app.getSharedPreferences("workday_notification_ids",Context.MODE_PRIVATE)
        val past=prefs.getStringSet("ids",emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }
        val visible=if (allowed(app)) {
            ShiftStore(app).use { store ->
                val people=store.persons().associateBy { it.id }
                val jobs=store.workplaces().associateBy { it.id }
                store.allSessions().filter { it.isRunning }.map { session ->
                    Triple(session,jobs[session.workplaceId],
                        people[jobs[session.workplaceId]?.personId]?.name ?: "Я")
                }
            }
        } else emptyList()
        manager.cancel(NOTIFICATION_ID) // Remove legacy single-session notification.
        val ids=visible.map { it.first.id.toInt() }.toSet()
        for (old in past) if (old !in ids) manager.cancel("workday",old)
        prefs.edit().putStringSet("ids",ids.map { it.toString() }.toSet()).apply()
        if(visible.isEmpty()) return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL,"Активные рабочие смены",NotificationManager.IMPORTANCE_LOW
        ).apply {description="Управление временем работы каждого человека"})
        val open=PendingIntent.getActivity(
            app,0,
            Intent(app,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        for ((active,workplace,person) in visible) {
            fun action(action: String, suffix: Int): PendingIntent =
                PendingIntent.getBroadcast(
                    app,active.id.toInt()*4+suffix,
                    Intent(app,WorkdayActionReceiver::class.java)
                        .setAction(action)
                        .setData(android.net.Uri.parse("smennik://workday/${active.id}/$suffix"))
                        .putExtra("session_id",active.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            val start=Instant.ofEpochMilli(active.startedMs).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
            val minutes=active.workedMinutes(System.currentTimeMillis())
            val title="${person} · ${workplace?.name ?: "Работа"}"
            val notification=Notification.Builder(app,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(if(active.isPaused) "Перерыв · $title" else "Смена · $title")
                .setContentText(
                    "Начало: $start · ${minutes/60} ч ${minutes%60} мин · " +
                        "${active.earnedKopecks(System.currentTimeMillis())/100} ₽"
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_media_pause,
                    if(active.isPaused) "Продолжить" else "Перерыв",
                    action(PAUSE_ACTION,1))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                    "Завершить",action(FINISH_ACTION,2))
                .build()
            manager.notify("workday",active.id.toInt(),notification)
        }
    }
}

/** Short, atomic DB operation; never mutates an old/foreign session. */
class WorkdayActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                val id = intent.getLongExtra("session_id", -1L)
                val action = intent.action
                if (id <= 0 || action !in listOf(
                    WorkdayNotifications.PAUSE_ACTION,WorkdayNotifications.FINISH_ACTION
                )) return@Thread
                ShiftStore(app).use { store ->
                    val session = store.allSessions().firstOrNull { it.id == id && it.isRunning }
                        ?: return@use
                    val now = System.currentTimeMillis()
                    if (action == WorkdayNotifications.PAUSE_ACTION)
                        store.togglePause(session.id,now)
                    else store.finishSession(session.id,now)
                }
                ShiftWidgetUpdater.updateAll(app)
            } catch (_: Exception) {
                // State is unchanged if validation or the transaction fails.
            } finally {
                runCatching { WorkdayNotifications.sync(app) }
                pending.finish()
            }
        }.start()
    }
}
