package ru.smennik.app.data

import org.junit.Assert.*
import org.junit.Test
import ru.smennik.app.domain.*
import java.time.LocalDate

class PlannerBackupTest {
    @Test fun v6BackupRestoresAllDayTypes() {
        val day=LocalDate.of(2026,10,18)
        val jobs=listOf(Workplace(1,"Основная работа"),Workplace(2,"Подработка"))
        val shifts=listOf(
            WorkShift(day,DayType.VACATION,workplaceId=1),
            WorkShift(day,DayType.WORK,workplaceId=2),
            WorkShift(day.plusDays(1),DayType.SICK,workplaceId=1),
            WorkShift(day.plusDays(2),DayType.HOLIDAY,workplaceId=1)
        )
        val copy=ShiftBackup.decodeSnapshot(ShiftBackup.encode(shifts,
            emptyList(),emptyList(),jobs))
        assertEquals(shifts.sortedBy { it.date },copy.shifts)
        assertEquals(jobs,copy.workplaces)
    }
}
