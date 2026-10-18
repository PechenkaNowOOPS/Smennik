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
        val app = context.applicationContext
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!allowed(app)) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        ShiftStore(app).use { store ->
            val active = store.allSessions().firstOrNull { it.isRunning }
            if (active == null) {
                manager.cancel(NOTIFICATION_ID)
                return
            }
            val workplace = store.workplaces().firstOrNull { it.id == active.workplaceId }
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, "Активная рабочая смена",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Управление таймером и перерывами" }
            )
            fun action(action: String, code: Int): PendingIntent =
                PendingIntent.getBroadcast(
                    app, code,
                    Intent(app, WorkdayActionReceiver::class.java)
                        .setAction(action).putExtra("session_id",active.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            val open = PendingIntent.getActivity(
                app, 0,
                Intent(app,MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val start = Instant.ofEpochMilli(active.startedMs).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
            val minutes = active.workedMinutes(System.currentTimeMillis())
            val notification = Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(
                    if (active.isPaused) "Перерыв · ${workplace?.name ?: "Работа"}"
                    else "Рабочая смена · ${workplace?.name ?: "Работа"}"
                )
                .setContentText(
                    "Начало: $start · ${minutes / 60} ч ${minutes % 60} мин · " +
                        "${active.earnedKopecks(System.currentTimeMillis()) / 100} ₽"
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .addAction(
                    android.R.drawable.ic_media_pause,
                    if (active.isPaused) "Продолжить" else "Перерыв",
                    action(PAUSE_ACTION, 701)
                )
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                    "Завершить", action(FINISH_ACTION, 702))
                .build()
            manager.notify(NOTIFICATION_ID, notification)
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
