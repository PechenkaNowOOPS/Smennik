package ru.smennik.app.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import ru.smennik.app.MainActivity
import ru.smennik.app.R
import ru.smennik.app.data.ShiftStore
import ru.smennik.app.domain.*
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

/**
 * Two simple RemoteViews widgets, no service or account. Periodic launcher updates
 * plus every successful database mutation keep the display reasonably fresh.
 */
object ShiftWidgetUpdater {
    private val ru = Locale("ru","RU")
    private val format = DateTimeFormatter.ofPattern("dd MMM, EEE",ru)
    private fun cash(v: Long): String = "%,.0f ₽".format(ru,v / 100.0)
    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0,
            Intent(context,MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun updateAll(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val shiftsIds = manager.getAppWidgetIds(ComponentName(app,NextShiftWidget::class.java))
        val earningsIds = manager.getAppWidgetIds(ComponentName(app,MonthlyIncomeWidget::class.java))
        if (shiftsIds.isEmpty() && earningsIds.isEmpty()) return
        ShiftStore(app).use { store ->
            val selectedPerson = app.getSharedPreferences("smennik_selected_person",0)
                .getLong("person_id",1L)
            val workplaces = store.workplaces().filter { it.personId == selectedPerson }
            val jobIds = workplaces.map { it.id }.toSet()
            if (shiftsIds.isNotEmpty()) {
                val shift = nearestPlannedShift(store.all().filter { it.workplaceId in jobIds },LocalDateTime.now())
                val name = workplaces.firstOrNull { it.id == shift?.workplaceId }?.name
                val running = shift != null && !LocalDateTime.now().isBefore(shift.startsAt)
                shiftsIds.forEach { id ->
                    val view=RemoteViews(app.packageName,R.layout.widget_next_shift)
                    view.setTextViewText(R.id.widget_next_title,
                        if (shift == null) "Смены не запланированы"
                        else if (running) "Смена сейчас" else "Следующая смена")
                    view.setTextViewText(R.id.widget_next_date,
                        shift?.date?.format(format)?.replaceFirstChar { it.uppercase(ru) }
                            ?: "Открой Сменник и создай график")
                    view.setTextViewText(R.id.widget_next_time,
                        shift?.let { "${displayTime(it.startMinutes)} — ${displayTime(it.endMinutes)}" }
                            ?: "—")
                    view.setTextViewText(R.id.widget_next_workplace,name ?: "СМЕННИК")
                    view.setOnClickPendingIntent(R.id.widget_next_root,openApp(app))
                    manager.updateAppWidget(id,view)
                }
            }
            if (earningsIds.isNotEmpty()) {
                val now=System.currentTimeMillis()
                val progress=monthlyGoalProgress(workplaces,
                    store.allSessions().filter { it.workplaceId in jobIds },
                    store.allPayments().filter { it.workplaceId in jobIds },
                    YearMonth.now(),now)
                earningsIds.forEach { id ->
                    val view=RemoteViews(app.packageName,R.layout.widget_monthly_income)
                    view.setTextViewText(R.id.widget_income_month,
                        YearMonth.now().format(DateTimeFormatter.ofPattern("LLLL yyyy",ru))
                            .replaceFirstChar { it.uppercase(ru) })
                    view.setTextViewText(R.id.widget_income_amount,cash(progress.actualKopecks))
                    view.setTextViewText(R.id.widget_income_goal,
                        if(progress.hasGoal) "Цель: ${cash(progress.goalKopecks)} · ${progress.progressPercent}%"
                        else "Цель пока не задана")
                    view.setProgressBar(R.id.widget_income_progress,100,
                        min(progress.progressPercent,100),false)
                    view.setOnClickPendingIntent(R.id.widget_income_root,openApp(app))
                    manager.updateAppWidget(id,view)
                }
            }
        }
    }
}

private fun updateInBackground(context: Context, pending: android.content.BroadcastReceiver.PendingResult) {
    val app=context.applicationContext
    Thread {
        try { ShiftWidgetUpdater.updateAll(app) }
        catch (_: Exception) { /* Android will retry at next scheduled widget refresh */ }
        finally { pending.finish() }
    }.start()
}

class NextShiftWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context,m: AppWidgetManager,ids: IntArray) {
        updateInBackground(c,goAsync())
    }
}

class MonthlyIncomeWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context,m: AppWidgetManager,ids: IntArray) {
        updateInBackground(c,goAsync())
    }
}
