package ru.smennik.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.smennik.app.BuildConfig
import ru.smennik.app.data.ShiftUiState
import ru.smennik.app.domain.*
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.delay
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
    state: ShiftUiState,
    onRetryLoad: () -> Unit,
    onSave: suspend (WorkShift) -> Result<Unit>,
    onRemove: suspend (LocalDate) -> Result<Unit>,
    onPattern: suspend (LocalDate, WorkPattern, Int, Int, Long, Boolean) -> Result<Unit>,
    onExportBackup: suspend (Uri) -> Result<Unit>,
    onImportBackup: suspend (Uri) -> Result<Int>
) {
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<LocalDate?>(null) }
    var showPatterns by remember { mutableStateOf(false) }
    var patternAnchor by remember { mutableStateOf(LocalDate.now()) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            onExportBackup(uri).fold(
                onSuccess = { snackbarHostState.showSnackbar("Резервная копия сохранена.") },
                onFailure = { snackbarHostState.showSnackbar("Не удалось сохранить копию: ${it.message ?: "ошибка файла"}") }
            )
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            restoreUri = uri
            restoreError = null
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = lime, onPrimary = graphite,
        background = graphite, onBackground = Color.White,
        surface = surface, onSurface = Color.White,
        surfaceVariant = Color(0xFF273139), onSurfaceVariant = textMuted
    )) {
        Scaffold(
            containerColor = graphite,
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                when {
                    state.isLoading -> LoadingScreen()
                    state.loadingError != null -> LoadErrorScreen(state.loadingError, onRetryLoad)
                    tab == 0 -> HomeScreen(
                        shifts = state.shifts,
                        isBusy = state.isBusy,
                        onEdit = { editing = it },
                        onCalendar = { tab = 1 },
                        onPattern = { patternAnchor = LocalDate.now(); showPatterns = true },
                        onExport = {
                            exportLauncher.launch("smennik-backup-${LocalDate.now()}.json")
                        },
                        onImport = { importLauncher.launch(arrayOf("application/json", "text/*")) }
                    )
                    else -> CalendarScreen(
                        shifts = state.shifts,
                        onEdit = { editing = it },
                        onPattern = { day -> patternAnchor = day; showPatterns = true }
                    )
                }
            }
        }
        editing?.let { date ->
            EditShiftDialog(
                date, state.shifts.firstOrNull { it.date == date }, state.isBusy,
                onDismiss = { editing = null },
                onSave = onSave,
                onRemove = { onRemove(date) }
            )
        }
        if (showPatterns) PatternDialog(
            anchor = patternAnchor,
            isBusy = state.isBusy,
            onDismiss = { showPatterns = false },
            onSelect = { pattern, start, end, rate, replace -> onPattern(patternAnchor, pattern, start, end, rate, replace) }
        )
        restoreUri?.let { uri ->
            AlertDialog(
                onDismissRequest = { if (!state.isBusy) restoreUri = null },
                title = { Text("Восстановить график?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Импорт заменит текущие записи графика данными из резервной копии.")
                        Text("Файл будет проверен перед изменением данных. Это действие нельзя отменить.", color = textMuted)
                        restoreError?.let { Text(it, color = Color(0xFFFF8585)) }
                    }
                },
                confirmButton = {
                    Button(enabled = !state.isBusy, onClick = {
                        scope.launch {
                            onImportBackup(uri).fold(
                                onSuccess = { count ->
                                    restoreUri = null
                                    snackbarHostState.showSnackbar("График восстановлен. Записей: $count.")
                                },
                                onFailure = { restoreError = "Не удалось восстановить копию: ${it.message ?: "ошибка файла"}" }
                            )
                        }
                    }) { Text(if (state.isBusy) "Восстановление…" else "Восстановить") }
                },
                dismissButton = {
                    TextButton(enabled = !state.isBusy, onClick = { restoreUri = null }) { Text("Отмена") }
                }
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = lime)
        Spacer(Modifier.height(14.dp))
        Text("Загружаю график…", color = textMuted)
    }
}

@Composable
private fun LoadErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Не удалось открыть график", color = Color(0xFFFF8585), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = textMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = lime, contentColor = graphite)) {
            Text("Повторить")
        }
    }
}

