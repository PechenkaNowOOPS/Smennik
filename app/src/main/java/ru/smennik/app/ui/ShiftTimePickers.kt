package ru.smennik.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.smennik.app.domain.displayTime
import ru.smennik.app.domain.parseTime
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Native Android 24-hour picker. The schedule editor no longer opens a keyboard
 * just to enter HH:mm. All values remain compatible with the existing domain model.
 */
@Composable
fun ShiftTimePickerField(
    label: String,
    time: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onTimeChange: (String) -> Unit
) {
    val context = LocalContext.current
    OutlinedButton(
        enabled = enabled,
        onClick = {
            val parsed = parseTime(time) ?: 8 * 60
            TimePickerDialog(
                context,
                { _, hour, minute -> onTimeChange(displayTime(hour * 60 + minute)) },
                parsed / 60, parsed % 60,
                true
            ).show()
        },
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontSize = 12.sp)
            Text(time, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Нажми для выбора", fontSize = 10.sp)
        }
    }
}

/** Two native pickers let users correct overnight sessions without ISO datetime typing. */
@Composable
fun ShiftDateTimePickerField(
    label: String,
    dateTimeText: String,
    enabled: Boolean,
    onDateTimeChange: (String) -> Unit
) {
    val context = LocalContext.current
    val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    OutlinedButton(
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            val value = runCatching { LocalDateTime.parse(dateTimeText,format) }
                .getOrElse { LocalDateTime.now().withSecond(0).withNano(0) }
            DatePickerDialog(
                context,
                { _, year, zeroBasedMonth, day ->
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            onDateTimeChange(
                                LocalDateTime.of(year, zeroBasedMonth + 1, day, hour, minute)
                                    .format(format)
                            )
                        },
                        value.hour, value.minute, true
                    ).show()
                },
                value.year, value.monthValue - 1, value.dayOfMonth
            ).show()
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontSize = 12.sp)
            Text(dateTimeText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Выбрать дату и время", fontSize = 11.sp)
        }
    }
}


// One tap selects both ends of a common schedule; overnight preset crosses midnight.
@Composable
fun ShiftTimePresets(
    enabled: Boolean,
    onChoose: (String, String) -> Unit
) {
    val presets = listOf(
        Triple("День 08–20", "08:00", "20:00"),
        Triple("Ночь 20–08", "20:00", "08:00"),
        Triple("5/2 09–18", "09:00", "18:00"),
        Triple("08–17", "08:00", "17:00")
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { (label, begin, finish) ->
            AssistChip(
                enabled = enabled,
                onClick = { onChoose(begin,finish) },
                label = { Text(label, fontSize = 12.sp) }
            )
        }
    }
}
