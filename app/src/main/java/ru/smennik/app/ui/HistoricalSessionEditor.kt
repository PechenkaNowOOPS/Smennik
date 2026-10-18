package ru.smennik.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import java.time.format.DateTimeFormatter
import java.util.Locale

private val goalGreen = Color(0xFFB7F34B)
private val secondary = Color(0xFF9AA5AF)
private val orange = Color(0xFFFFC47A)
private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val displayFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm",Locale("ru","RU"))

/**
 * Manual actual work and finished-session correction share one form.
 * Detailed paid / unpaid intervals are kept separately from legacy total-only
 * pauses; all validation is duplicated inside the SQLite transaction.
 */
@Composable
fun HistoricalSessionEditor(
    workplace: Workplace,
    currentMonth: java.time.YearMonth,
    existing: WorkSession?,
    onDismiss: () -> Unit,
    onCreate: suspend (Long,Long,Long,List<WorkBreak>,Int) -> Result<Unit>,
    onUpdate: suspend (Long,Long,Long,Long,List<WorkBreak>,Int) -> Result<Unit>
) {
    val zone=ZoneId.systemDefault()
    val today=LocalDate.now()
    val baseDate=if (java.time.YearMonth.from(today)==currentMonth) today
        else currentMonth.atDay(1)
    val startDefault=baseDate.atTime(8,0).format(dateFormat)
    val endDefault=baseDate.atTime(20,0).format(dateFormat)
    var from by remember(existing?.id,workplace.id) {
        mutableStateOf(existing?.let {
            Instant.ofEpochMilli(it.startedMs).atZone(zone).format(dateFormat)
        } ?: startDefault)
    }
    var until by remember(existing?.id,workplace.id) {
        mutableStateOf(existing?.endedMs?.let {
            Instant.ofEpochMilli(it).atZone(zone).format(dateFormat)
        } ?: endDefault)
    }
    var rate by remember(existing?.id,workplace.id) {
        mutableStateOf(((existing?.hourlyKopecks ?: workplace.hourlyKopecks)/100).toString())
    }
    val pauses=remember(existing?.id,workplace.id) {
        mutableStateListOf<WorkBreak>().apply { addAll(existing?.breaks.orEmpty()) }
    }
    val legacyMs=existing?.let { item ->
        (item.totalPausedMs-item.breaks.filterNot { it.paid }
            .sumOf { it.endMs-it.startMs }).coerceAtLeast(0L)
    } ?: 0L
    var legacyMinutes by remember(existing?.id,workplace.id) {
        mutableStateOf((legacyMs/60_000L).toString())
    }
    var newPauseStart by remember(existing?.id,workplace.id) {
        mutableStateOf(baseDate.atTime(12,0).format(dateFormat))
    }
    var newPauseEnd by remember(existing?.id,workplace.id) {
        mutableStateOf(baseDate.atTime(12,30).format(dateFormat))
    }
    var paid by remember(existing?.id,workplace.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    fun stamp(value: String): Long? =
        runCatching { LocalDateTime.parse(value,dateFormat).atZone(zone)
            .toInstant().toEpochMilli() }.getOrNull()

    AlertDialog(
        onDismissRequest={ if (!busy) onDismiss() },
        title={ Text(if(existing==null) "Добавить отработанную смену"
            else "Изменить фактическую смену") },
        text={
            Column(
                modifier=Modifier.fillMaxWidth().heightIn(max=535.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(9.dp)
            ) {
                Text(workplace.name,color=goalGreen,fontWeight=FontWeight.Bold)
                ShiftDateTimePickerField("Начало",from,!busy) { from=it }
                ShiftDateTimePickerField("Конец",until,!busy) { until=it }
                OutlinedTextField(
                    value=rate,
                    onValueChange={ if(it.length<=6 && it.all(Char::isDigit)) rate=it },
                    label={Text("Ставка, ₽/час")},
                    singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number)
                )
                HorizontalDivider()
                Text("Перерывы",fontWeight=FontWeight.SemiBold)
                if(pauses.isEmpty())
                    Text("Интервалы пока не добавлены.",color=secondary,fontSize=12.sp)
                pauses.toList().sortedBy { it.startMs }.forEach { interval ->
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                Instant.ofEpochMilli(interval.startMs).atZone(zone)
                                    .format(displayFormat)+" — "+
                                Instant.ofEpochMilli(interval.endMs).atZone(zone)
                                    .format(displayFormat),
                                fontSize=12.sp,fontWeight=FontWeight.Bold
                            )
                            Text(if(interval.paid) "Оплачиваемый" else "Неоплачиваемый",
                                fontSize=11.sp,color=if(interval.paid)goalGreen else orange)
                        }
                        TextButton(enabled=!busy,onClick={ pauses.remove(interval) }) {
                            Text("Удалить",color=orange)
                        }
                    }
                }
                Text("Добавить перерыв:",color=secondary,fontSize=12.sp)
                ShiftDateTimePickerField("Начало перерыва",newPauseStart,!busy) {
                    newPauseStart=it
                }
                ShiftDateTimePickerField("Окончание перерыва",newPauseEnd,!busy) {
                    newPauseEnd=it
                }
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked=paid,enabled=!busy,onCheckedChange={paid=it})
                    Text("Оплачиваемый перерыв",fontSize=13.sp)
                }
                OutlinedButton(
                    enabled=!busy,
                    modifier=Modifier.fillMaxWidth(),
                    onClick={
                        val a=stamp(newPauseStart)
                        val b=stamp(newPauseEnd)
                        val first=stamp(from)
                        val last=stamp(until)
                        if(a==null||b==null||first==null||last==null) {
                            error="Выбери корректные дату и время."
                        } else {
                            runCatching {
                                val next=pauses.toList()+WorkBreak(a,b,paid)
                                validateBreaks(first,last,next)
                                pauses.add(WorkBreak(a,b,paid))
                                error=""
                            }.onFailure { error=it.message ?: "Неправильный перерыв." }
                        }
                    }
                ) {Text("＋ Добавить интервал",color=goalGreen)}
                OutlinedTextField(
                    value=legacyMinutes,
                    onValueChange={
                        if(it.length<=4 && it.all(Char::isDigit)) legacyMinutes=it
                    },
                    label={Text("Прочие неоплачиваемые минуты")},
                    singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                    supportingText={
                        Text("Для старых смен без времени начала/конца перерывов.")
                    }
                )
                Text("Оплачиваемые перерывы остаются в оплачиваемых часах. " +
                    "Ночные надбавки пересчитываются с учётом точного времени " +
                    "неоплачиваемых перерывов.",
                    fontSize=12.sp,color=secondary)
                if(error.isNotBlank()) Text(error,color=Color(0xFFFF8585),fontSize=12.sp)
            }
        },
        confirmButton={
            Button(enabled=!busy,onClick={
                val startMs=stamp(from)
                val endMs=stamp(until)
                val hourly=rate.toLongOrNull()
                val legacy=legacyMinutes.toIntOrNull()
                if(startMs==null||endMs==null||hourly==null||legacy==null||
                    hourly !in 0..100_000||legacy !in 0..2880 ||
                    endMs>System.currentTimeMillis()+60_000L) {
                    error="Укажи завершённую смену, действительную ставку и длительность перерывов."
                } else {
                    runCatching {
                        validateBreaks(startMs,endMs,pauses.toList())
                        require(endMs-startMs<=48L*60*60*1000L)
                        val unpaid=pauses.filterNot {it.paid}
                            .sumOf {it.endMs-it.startMs}
                        require(unpaid+legacy*60_000L<endMs-startMs)
                    }.onFailure {error=it.message ?: "Некорректные интервалы."}
                        .onSuccess {
                            error=""
                            busy=true
                            scope.launch {
                                val result=if(existing==null)
                                    onCreate(startMs,endMs,hourly*100L,
                                        pauses.toList(),legacy)
                                else
                                    onUpdate(existing.id,startMs,endMs,hourly*100L,
                                        pauses.toList(),legacy)
                                result.fold(
                                    onSuccess={onDismiss()},
                                    onFailure={
                                        error=it.message ?: "Ошибка сохранения."
                                        busy=false
                                    }
                                )
                            }
                        }
                }
            },colors=ButtonDefaults.buttonColors(containerColor=goalGreen)) {
                Text(if(busy) "Сохранение…" else "Сохранить")
            }
        },
        dismissButton={
            TextButton(enabled=!busy,onClick=onDismiss){Text("Отмена")}
        }
    )
}
