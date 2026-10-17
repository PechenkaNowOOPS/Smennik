package ru.smennik.app.ui

import androidx.compose.foundation.BorderStroke
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
import ru.smennik.app.domain.*
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val graphite = Color(0xFF101418)
private val surface = Color(0xFF1B2229)
private val lime = Color(0xFFB7F34B)
private val textMuted = Color(0xFF9AA5AF)
private val russian = Locale("ru", "RU")
private val dayFormatter = DateTimeFormatter.ofPattern("d MMMM, EEEE", russian)
private val monthFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", russian)

private fun rubles(kopecks: Long): String =
    NumberFormat.getNumberInstance(russian).apply { minimumFractionDigits = 0; maximumFractionDigits = 2 }
        .format(kopecks / 100.0) + " ₽"

@Composable
fun SmennikApp(
    shifts: List<WorkShift>,
    error: String?,
    onSave: (WorkShift) -> Unit,
    onRemove: (LocalDate) -> Unit,
    onPattern: (LocalDate, WorkPattern) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<LocalDate?>(null) }
    var showPatterns by remember { mutableStateOf(false) }
    var patternAnchor by remember { mutableStateOf(LocalDate.now()) }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = lime, onPrimary = graphite,
        background = graphite, onBackground = Color.White,
        surface = surface, onSurface = Color.White,
        surfaceVariant = Color(0xFF273139), onSurfaceVariant = textMuted
    )) {
        Scaffold(
            containerColor = graphite,
            bottomBar = {
                NavigationBar(containerColor = surface) {
                    listOf("Главная", "Календарь").forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Text(if (index == 0) "⌂" else "▦", fontSize = 24.sp) },
                            label = { Text(title) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = lime, selectedTextColor = lime,
                                indicatorColor = Color(0xFF314129),
                                unselectedIconColor = textMuted, unselectedTextColor = textMuted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (error != null) Text(
                    text = error, color = Color(0xFFFF8585),
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                )
                if (tab == 0) HomeScreen(
                    shifts = shifts,
                    onEdit = { editing = it },
                    onCalendar = { tab = 1 },
                    onPattern = { patternAnchor = LocalDate.now(); showPatterns = true }
                ) else CalendarScreen(
                    shifts = shifts,
                    onEdit = { editing = it },
                    onPattern = { day -> patternAnchor = day; showPatterns = true }
                )
            }
        }
        editing?.let { date ->
            EditShiftDialog(
                date, shifts.firstOrNull { it.date == date },
                onDismiss = { editing = null },
                onSave = { onSave(it); editing = null },
                onRemove = { onRemove(date); editing = null }
            )
        }
        if (showPatterns) PatternDialog(
            anchor = patternAnchor,
            onDismiss = { showPatterns = false },
            onSelect = { pattern -> onPattern(patternAnchor, pattern); showPatterns = false }
        )
    }
}

