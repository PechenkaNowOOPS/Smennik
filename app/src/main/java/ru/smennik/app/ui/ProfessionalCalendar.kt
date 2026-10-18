package ru.smennik.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.smennik.app.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

private val bg = Color(0xFF101418)
private val card = Color(0xFF1B2229)
private val accent = Color(0xFFB7F34B)
private val muted = Color(0xFF9AA5AF)
private val softRed = Color(0xFFFF8585)
private val amber = Color(0xFFFFBF69)
private val blue = Color(0xFF7EB8F0)
private val violet = Color(0xFFC39DF4)
private val ru = Locale("ru", "RU")
private val monthTitle = DateTimeFormatter.ofPattern("LLLL yyyy", ru)
private val dayTitle = DateTimeFormatter.ofPattern("EEE, d MMMM", ru)

private fun dayColor(type: DayType?): Color = when(type) {
    DayType.WORK -> accent
    DayType.OFF -> blue
    DayType.VACATION -> amber
    DayType.SICK -> softRed
    DayType.HOLIDAY -> violet
    null -> muted
}
private fun dayLabel(type: DayType?): String = when(type) {
    DayType.WORK -> "Рабочая смена"
    DayType.OFF -> "Выходной"
    DayType.VACATION -> "Отпуск"
    DayType.SICK -> "Больничный"
    DayType.HOLIDAY -> "Праздник"
    null -> "Без записи"
}
private fun moneyLabel(kopecks: Long): String =
    "%,.2f ₽".format(ru, kopecks / 100.0)

private enum class CalendarMode { MONTH, WEEK, DAY }

/**
 * One calendar can show a chosen workplace or every workplace; every edit
 * carries an explicit workplace id, so there is no ambiguous cross-job write.
 */