@Composable
private fun HomeScreen(
    shifts: List<WorkShift>,
    isBusy: Boolean,
    onEdit: (LocalDate) -> Unit,
    onCalendar: () -> Unit,
    onPattern: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = LocalDateTime.now()
        }
    }
    val today = now.toLocalDate()
    val todayEntry = shifts.firstOrNull { it.date == today }
    val active = activeShiftAt(shifts, now)
    val todayShift = todayEntry?.takeIf { it.type == DayType.WORK }
    val currentShift = active ?: todayShift
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
                Text(
                    when {
                        active != null && active.date.isBefore(today) -> "●  ПРОДОЛЖАЕТСЯ НОЧНАЯ СМЕНА"
                        currentShift != null -> "●  СЕГОДНЯ РАБОТА"
                        todayEntry?.type == DayType.OFF -> "●  СЕГОДНЯ ВЫХОДНОЙ"
                        else -> "●  ГРАФИК НЕ ЗАДАН"
                    },
                    color = if (currentShift != null) lime else textMuted,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                Text(
                    when {
                        currentShift != null -> "${displayTime(currentShift.startMinutes)} — ${displayTime(currentShift.endMinutes)}"
                        todayEntry?.type == DayType.OFF -> "Свободный день"
                        else -> "Нет записи на сегодня"
                    },
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
                    onClick = { onEdit(currentShift?.date ?: today) },
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
            Metric("Часы", "${summary.hours} ч ${summary.remainderMinutes} мин", Modifier.weight(1f))
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
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Резервная копия", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(4.dp))
                Text("Сохрани график в файл или восстанови его на этом устройстве.", color = textMuted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExport, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                        Text("Экспорт JSON", color = lime)
                    }
                    OutlinedButton(onClick = onImport, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                        Text("Импорт JSON", color = lime)
                    }
                }
            }
        }
        TextButton(onClick = onCalendar, modifier = Modifier.fillMaxWidth()) {
            Text("Открыть календарь →", color = Color.White)
        }
        Text(
            "Версия ${BuildConfig.VERSION_NAME} · сборка ${BuildConfig.VERSION_CODE}",
            modifier = Modifier.fillMaxWidth(),
            color = textMuted, fontSize = 12.sp, textAlign = TextAlign.Center
        )
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
                Column { Text("ЧАСОВ", color = textMuted, fontSize = 12.sp); Text("${summary.hours} ч ${summary.remainderMinutes} мин", fontSize = 19.sp) }
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
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (WorkShift) -> Result<Unit>,
    onRemove: suspend () -> Result<Unit>
) {
    val scope = rememberCoroutineScope()
    var start by remember(date, old) { mutableStateOf(displayTime(old?.startMinutes ?: 480)) }
    var end by remember(date, old) { mutableStateOf(displayTime(old?.endMinutes ?: 1200)) }
    var hourlyRubles by remember(date, old) { mutableStateOf(((old?.hourlyKopecks ?: 35_000L) / 100).toString()) }
    var validation by remember(date) { mutableStateOf("") }
    var operationError by remember(date) { mutableStateOf("") }
    var saving by remember(date) { mutableStateOf(false) }
    val disabled = isBusy || saving

    fun submit(shift: WorkShift) {
        if (disabled) return
        saving = true
        operationError = ""
        scope.launch {
            onSave(shift).fold(
                onSuccess = { onDismiss() },
                onFailure = {
                    operationError = "Не удалось сохранить: ${it.message ?: "ошибка базы данных"}"
                    saving = false
                }
            )
        }
    }

    fun clearDay() {
        if (disabled) return
        saving = true
        operationError = ""
        scope.launch {
            onRemove().fold(
                onSuccess = { onDismiss() },
                onFailure = {
                    operationError = "Не удалось удалить запись: ${it.message ?: "ошибка базы данных"}"
                    saving = false
                }
            )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!disabled) onDismiss() },
        title = { Text(date.format(dayFormatter).replaceFirstChar { it.uppercase(russian) }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Время работы и почасовая ставка", color = textMuted)
                OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("Начало, ЧЧ:ММ") }, singleLine = true)
                OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("Окончание, ЧЧ:ММ") }, singleLine = true)
                OutlinedTextField(value = hourlyRubles, onValueChange = { hourlyRubles = it }, label = { Text("Ставка, ₽/час") }, singleLine = true)
                Text("Равное время начала и конца — смена 24 часа. Более раннее окончание — следующий день.", fontSize = 12.sp, color = textMuted)
                if (validation.isNotEmpty()) Text(validation, color = Color(0xFFFF8585), fontSize = 13.sp)
                if (operationError.isNotEmpty()) Text(operationError, color = Color(0xFFFF8585), fontSize = 13.sp)
                OutlinedButton(enabled = !disabled, onClick = { submit(WorkShift(date, DayType.OFF)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Отметить выходной")
                }
                if (old != null) TextButton(enabled = !disabled, onClick = ::clearDay) {
                    Text("Очистить день", color = Color(0xFFFF8585))
                }
            }
        },
        confirmButton = {
            Button(enabled = !disabled, onClick = {
                val from = parseTime(start)
                val to = parseTime(end)
                val rate = hourlyRubles.toLongOrNull()
                if (from == null || to == null || rate == null || rate !in 0..100_000) {
                    validation = "Проверь время (ЧЧ:ММ) и ставку (0–100000 ₽)."
                } else submit(WorkShift(date, DayType.WORK, from, to, rate * 100))
            }) { Text(if (saving) "Сохраняю…" else "Сохранить") }
        },
        dismissButton = { TextButton(enabled = !disabled, onClick = onDismiss) { Text("Отмена") } }
    )
}

