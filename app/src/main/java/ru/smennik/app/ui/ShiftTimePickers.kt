package ru.smennik.app.ui

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import ru.smennik.app.domain.displayTime
import ru.smennik.app.domain.parseTime
import ru.smennik.app.domain.ShiftTimeRange
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val panel = Color(0xFF1B2229)
private val backdrop = Color(0xFF101418)
private val lime = Color(0xFFB7F34B)
private val muted = Color(0xFF9AA5AF)
private val ru = Locale("ru", "RU")

private data class FavoriteRange(val start: String, val end: String) {
    val label: String get() = "$start – $end"
}

/** Local personal shortcuts; do not touch the SQLite schedule or backups. */
private object TimeFavorites {
    private const val PREFS = "smennik_time_picker"
    private const val KEY = "favorite_intervals"
    fun load(context: Context): List<FavoriteRange> =
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .getString(KEY,"").orEmpty().split(";")
            .mapNotNull { entry ->
                val pair=entry.split("|")
                if (pair.size == 2 && parseTime(pair[0]) != null && parseTime(pair[1]) != null)
                    FavoriteRange(pair[0],pair[1]) else null
            }.distinct().takeLast(6)

    private fun store(context: Context, values: List<FavoriteRange>) {
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putString(KEY, values.takeLast(6).joinToString(";") { "${it.start}|${it.end}" })
            .apply()
    }

    fun add(context: Context, values: List<FavoriteRange>, range: FavoriteRange): List<FavoriteRange> {
        val updated=(values.filterNot { it==range }+range).takeLast(6)
        store(context,updated)
        return updated
    }

    fun remove(context: Context, values: List<FavoriteRange>, range: FavoriteRange): List<FavoriteRange> {
        val updated=values.filterNot { it==range }
        store(context,updated)
        return updated
    }
}

private val presets = listOf(
    Triple("День 08–20", "08:00", "20:00"),
    Triple("Ночь 20–08", "20:00", "08:00"),
    Triple("5/2 09–18", "09:00", "18:00"),
    Triple("08–17", "08:00", "17:00")
)

/** One compact field replaces two system time dialogs in every schedule editor. */
@Composable
fun ShiftTimeRangePickerField(
    start: String,
    end: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    hourlyKopecks: Long? = null,
    onRangeChange: (String, String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val currentStart = parseTime(start) ?: 480
    val currentEnd = parseTime(end) ?: 1200
    val range = ShiftTimeRange(currentStart, currentEnd)
    OutlinedButton(
        enabled = enabled,
        onClick = { open = true },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFF3E5146))
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ВРЕМЯ СМЕНЫ · ИЗМЕНИТЬ", color = muted,
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween, modifier=Modifier.fillMaxWidth()) {
                Text(start, color = lime, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("→", color = muted, fontSize = 21.sp)
                Text(end, color = lime, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Text("${range.durationMinutes / 60} ч ${range.durationMinutes % 60} мин" +
                if (range.crossesMidnight) " · до следующего дня" else "",
                color = muted, fontSize = 12.sp)
        }
    }
    if (open) ShiftTimeRangeDialog(
        initialStart=currentStart,
        initialEnd=currentEnd,
        hourlyKopecks=hourlyKopecks,
        onDismiss={open=false},
        onConfirm={first,final ->
            onRangeChange(displayTime(first),displayTime(final))
            open=false
        }
    )
}