@Composable
fun ProfessionalCalendarScreen(
    shifts: List<WorkShift>,
    workplaces: List<Workplace>,
    sessions: List<WorkSession>,
    filterWorkplaceId: Long?,
    onEdit: (LocalDate, Long) -> Unit,
    onPattern: (LocalDate) -> Unit,
    onBulk: suspend (BulkPlan) -> Result<Unit>,
    onMove: suspend (ShiftMove) -> Result<Unit>
) {
    var focus by remember { mutableStateOf(LocalDate.now()) }
    var mode by remember { mutableStateOf(CalendarMode.MONTH) }
    var batchMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<LocalDate>()) }
    var showBulk by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val visible = if (filterWorkplaceId == null) shifts
        else shifts.filter { it.workplaceId == filterWorkplaceId }
    val allConflicts = remember(shifts) { findShiftConflicts(shifts) }
    val byDate = remember(visible) { visible.groupBy { it.date } }
    val currentMonth = YearMonth.from(focus)
    val monthSummary = monthlySummary(visible, currentMonth)
    val dates: List<LocalDate> = when (mode) {
        CalendarMode.MONTH -> {
            val first = currentMonth.atDay(1)
            val monday = first.minusDays((first.dayOfWeek.value - 1).toLong())
            (0 until 42).map { monday.plusDays(it.toLong()) }
        }
        CalendarMode.WEEK -> {
            val monday = focus.minusDays((focus.dayOfWeek.value - 1).toLong())
            (0 until 7).map { monday.plusDays(it.toLong()) }
        }
        CalendarMode.DAY -> listOf(focus)
    }
    val dateSet = dates.toSet()
    val conflicts = allConflicts.filter { conflict ->
        (filterWorkplaceId == null ||
            conflict.first.workplaceId == filterWorkplaceId ||
            conflict.second.workplaceId == filterWorkplaceId) &&
        (conflict.first.date in dateSet || conflict.second.date in dateSet ||
            conflict.from.toLocalDate() in dateSet)
    }
    val conflictingDays = conflicts.flatMap {
        listOf(it.first.date,it.second.date,it.from.toLocalDate())
    }.toSet()
    val completedDays = remember(sessions) {
        sessions.filter { !it.isRunning }.map { it.date }.toSet()
    }
    LaunchedEffect(filterWorkplaceId, mode) {
        selected = emptySet()
        batchMode = false
    }
    fun step(direction: Long) {
        focus = when (mode) {
            CalendarMode.MONTH -> focus.plusMonths(direction)
            CalendarMode.WEEK -> focus.plusWeeks(direction)
            CalendarMode.DAY -> focus.plusDays(direction)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Spacer(Modifier.height(5.dp))
        Text("Календарь", fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(if (filterWorkplaceId == null) "Общий график всех мест работы"
            else workplaces.firstOrNull { it.id == filterWorkplaceId }?.name.orEmpty(),
            color = muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                CalendarMode.MONTH to "Месяц",
                CalendarMode.WEEK to "Неделя",
                CalendarMode.DAY to "День"
            ).forEach { (item, text) ->
                FilterChip(
                    selected = mode == item,
                    onClick = { mode = item },
                    label = { Text(text) }
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { step(-1) }) { Text("‹", color = accent, fontSize = 28.sp) }
            Text(
                if (mode == CalendarMode.MONTH) currentMonth.format(monthTitle)
                    .replaceFirstChar { it.uppercase(ru) }
                else if (mode == CalendarMode.WEEK) "Неделя · ${focus.format(dayTitle)}"
                else focus.format(dayTitle),
                Modifier.weight(1f), textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp
            )
            TextButton(onClick = { step(1) }) { Text("›", color = accent, fontSize = 28.sp) }
        }
        if (mode != CalendarMode.DAY) {
            Row {
                listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach {
                    Text(it, Modifier.weight(1f), textAlign = TextAlign.Center,
                        color = muted, fontSize = 12.sp)
                }
            }
            dates.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { day ->
                        val entries = byDate[day].orEmpty()
                        val active = entries.count { it.type == DayType.WORK }
                        val baseColor = if (day in selected) Color(0xFF42552C) else
                            if (day == LocalDate.now()) Color(0xFF293139) else card
                        val highlighted = day in conflictingDays
                        Column(
                            Modifier.weight(1f).height(72.dp)
                                .background(baseColor, RoundedCornerShape(10.dp))
                                .clickable {
                                    if (batchMode) {
                                        selected = if (day in selected) selected - day else selected + day
                                    } else {
                                        focus = day
                                        mode = CalendarMode.DAY
                                    }
                                }.padding(top = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(day.dayOfMonth.toString(),
                                color = if (YearMonth.from(day) != currentMonth &&
                                    mode == CalendarMode.MONTH) muted else Color.White,
                                fontWeight = if (day == LocalDate.now()) FontWeight.Bold
                                    else FontWeight.Normal)
                            if (highlighted) Text("!", color = softRed, fontWeight = FontWeight.Bold)
                            else if (active > 0) Text(if (active > 1) "${active}●" else "●",
                                color = accent, fontSize = 11.sp)
                            else {
                                val leave = entries.firstOrNull()
                                if (leave != null) Text("●", color = dayColor(leave.type), fontSize = 11.sp)
                                else if (day in completedDays) Text("✓", color = accent, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                DayType.WORK to "Смена",
                DayType.VACATION to "Отпуск",
                DayType.SICK to "Больничный",
                DayType.HOLIDAY to "Праздник"
            ).forEach { (kind, title) ->
                Text("● $title", color = dayColor(kind), fontSize = 10.sp)
            }
        }
        if (conflicts.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3B292B))) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("⚠ Найдено пересечений: ${conflicts.size}",
                        color = softRed, fontWeight = FontWeight.Bold)
                    conflicts.take(3).forEach { conflict ->
                        val left = workplaces.find { it.id == conflict.first.workplaceId }?.name
                            ?: "Работа ${conflict.first.workplaceId}"
                        val right = workplaces.find { it.id == conflict.second.workplaceId }?.name
                            ?: "Работа ${conflict.second.workplaceId}"
                        Text("${conflict.from.format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))}: " +
                            "$left / $right · ${conflict.overlapMinutes} мин",
                            color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
        if (!batchMode) {
            OutlinedButton(onClick = { batchMode = true }, modifier = Modifier.fillMaxWidth()) {
                Text("▣ Выбрать несколько дней", color = accent)
            }
        } else {
            Text("Выбрано дней: ${selected.size}. Нажимай на клетки календаря.",
                color = muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Button(enabled = selected.isNotEmpty() && selected.size <= 93,
                    onClick = { showBulk = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = bg)) {
                    Text("Изменить (${selected.size})")
                }
                OutlinedButton(onClick = { batchMode = false; selected = emptySet() }) {
                    Text("Отмена")
                }
            }
        }
        val selectedDay = focus
        Card(colors = CardDefaults.cardColors(containerColor = card)) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selectedDay.format(dayTitle).replaceFirstChar { it.uppercase(ru) },
                    fontWeight = FontWeight.Bold, fontSize = 19.sp)
                val items = byDate[selectedDay].orEmpty().sortedBy { it.workplaceId }
                if (items.isEmpty()) Text("На этот день записей нет", color = muted)
                items.forEach { entry ->
                    val name = workplaces.firstOrNull { it.id == entry.workplaceId }?.name
                        ?: "Работа ${entry.workplaceId}"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.SemiBold, color = dayColor(entry.type))
                            Text(
                                if (entry.type == DayType.WORK)
                                    "${displayTime(entry.startMinutes)}–${displayTime(entry.endMinutes)} · " +
                                        moneyLabel(entry.projectedKopecks)
                                else dayLabel(entry.type),
                                color = muted, fontSize = 12.sp
                            )
                        }
                        TextButton(onClick = { onEdit(selectedDay, entry.workplaceId) }) {
                            Text("Править", color = accent)
                        }
                    }
                }
                val target = filterWorkplaceId ?: workplaces.firstOrNull()?.id
                if (target != null) OutlinedButton(
                    onClick = { onEdit(selectedDay, target) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (filterWorkplaceId == null)
                        "+ Добавить / править у основного работодателя" else "+ Изменить день",
                        color = accent)
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = card)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("План на месяц", fontWeight = FontWeight.SemiBold)
                Text("${monthSummary.shifts} смен · ${monthSummary.hours} ч " +
                    "${monthSummary.remainderMinutes} мин · ${moneyLabel(monthSummary.projectedKopecks)}",
                    fontSize = 14.sp, color = accent)
            }
        }
        if (filterWorkplaceId != null) OutlinedButton(
            onClick = { onPattern(currentMonth.atDay(1)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("⚡ Автозаполнение графика", color = accent) }
        Spacer(Modifier.height(22.dp))
    }
    if (showBulk && selected.isNotEmpty()) {
        BulkEditDialog(
            dates = selected.sorted(), workplaces = workplaces,
            defaultJob = filterWorkplaceId ?: workplaces.firstOrNull()?.id ?: 1L,
            onDismiss = { showBulk = false },
            onApply = { plan ->
                val result = onBulk(plan)
                if (result.isSuccess) {
                    selected = emptySet()
                    batchMode = false
                    showBulk = false
                }
                result
            },
            onMove = { move ->
                val result = onMove(move)
                if (result.isSuccess) {
                    selected = emptySet()
                    batchMode = false
                    showBulk = false
                }
                result
            }
        )
    }
}

@Composable
private fun BulkEditDialog(
    dates: List<LocalDate>,
    workplaces: List<Workplace>,
    defaultJob: Long,
    onDismiss: () -> Unit,
    onApply: suspend (BulkPlan) -> Result<Unit>,
    onMove: suspend (ShiftMove) -> Result<Unit>
) {
    var employer by remember { mutableLongStateOf(defaultJob) }
    var kind by remember { mutableStateOf<DayType?>(DayType.WORK) }
    var start by remember { mutableStateOf("08:00") }
    var finish by remember { mutableStateOf("20:00") }
    var pay by remember(employer) {
        mutableStateOf(((workplaces.firstOrNull { it.id == employer }?.hourlyKopecks
            ?: 35_000L) / 100).toString())
    }
    var moveMode by remember { mutableStateOf(false) }
    var offsetText by remember { mutableStateOf("1") }
    var overwrite by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Массовое редактирование") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Выбрано: ${dates.size}. Изменится только выбранный работодатель.",
                    color = muted, fontSize = 12.sp)
                workplaces.forEach { workplace ->
                    FilterChip(
                        selected = employer == workplace.id,
                        onClick = { employer = workplace.id },
                        label = { Text(workplace.name) }
                    )
                }
                FilterChip(
                    selected = moveMode,
                    onClick = { moveMode = !moveMode },
                    label = { Text("↪ Перенести выбранные записи на другие даты") }
                )
                if (moveMode) OutlinedTextField(
                    value = offsetText,
                    onValueChange = { value ->
                        if (value.length <= 3 && value.all { it.isDigit() || it == '-' })
                            offsetText = value
                    },
                    label = { Text("На сколько дней перенести (−31 … +31)") },
                    supportingText = { Text("Положительное число — вперёд, отрицательное — назад") },
                    singleLine = true
                )
                if (!moveMode) listOf(
                    DayType.WORK to "Рабочие смены",
                    DayType.OFF to "Выходные",
                    DayType.VACATION to "Отпуск",
                    DayType.SICK to "Больничный",
                    DayType.HOLIDAY to "Праздник",
                    null to "Удалить записи"
                ).forEach { (value, title) ->
                    FilterChip(selected = kind == value, onClick = { kind = value },
                        label = { Text(title) })
                }
                if (!moveMode && kind == DayType.WORK) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShiftTimePickerField("Начало", start, !saving, Modifier.weight(1f)) {
                            start = it
                        }
                        ShiftTimePickerField("Окончание", finish, !saving, Modifier.weight(1f)) {
                            finish = it
                        }
                    }
                    OutlinedTextField(value = pay,
                        onValueChange = { if (it.all(Char::isDigit)) pay = it.take(6) },
                        label = { Text("Ставка, ₽/ч") }, singleLine = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = overwrite, onCheckedChange = {
                        overwrite = it; confirmed = false
                    })
                    Text("Перезаписать существующие дни", fontSize = 12.sp)
                }
                if (overwrite) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                        Text("Подтверждаю замену записей. Сделал(а) резервную копию.",
                            color = softRed, fontSize = 12.sp)
                    }
                } else Text(if (moveMode) "Все выбранные дни должны содержать записи. " +
                    "Занятые даты назначения не будут заменены без подтверждения."
                    else "Уже заполненные дни будут пропущены. " +
                        "Для удаления записей включи перезапись.",
                    color = muted, fontSize = 12.sp)
                if (error.isNotEmpty()) Text(error, color = softRed, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(enabled = !saving && (!overwrite || confirmed) && workplaces.isNotEmpty(),
                onClick = {
                    if (moveMode) {
                        val offset = offsetText.toIntOrNull()
                        if (offset == null || offset == 0 || offset !in -31..31) {
                            error = "Укажи смещение от −31 до 31 дней, кроме нуля."
                        } else {
                            saving = true
                            scope.launch {
                                onMove(ShiftMove(employer,dates,offset,overwrite)).fold(
                                    onSuccess = { onDismiss() },
                                    onFailure = {
                                        error = it.message ?: "Не удалось перенести записи"
                                        saving = false
                                    }
                                )
                            }
                        }
                    } else {
                        val from = if (kind == DayType.WORK) parseTime(start) else 480
                        val until = if (kind == DayType.WORK) parseTime(finish) else 1200
                        val rubles = pay.toLongOrNull()
                        if (from == null || until == null || rubles == null ||
                            rubles !in 0..100_000) {
                            error = "Проверь время и ставку."
                        } else {
                            saving = true
                            scope.launch {
                                onApply(BulkPlan(employer,dates,kind,from,until,rubles * 100,overwrite))
                                    .fold(
                                        onSuccess = { onDismiss() },
                                        onFailure = {
                                            error = it.message ?: "Не удалось изменить дни"
                                            saving = false
                                        }
                                    )
                            }
                        }
                    }
                }) {
                Text(if (saving) "Сохранение…" else "Применить")
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("Отмена") }
        }
    )
}