private data class PatternSelection(val pattern: WorkPattern, val start: Int, val end: Int, val rate: Long)

@Composable
private fun PatternDialog(
    anchor: LocalDate,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSelect: suspend (WorkPattern, Int, Int, Long, Boolean) -> Result<Unit>
) {
    val scope = rememberCoroutineScope()
    var start by remember(anchor) { mutableStateOf("08:00") }
    var end by remember(anchor) { mutableStateOf("20:00") }
    var hourlyRubles by remember(anchor) { mutableStateOf("350") }
    var validation by remember(anchor) { mutableStateOf("") }
    var saving by remember(anchor) { mutableStateOf(false) }
    var replaceExisting by remember(anchor) { mutableStateOf(false) }
    var pending by remember(anchor) { mutableStateOf<PatternSelection?>(null) }
    val disabled = isBusy || saving

    fun selectPattern(pattern: WorkPattern, from: Int, to: Int, rate: Long, replace: Boolean) {
        if (disabled) return
        saving = true
        validation = ""
        scope.launch {
            onSelect(pattern, from, to, rate, replace).fold(
                onSuccess = { onDismiss() },
                onFailure = {
                    validation = "Не удалось создать график: ${it.message ?: "ошибка базы данных"}"
                    saving = false
                }
            )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!disabled) onDismiss() },
        title = { Text("Создать рабочий график") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Период: ${anchor.format(dayFormatter)} — ${anchor.plusDays(89).format(dayFormatter)}. При обычном обновлении сохраняются вручную изменённые дни.", color = textMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = replaceExisting, enabled = !disabled,
                        onCheckedChange = { replaceExisting = it })
                    Text("Заменить ВСЕ дни периода, включая ручные", color = Color(0xFFFFC47A), fontSize = 12.sp)
                }
                if (replaceExisting) Text("Внимание: все записи за эти 90 дней будут заменены. Сначала экспортируйте резервную копию.", color = Color(0xFFFF8585), fontSize = 12.sp)
                Text("Для 5/2 рабочие дни — понедельник–пятница. Совпадающее начало и окончание = 24 ч.", color = textMuted, fontSize = 12.sp)
                OutlinedTextField(
                    value = start, onValueChange = { start = it },
                    label = { Text("Начало смены, ЧЧ:ММ") }, singleLine = true
                )
                OutlinedTextField(
                    value = end, onValueChange = { end = it },
                    label = { Text("Окончание смены, ЧЧ:ММ") }, singleLine = true
                )
                OutlinedTextField(
                    value = hourlyRubles,
                    onValueChange = { value -> if (value.all { it.isDigit() }) hourlyRubles = value },
                    label = { Text("Ставка, ₽/час") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                if (validation.isNotEmpty()) Text(validation, color = Color(0xFFFF8585), fontSize = 13.sp)
                listOf(2 to 2, 3 to 3, 5 to 2, 1 to 3, 4 to 2).forEach { (on, off) ->
                    OutlinedButton(enabled = !disabled, onClick = {
                        val from = parseTime(start)
                        val to = parseTime(end)
                        val rate = hourlyRubles.toLongOrNull()
                        if (from == null || to == null || rate == null || rate !in 0..100_000) {
                            validation = "Проверь время (ЧЧ:ММ) и ставку (0–100000 ₽)."
                        } else {
                            if (replaceExisting) {
                                pending = PatternSelection(WorkPattern(on, off), from, to, rate * 100)
                            } else {
                                selectPattern(WorkPattern(on, off), from, to, rate * 100, false)
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("График $on/$off", color = lime)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !disabled, onClick = onDismiss) { Text("Закрыть") } }
    )
    pending?.let { request ->
        AlertDialog(
            onDismissRequest = { if (!disabled) pending = null },
            title = { Text("Удалить текущий график за 90 дней?") },
            text = { Text("Все смены и выходные в выбранном периоде будут перезаписаны, включая ручные изменения. Это нельзя отменить без резервной копии.") },
            confirmButton = {
                Button(enabled = !disabled, onClick = {
                    pending = null
                    selectPattern(request.pattern, request.start, request.end, request.rate, true)
                }) { Text("Заменить график") }
            },
            dismissButton = {
                TextButton(enabled = !disabled, onClick = { pending = null }) { Text("Отмена") }
            }
        )
    }
}