/** In-app time selection with inertial wheels, snapping and shortcuts. */
@Composable
private fun ShiftTimeRangeDialog(
    initialStart: Int,
    initialEnd: Int,
    hourlyKopecks: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val context=LocalContext.current
    var start by remember { mutableIntStateOf(initialStart) }
    var end by remember { mutableIntStateOf(initialEnd) }
    var editStart by remember { mutableStateOf(true) }
    var wheelReset by remember { mutableIntStateOf(0) }
    var favorites by remember { mutableStateOf(TimeFavorites.load(context)) }
    val range=ShiftTimeRange(start,end)
    val sum=hourlyKopecks?.let { range.estimatedKopecks(it) }
    val scope=rememberCoroutineScope()

    fun preset(first: String,second: String) {
        start=parseTime(first) ?: start
        end=parseTime(second) ?: end
        wheelReset++
    }

    Dialog(onDismissRequest=onDismiss) {
        Surface(
            modifier=Modifier.fillMaxWidth().widthIn(max=420.dp),
            shape=RoundedCornerShape(28.dp),
            color=backdrop,
            tonalElevation=8.dp,
            border=BorderStroke(1.dp,Color(0xFF344435))
        ) {
            Column(
                modifier=Modifier.verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement=Arrangement.spacedBy(11.dp)
            ) {
                Text("ВРЕМЯ СМЕНЫ",color=lime,fontSize=13.sp,
                    letterSpacing=2.sp,fontWeight=FontWeight.Bold)
                Text("Выбери начало и окончание",color=Color.White,
                    fontSize=20.sp,fontWeight=FontWeight.Bold)

                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TimeSegmentCard("НАЧАЛО",start,editStart,
                        Modifier.weight(1f)) { editStart=true }
                    TimeSegmentCard("ОКОНЧАНИЕ",end,!editStart,
                        Modifier.weight(1f)) { editStart=false }
                }

                // The wheels are keyed by selected segment and preset selection.
                // Selecting "Начало" or "Окончание" always shows its own time.
                key(editStart,wheelReset) {
                    val selection = if(editStart) start else end
                    Row(modifier=Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        TimeWheel("ЧАСЫ",24,selection / 60,
                            Modifier.weight(1f)) { hour ->
                            if(editStart) start=hour*60 + start%60
                            else end=hour*60 + end%60
                        }
                        Text(":",color=lime,fontSize=35.sp,fontWeight=FontWeight.Bold,
                            modifier=Modifier.align(Alignment.CenterVertically))
                        TimeWheel("МИНУТЫ",60,selection % 60,
                            Modifier.weight(1f)) { minute ->
                            if(editStart) start=start/60*60 + minute
                            else end=end/60*60 + minute
                        }
                    }
                }

                Surface(color=panel,shape=RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(13.dp),
                        verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("ДЛИТЕЛЬНОСТЬ",fontSize=11.sp,color=muted)
                        Text("${range.durationMinutes / 60} ч ${range.durationMinutes % 60} мин" +
                            if(range.crossesMidnight) " · следующий день" else "",
                            fontSize=18.sp,fontWeight=FontWeight.Bold,color=Color.White)
                        if(sum!=null) Text(
                            "Прогноз: ${NumberFormat.getNumberInstance(ru).format(sum/100.0)} ₽",
                            color=lime,fontSize=13.sp)
                    }
                }

                Text("БЫСТРЫЕ ИНТЕРВАЛЫ",color=muted,fontSize=11.sp,
                    fontWeight=FontWeight.Bold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    presets.forEach { (name,first,last) ->
                        AssistChip(
                            onClick={preset(first,last)},
                            label={Text(name,fontSize=12.sp)}
                        )
                    }
                }

                Row(verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.SpaceBetween,
                    modifier=Modifier.fillMaxWidth()) {
                    Text("МОИ ШАБЛОНЫ",color=muted,fontSize=11.sp,
                        fontWeight=FontWeight.Bold)
                    TextButton(onClick={
                        favorites=TimeFavorites.add(context,favorites,
                            FavoriteRange(displayTime(start),displayTime(end)))
                    }) { Text("☆ Запомнить",color=lime,fontSize=12.sp) }
                }
                if(favorites.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        favorites.forEach { rangeItem ->
                            AssistChip(onClick={preset(rangeItem.start,rangeItem.end)},
                                label={Text(rangeItem.label,fontSize=11.sp)})
                            TextButton(onClick={
                                favorites=TimeFavorites.remove(context,favorites,rangeItem)
                            }) { Text("×",color=muted,fontSize=16.sp) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(onClick=onDismiss,modifier=Modifier.weight(1f)) {
                        Text("Отмена")
                    }
                    Button(
                        onClick={onConfirm(start,end)},
                        modifier=Modifier.weight(1.3f),
                        colors=ButtonDefaults.buttonColors(containerColor=lime,
                            contentColor=backdrop)
                    ) { Text("Применить",fontWeight=FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun TimeSegmentCard(
    label: String,
    minutes: Int,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val shape=RoundedCornerShape(14.dp)
    Column(
        modifier=modifier
            .border(BorderStroke(1.dp,if(selected)lime else Color(0xFF3D474F)),shape)
            .background(if(selected) Color(0xFF253623) else panel,shape)
            .clickable(onClick=onClick).padding(vertical=13.dp,horizontal=10.dp),
        horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.spacedBy(6.dp)
    ) {
        Text(label,color=if(selected)lime else muted,fontSize=10.sp,
            fontWeight=FontWeight.Bold)
        Text(displayTime(minutes),color=Color.White,
            fontSize=23.sp,fontWeight=FontWeight.Bold)
    }
}

/**
 * A five-row wheel, with the center item selected. Center snapping preserves
 * momentum; a touch on any visible number also animates to it.
 */
@Composable
private fun TimeWheel(
    title: String,
    count: Int,
    initiallySelected: Int,
    modifier: Modifier,
    onSelected: (Int) -> Unit
) {
    val listState=rememberLazyListState(initialFirstVisibleItemIndex=initiallySelected)
    val scope=rememberCoroutineScope()
    val centered by remember(listState) {
        derivedStateOf {
            val layout=listState.layoutInfo
            val center=(layout.viewportStartOffset + layout.viewportEndOffset)/2
            layout.visibleItemsInfo.minByOrNull {
                abs((it.offset+it.size/2)-center)
            }?.index?.coerceIn(0,count-1) ?: initiallySelected
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            if(listState.isScrollInProgress) null else centered
        }.filterNotNull().distinctUntilChanged().collect { onSelected(it) }
    }

    Column(modifier=modifier,horizontalAlignment=Alignment.CenterHorizontally) {
        Text(title,color=muted,fontSize=11.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(210.dp),
            contentAlignment=Alignment.Center) {
            LazyColumn(
                modifier=Modifier.fillMaxWidth().height(210.dp),
                state=listState,
                contentPadding=PaddingValues(vertical=84.dp),
                flingBehavior=rememberSnapFlingBehavior(lazyListState=listState)
            ) {
                items(count) { value ->
                    val selected=centered==value
                    Box(Modifier.fillMaxWidth().height(42.dp)
                        .clickable {
                            scope.launch { listState.animateScrollToItem(value) }
                        }.background(if(selected)Color(0xFF2B3F28) else Color.Transparent,
                            RoundedCornerShape(11.dp)),
                        contentAlignment=Alignment.Center) {
                        Text("%02d".format(value),
                            color=if(selected)lime else muted,
                            fontSize=if(selected)26.sp else 19.sp,
                            fontWeight=if(selected)FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

/** History editor retains the native DATE picker, but time uses the custom wheel. */
@Composable
fun ShiftDateTimePickerField(
    label: String,
    dateTimeText: String,
    enabled: Boolean,
    onDateTimeChange: (String) -> Unit
) {
    val context=LocalContext.current
    val format=remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }
    var dateForTime by remember { mutableStateOf<LocalDate?>(null) }
    val value=runCatching { LocalDateTime.parse(dateTimeText,format) }
        .getOrElse { LocalDateTime.now().withSecond(0).withNano(0) }
    OutlinedButton(
        enabled=enabled,
        modifier=Modifier.fillMaxWidth(),
        onClick={
            DatePickerDialog(
                context,{ _,year,zeroMonth,day ->
                    dateForTime=LocalDate.of(year,zeroMonth+1,day)
                },value.year,value.monthValue-1,value.dayOfMonth
            ).show()
        }
    ) {
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(label,fontSize=12.sp)
            Text(dateTimeText,fontWeight=FontWeight.SemiBold,fontSize=16.sp)
            Text("Выбрать дату и время",fontSize=11.sp)
        }
    }
    dateForTime?.let { day ->
        SingleTimeWheelDialog(
            label=label,
            initialMinutes=value.hour*60 + value.minute,
            onDismiss={dateForTime=null},
            onConfirm={hourMinutes ->
                val dateTime=day.atStartOfDay().plusMinutes(hourMinutes.toLong())
                onDateTimeChange(dateTime.format(format))
                dateForTime=null
            }
        )
    }
}

@Composable
private fun SingleTimeWheelDialog(
    label: String,
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var current by remember { mutableIntStateOf(initialMinutes) }
    Dialog(onDismissRequest=onDismiss) {
        Surface(color=backdrop,shape=RoundedCornerShape(24.dp),
            modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text(label,color=lime,fontWeight=FontWeight.Bold,fontSize=18.sp)
                Text(displayTime(current),fontSize=29.sp,fontWeight=FontWeight.Bold,
                    color=Color.White,textAlign=TextAlign.Center,
                    modifier=Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    TimeWheel("ЧАСЫ",24,current/60,Modifier.weight(1f)) {
                        current=it*60 + current%60
                    }
                    Text(":",color=lime,fontSize=30.sp,
                        modifier=Modifier.align(Alignment.CenterVertically))
                    TimeWheel("МИНУТЫ",60,current%60,Modifier.weight(1f)) {
                        current=current/60*60 + it
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick=onDismiss,modifier=Modifier.weight(1f)) {
                        Text("Отмена")
                    }
                    Button(
                        onClick={onConfirm(current)},
                        modifier=Modifier.weight(1f),
                        colors=ButtonDefaults.buttonColors(containerColor=lime,
                            contentColor=backdrop)
                    ) { Text("Готово") }
                }
            }
        }
    }
}