/** Cross-employer overview with lightweight per-job and six-month bar summaries. */
@Composable
fun AllWorkStatisticsScreen(
    shifts: List<WorkShift>,
    sessions: List<WorkSession>,
    payments: List<WagePayment>,
    workplaces: List<Workplace>
) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val now = System.currentTimeMillis()
    val allTotals = workplaces.map { job ->
        job to actualMonthSummary(
            sessions.filter { it.workplaceId == job.id },
            payments.filter { it.workplaceId == job.id }, month, now, job
        )
    }
    val totalPay = allTotals.sumOf { it.second.earnedKopecks }
    val totalPaid = allTotals.sumOf { it.second.paidKopecks }
    val totalMinutes = allTotals.sumOf { it.second.minutes }
    val monthlyValues = (5 downTo 0).map { ago ->
        val period = month.minusMonths(ago.toLong())
        period to workplaces.sumOf { job ->
            actualMonthSummary(
                sessions.filter { it.workplaceId == job.id },
                payments.filter { it.workplaceId == job.id }, period, now, job
            ).earnedKopecks
        }
    }
    val maxValue = max(1L,monthlyValues.maxOfOrNull { it.second.coerceAtLeast(0) } ?: 1L)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(6.dp))
        Text("Все места работы", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹",color=accent,fontSize=27.sp) }
            Text(month.format(monthTitle).replaceFirstChar { it.uppercase(ru) },
                Modifier.weight(1f), textAlign=TextAlign.Center, fontWeight=FontWeight.Bold)
            TextButton(onClick = { month = month.plusMonths(1) }) { Text("›",color=accent,fontSize=27.sp) }
        }
        InsightCard("Всего начислено",moneyLabel(totalPay), accent)
        InsightCard("Фактически отработано","${totalMinutes / 60} ч ${totalMinutes % 60} мин")
        InsightCard("Выплачено",moneyLabel(totalPaid))
        InsightCard("Расчётный остаток",moneyLabel(totalPay-totalPaid))
        Text("По работодателям",fontSize=19.sp,fontWeight=FontWeight.SemiBold)
        allTotals.forEach { (job, sum) ->
            Card(colors=CardDefaults.cardColors(containerColor=card),
                modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(job.name,fontWeight=FontWeight.Bold)
                    Text("${sum.completedSessions} смен · ${sum.minutes/60} ч ${sum.minutes%60} мин",
                        color=muted,fontSize=12.sp)
                    Text(moneyLabel(sum.earnedKopecks),color=accent,fontSize=21.sp,
                        fontWeight=FontWeight.Bold)
                }
            }
        }
        Text("Динамика начислений — 6 месяцев",fontSize=19.sp,fontWeight=FontWeight.SemiBold)
        Card(colors=CardDefaults.cardColors(containerColor=card)) {
            Column(Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement=Arrangement.spacedBy(11.dp)) {
                monthlyValues.forEach { (period, value) ->
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(period.format(DateTimeFormatter.ofPattern("MMM yy",ru)),
                            Modifier.width(64.dp),fontSize=12.sp,color=muted)
                        Box(Modifier.weight(1f).height(13.dp)
                            .background(Color(0xFF334039),RoundedCornerShape(5.dp))) {
                            if (value > 0) Box(
                                Modifier.fillMaxWidth((value.toFloat()/maxValue).coerceIn(0f,1f))
                                    .fillMaxHeight()
                                    .background(accent,RoundedCornerShape(5.dp)))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(moneyLabel(value),fontSize=11.sp,
                            modifier=Modifier.width(96.dp),textAlign=TextAlign.End)
                    }
                }
            }
        }
        Text("Итоги суммируются по всем работодателям. Начисления остаются оценочными.",
            color=muted,fontSize=12.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InsightCard(title: String, number: String, tint: Color = Color.White) {
    Card(modifier=Modifier.fillMaxWidth(),
        colors=CardDefaults.cardColors(containerColor=card)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title,color=muted,fontSize=13.sp)
            Spacer(Modifier.height(6.dp))
            Text(number,color=tint,fontWeight=FontWeight.Bold,fontSize=25.sp)
        }
    }
}
