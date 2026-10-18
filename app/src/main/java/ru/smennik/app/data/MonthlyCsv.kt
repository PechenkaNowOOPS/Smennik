package ru.smennik.app.data

import ru.smennik.app.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** A self-contained, UTF-8 semicolon-separated report, suitable for Excel and LibreOffice. */
object MonthlyCsv {
    private fun cell(value: String): String {
        val sanitized = if (value.startsWith("=") || value.startsWith("+") ||
            value.startsWith("-") || value.startsWith("@")) "'$value" else value
        return "\"" + sanitized.replace("\"", "\"\"") + "\""
    }
    private fun row(vararg values: String): String = values.joinToString(";") { cell(it) } + "\r\n"
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun generate(
        workplace: Workplace, month: YearMonth,
        sessions: List<WorkSession>, payments: List<WagePayment>, nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val actual = actualMonthSummary(sessions, payments, month, nowMs, workplace, zone)
        val result = StringBuilder()
        result.append(row("СМЕННИК — ОТЧЁТ", workplace.name, month.toString()))
        result.append(row("Показатель","Сумма, ₽"))
        fun money(value: Long) = "%d,%02d".format(value / 100, kotlin.math.abs(value % 100))
        result.append(row("Основное начисление",money(actual.baseKopecks)))
        result.append(row("Ночная надбавка",money(actual.nightBonusKopecks)))
        result.append(row("Переработка",money(actual.overtimeBonusKopecks)))
        result.append(row("Премии",money(actual.manualBonusesKopecks)))
        result.append(row("Удержания",money(actual.deductionsKopecks)))
        result.append(row("Всего начислено",money(actual.earnedKopecks)))
        result.append(row("Выплачено",money(actual.paidKopecks)))
        result.append(row("Остаток",money(actual.outstandingKopecks)))
        result.append(row(""))
        result.append(row("СМЕНЫ"))
        result.append(row("Дата","Начало","Окончание","Перерыв (мин)","Отработано (мин)","Ставка, ₽/ч"))
        sessions.asSequence().filter { YearMonth.from(it.date) == month }
            .sortedBy { it.startedMs }.forEach { s ->
                val finish = s.endedMs?.let { Instant.ofEpochMilli(it).atZone(zone).format(dateTime) }
                    ?: "В процессе"
                result.append(row(
                    s.date.toString(),
                    Instant.ofEpochMilli(s.startedMs).atZone(zone).format(dateTime),
                    finish,
                    (s.totalPausedMs / 60_000).toString(),
                    s.workedMinutes(nowMs).toString(),
                    money(s.hourlyKopecks)
                ))
            }
        result.append(row(""))
        result.append(row("ОПЕРАЦИИ"))
        result.append(row("Дата","Тип","Сумма, ₽"))
        payments.asSequence().filter { YearMonth.from(it.date) == month }
            .sortedBy { it.date }.forEach { p ->
                result.append(row(p.date.toString(),p.type.name,money(p.amountKopecks)))
            }
        return result.toString()
    }
}
