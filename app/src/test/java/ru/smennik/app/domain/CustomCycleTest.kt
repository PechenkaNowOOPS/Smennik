package ru.smennik.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CustomCycleTest {
    @Test fun customPatternRepeatsForwardAndBackward() {
        val anchor = LocalDate.of(2026, 10, 18)
        val cycle = CustomCycle("РРВВРВ")
        assertEquals(listOf(true, true, false, false, true, false, true, true),
            (0L..7L).map { cycle.isWorkDay(anchor, anchor.plusDays(it)) })
        assertFalse(cycle.isWorkDay(anchor, anchor.minusDays(1)))
        assertTrue(cycle.isWorkDay(anchor, anchor.minusDays(2)))
    }

    @Test fun emptyOrSingleKindRejected() {
        listOf("", "Р", "ВВВ", "РОВ", "Р".repeat(61)).forEach { invalid ->
            try {
                CustomCycle(invalid)
                fail("Cycle should be rejected: $invalid")
            } catch (expected: IllegalArgumentException) {}
        }
    }

    @Test fun separateWorkplacesMayHoldSameDate() {
        val day = LocalDate.of(2026, 10, 18)
        val a = WorkShift(day, DayType.WORK, workplaceId = 1)
        val b = WorkShift(day, DayType.WORK, workplaceId = 2)
        assertNotEquals(a, b)
        assertEquals(2, listOf(a,b).map { it.workplaceId to it.date }.toSet().size)
    }
}
