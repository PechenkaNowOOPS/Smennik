package ru.smennik.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.smennik.app.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val light = Color(0xFFB7F34B)
private val subtle = Color(0xFF9AA5AF)
private val alertRed = Color(0xFFFF8585)
private val bgDark = Color(0xFF1B2229)
private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** Add a missed shift or edit a completed one without restarting the live timer. */
@Composable
fun HistoricalWorkDialog(
    existing: WorkSession?,
    workplace: Workplace,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: suspend (WorkSession?, Long, Long, Long, List<WorkBreak>, Int) -> Result<Unit>
) {
    val zone = ZoneId.systemDefault()
    fun formatMillis(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(fmt)
    val todayStart = initialDate.atTime(8,0).atZone(zone).toInstant().toEpochMilli()
    var start by remember(existing?.id,initialDate) {
        mutableStateOf(formatMillis(existing?.startedMs ?: todayStart))
    }
    var end by remember(existing?.id,initialDate) {
        mutableStateOf(formatMillis(existing?.endedMs ?: todayStart + 12*60*60*1000L))
    }
    var rate by remember(existing?.id) {
        mutableStateOf(((existing?.hourlyKopecks ?: workplace.hourlyKopecks) / 100L).toString())
    }
    // Older releases stored break duration only. Preserve those minutes until
    // the user explicitly converts them into timestamped intervals.
    val recordedMs = existing?.breaks?.filterNot { it.paid }
        ?.sumOf { it.endMs-it.startMs } ?: 0L
    val extra = ((existing?.totalPausedMs ?: 0L)-recordedMs).coerceAtLeast(0L)/60_000L
    var legacyMinutes by remember(existing?.id) { mutableStateOf(extra.toString()) }
    var pauses by remember(existing?.id) {
        mutableStateOf(existing?.breaks?.sortedBy { it.startMs }.orEmpty())
    }
    var editingBreak by remember { mutableStateOf(false) }
    var breakStart by remember { mutableStateOf(start) }
    var breakEnd by remember { mutableStateOf(end) }
    var breakPaid by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun epoch(text: String): Long? = runCatching {
        LocalDateTime.parse(text,fmt).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()
    val startMs = epoch(start)
    val endMs = epoch(end)
    val tariff = rate.toLongOrNull()?.times(100L)
    val legacy = legacyMinutes.toIntOrNull()
    val current = if (startMs != null && endMs != null && tariff != null &&
        legacy != null && startMs > 0 && endMs > startMs) {
        runCatching {
            validateBreaks(startMs,endMs,pauses)
            val unapplied = pauses.filterNot { it.paid }.sumOf { it.endMs-it.startMs }
            require(unapplied+legacy*60_000L < endMs-startMs)
            WorkSession(
                id=existing?.id ?: 0L,
                date=Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate(),
                startedMs=startMs,endedMs=endMs,workplaceId=workplace.id,
                hourlyKopecks=tariff,
                totalPausedMs=unapplied+legacy*60_000L,
                breaks=pauses
            )
        }.getOrNull()
    } else null
    val now = System.currentTimeMillis()
    val total = current?.let {
        actualMonthSummary(listOf(it),emptyList(),
            YearMonth.from(it.date),now,workplace)
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(if (existing==null) "Добавить отработанную смену" else "Изменить отработанную смену",
                fontWeight=FontWeight.Bold)
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max=500.dp)
                .verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Text("Фактическое время · ${workplace.name}",color=subtle,fontSize=12.sp)
                ShiftDateTimePickerField("Начало",start,!saving) { start=it }
                ShiftDateTimePickerField("Окончание",end,!saving) { end=it }
                OutlinedTextField(
                    value=rate,
                    onValueChange={ if(it.length<=6 && it.all(Char::isDigit)) rate=it },
                    label={ Text("Ставка, ₽/час") },
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                    singleLine=true
                )
                Text("ПЕРЕРЫВЫ",color=light,fontWeight=FontWeight.Bold,fontSize=14.sp)
                pauses.forEachIndexed { index, pause ->
                    val range = "${formatMillis(pause.startMs)} — ${formatMillis(pause.endMs)}"
                    Card(colors=CardDefaults.cardColors(containerColor=bgDark)) {
                        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(range,fontSize=12.sp)
                            Text(if(pause.paid) "Оплачиваемый" else "Неоплачиваемый",
                                color=if(pause.paid) light else subtle,fontSize=12.sp)
                            TextButton(enabled=!saving,onClick={
                                pauses=pauses.filterIndexed { i,_ -> i!=index }
                            }) { Text("Удалить перерыв",color=alertRed) }
                        }
                    }
                }
                if (editingBreak) {
                    ShiftDateTimePickerField("Начало перерыва",breakStart,!saving) { breakStart=it }
                    ShiftDateTimePickerField("Конец перерыва",breakEnd,!saving) { breakEnd=it }
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked=breakPaid,enabled=!saving,onCheckedChange={ breakPaid=it })
                        Text("Оплачиваемый перерыв",fontSize=12.sp)
                    }
                    OutlinedButton(enabled=!saving,onClick={
                        val from=epoch(breakStart)
                        val until=epoch(breakEnd)
                        if(from==null || until==null || until<=from || startMs==null ||
                            endMs==null) {
                            error="Проверь начало и конец перерыва."
                        } else {
                            runCatching {
                                val test=pauses+WorkBreak(from,until,breakPaid)
                                validateBreaks(startMs,endMs,test)
                                test.sortedBy { it.startMs }
                            }.fold(onSuccess={
                                pauses=it;editingBreak=false;error=""
                            },onFailure={error=it.message ?: "Неверный интервал."})
                        }
                    }) { Text("Добавить перерыв",color=light) }
                } else {
                    OutlinedButton(enabled=!saving && pauses.size<64,onClick={
                        breakStart=start
                        breakEnd=end
                        breakPaid=false
                        editingBreak=true
                    },modifier=Modifier.fillMaxWidth()) {
                        Text("＋ Указать интервал перерыва",color=light)
                    }
                }
                OutlinedTextField(
                    value=legacyMinutes,
                    onValueChange={if(it.all(Char::isDigit)&&it.length<=4)legacyMinutes=it},
                    label={Text("Перерывы без времени, мин")},
                    supportingText={Text("Старые записи сохраняются; точный ночной расчёт только для интервалов.")},
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                    singleLine=true
                )
                if(current!=null && total!=null) {
                    val worked=current.workedMinutes(now)
                    Card(colors=CardDefaults.cardColors(containerColor=bgDark)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("Отработано: ${worked/60} ч ${worked%60} мин",
                                fontWeight=FontWeight.Bold)
                            Text("Ночные: ${total.nightMinutes} мин",
                                color=subtle)
                            Text("Начислено: ${total.earnedKopecks/100} ₽",
                                color=light,fontWeight=FontWeight.Bold)
                        }
                    }
                }
                Text("Данные сохраняются отдельно от планового календаря.",
                    fontSize=12.sp,color=subtle)
                if(error.isNotBlank())Text(error,color=alertRed,fontSize=12.sp)
            }
        },
        confirmButton={
            Button(enabled=!saving && !editingBreak,onClick={
                val session=current
                val extraMinutes=legacy
                if(session==null || extraMinutes==null || extraMinutes !in 0..2880 ||
                    tariff==null || tariff !in 0..10_000_000L ||
                    endMs==null || endMs>now+60_000L ||
                    endMs-session.startedMs>48L*60*60*1000) {
                    error="Проверь время (до 48 ч), тариф и перерывы. Смена должна быть завершена."
                } else {
                    saving=true
                    scope.launch {
                        onSave(existing,session.startedMs,endMs,tariff,pauses,extraMinutes)
                            .fold(
                                onSuccess={onDismiss()},
                                onFailure={
                                    error=it.message ?: "Ошибка сохранения"
                                    saving=false
                                }
                            )
                    }
                }
            },colors=ButtonDefaults.buttonColors(containerColor=light)) {
                Text(if(saving)"Сохранение…" else "Сохранить")
            }
        },
        dismissButton={
            TextButton(enabled=!saving,onClick=onDismiss) { Text("Отмена") }
        }
    )
}