@Composable
private fun HomeScreen(
    shifts: List<WorkShift>,
    onEdit: (LocalDate) -> Unit,
    onCalendar: () -> Unit,
    onPattern: () -> Unit
) {
    val today = LocalDate.now()
    val currentShift = shifts.firstOrNull { it.date == today && it.type == DayType.WORK }
    val nextShift = shifts.filter { !it.date.isBefore(today) && it.type == DayType.WORK }
        .minByOrNull { it.date }
    val summary = monthlySummary(shifts, YearMonth.from(today))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        Text("СМЕННИК", color = lime, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Text("Мой рабочий день", fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text(today.format(dayFormatter).replaceFirstChar { it.uppercase(russian) }, color = textMuted)
        Spacer(Modifier.height(26.dp))

        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(if (currentShift != null) "●  СЕГОДНЯ РАБОТА" else "●  СЕГОДНЯ ВЫХОДНОЙ",
                    color = if (currentShift != null) lime else textMuted,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                Text(
                    if (currentShift == null) "Свободный день" else
                        "${displayTime(currentShift.startMinutes)} — ${displayTime(currentShift.endMinutes)}",
                    fontSize = 26.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        currentShift != null -> "${currentShift.durationMinutes / 60} ч ${currentShift.durationMinutes % 60} мин · прогноз ${rubles(currentShift.projectedKopecks)}"
                        nextShift != null -> "Следующая смена ${nextShift.date.format(dayFormatter)}"
                        else -> "Добавь смену или создай рабочий график"
                    }, color = textMuted
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { onEdit(today) },
                    colors = ButtonDefaults.buttonColors(containerColor = lime, contentColor = graphite),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (currentShift == null) "+ Добавить смену" else "Изменить смену") }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Этот месяц", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("Смены", summary.shifts.toString(), Modifier.weight(1f))
            Metric("Часы", "${summary.hours} ч", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("ПРОГНОЗ ДОХОДА", color = textMuted, fontSize = 12.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                Text(rubles(summary.projectedKopecks), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = lime)
                Text("По запланированным сменам · без учёта выплат", color = textMuted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onPattern, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, lime)) {
            Text("⚡ Создать график 2/2, 3/3…", color = lime)
        }
        TextButton(onClick = onCalendar, modifier = Modifier.fillMaxWidth()) {
            Text("Открыть календарь →", color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(label, color = textMuted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        }
    }
}

@Composable
private fun CalendarScreen(
    shifts: List<WorkShift>,
    onEdit: (LocalDate) -> Unit,
    onPattern: (LocalDate) -> Unit
) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val today = LocalDate.now()
    val summary = monthlySummary(shifts, month)
    val byDay = remember(shifts) { shifts.associateBy { it.date } }
    val first = month.atDay(1)
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val cells = (0 until 42).map { start.plusDays(it.toLong()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(22.dp))
        Text("Календарь смен", fontWeight = FontWeight.Bold, fontSize = 27.sp)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹", fontSize = 28.sp, color = lime) }
            Text(month.format(monthFormatter).replaceFirstChar { it.uppercase(russian) },
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                fontSize = 19.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { month = month.plusMonths(1) }) { Text("›", fontSize = 28.sp, color = lime) }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { name ->
                Text(name, Modifier.weight(1f), textAlign = TextAlign.Center, color = textMuted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        (0 until 6).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                (0 until 7).forEach { day ->
                    val date = cells[week * 7 + day]
                    val shift = byDay[date]
                    val isThisMonth = date.month == month.month && date.year == month.year
                    val working = shift?.type == DayType.WORK
                    Column(
                        modifier = Modifier.weight(1f).height(60.dp)
                            .background(
                                if (working && isThisMonth) Color(0xFF304326)
                                else if (date == today) Color(0xFF293139) else surface,
                                RoundedCornerShape(11.dp)
                            ).clickable { month = YearMonth.from(date); onEdit(date) }.padding(top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(date.dayOfMonth.toString(),
                            color = if (!isThisMonth) Color(0xFF59646F) else if (working) lime else Color.White,
                            fontWeight = if (working || date == today) FontWeight.Bold else FontWeight.Normal)
                        if (working) Text("●", color = lime, fontSize = 11.sp)
                        else if (shift?.type == DayType.OFF && isThisMonth) Text("—", color = textMuted, fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("● Рабочий", color = lime, fontSize = 13.sp)
            Spacer(Modifier.width(14.dp))
            Text("— Выходной", color = textMuted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = surface)) {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("СМЕН", color = textMuted, fontSize = 12.sp); Text(summary.shifts.toString(), fontSize = 25.sp) }
                Column { Text("ЧАСОВ", color = textMuted, fontSize = 12.sp); Text(summary.hours.toString(), fontSize = 25.sp) }
                Column { Text("ПРОГНОЗ", color = textMuted, fontSize = 12.sp); Text(rubles(summary.projectedKopecks), fontSize = 20.sp, color = lime) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = { onEdit(today) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = lime, contentColor = graphite)) {
            Text("+ Добавить или изменить смену сегодня")
        }
        OutlinedButton(onClick = { onPattern(month.atDay(1)) }, modifier = Modifier.fillMaxWidth()) {
            Text("Автозаполнение графика", color = lime)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EditShiftDialog(
    date: LocalDate,
    old: WorkShift?,
    onDismiss: () -> Unit,
    onSave: (WorkShift) -> Unit,
    onRemove: () -> Unit
) {
    var start by remember(date, old) { mutableStateOf(displayTime(old?.startMinutes ?: 480)) }
    var end by remember(date, old) { mutableStateOf(displayTime(old?.endMinutes ?: 1200)) }
    var hourlyRubles by remember(date, old) { mutableStateOf(((old?.hourlyKopecks ?: 35_000L) / 100).toString()) }
    var validation by remember(date) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(date.format(dayFormatter).replaceFirstChar { it.uppercase(russian) }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Время работы и почасовая ставка", color = textMuted)
                OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("Начало, ЧЧ:ММ") }, singleLine = true)
                OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("Окончание, ЧЧ:ММ") }, singleLine = true)
                OutlinedTextField(value = hourlyRubles, onValueChange = { hourlyRubles = it }, label = { Text("Ставка, ₽/час") }, singleLine = true)
                Text("Ночная смена: окончание раньше начала означает следующий день.", fontSize = 12.sp, color = textMuted)
                if (validation.isNotEmpty()) Text(validation, color = Color(0xFFFF8585), fontSize = 13.sp)
                OutlinedButton(onClick = { onSave(WorkShift(date, DayType.OFF)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Отметить выходной")
                }
                if (old != null) TextButton(onClick = onRemove) { Text("Очистить день", color = Color(0xFFFF8585)) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val from = parseTime(start)
                val to = parseTime(end)
                val rate = hourlyRubles.toLongOrNull()
                if (from == null || to == null || from == to || rate == null || rate !in 0..100_000) {
                    validation = "Проверь время (ЧЧ:ММ) и ставку (0–100000 ₽)."
                } else onSave(WorkShift(date, DayType.WORK, from, to, rate * 100))
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun PatternDialog(anchor: LocalDate, onDismiss: () -> Unit, onSelect: (WorkPattern) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Создать рабочий график") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("От ${anchor.format(dayFormatter)}. Первый день — рабочий. Заполняются 90 дней, существующие записи не меняются.", color = textMuted)
                listOf(2 to 2, 3 to 3, 5 to 2, 1 to 3, 4 to 2).forEach { (on, off) ->
                    OutlinedButton(onClick = { onSelect(WorkPattern(on, off)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("График $on/$off", color = lime)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}
