package com.example.kharcha.core.budget

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Half-open date range: [start, endExclusive). */
data class DateRange(val start: LocalDate, val endExclusive: LocalDate) {
    init {
        require(start < endExclusive) { "Empty range: $start..$endExclusive" }
    }

    operator fun contains(date: LocalDate): Boolean = date >= start && date < endExclusive
    val lastDay: LocalDate get() = endExclusive.minus(1, DateTimeUnit.DAY)
    val lengthInDays: Int get() = start.daysUntil(endExclusive)
}

/**
 * How a budget repeats. Weekly and bi-weekly exist because many people are paid on
 * those cycles; monthly supports a custom start day (e.g. salary on the 25th).
 */
sealed interface BudgetPeriod {

    /** The period that contains [date]. */
    fun periodContaining(date: LocalDate): DateRange

    fun next(range: DateRange): DateRange = periodContaining(range.endExclusive)
    fun previous(range: DateRange): DateRange = periodContaining(range.start.minus(1, DateTimeUnit.DAY))

    data class Weekly(val startsOn: DayOfWeek = DayOfWeek.MONDAY) : BudgetPeriod {
        override fun periodContaining(date: LocalDate): DateRange {
            val back = (date.dayOfWeek.isoDayNumber - startsOn.isoDayNumber + 7) % 7
            val start = date.minus(back, DateTimeUnit.DAY)
            return DateRange(start, start.plus(7, DateTimeUnit.DAY))
        }
    }

    /** Every 14 days counting from [anchor] (any known payday). Works for dates before the anchor too. */
    data class BiWeekly(val anchor: LocalDate) : BudgetPeriod {
        override fun periodContaining(date: LocalDate): DateRange {
            val days = anchor.daysUntil(date)
            val index = floorDiv(days, 14)
            val start = anchor.plus(index * 14, DateTimeUnit.DAY)
            return DateRange(start, start.plus(14, DateTimeUnit.DAY))
        }
    }

    /**
     * Monthly, starting on [startDay] (1..31). For short months the period starts on the
     * month's last day instead (startDay = 31 → Feb 28/29).
     */
    data class Monthly(val startDay: Int = 1) : BudgetPeriod {
        init {
            require(startDay in 1..31) { "startDay must be 1..31" }
        }

        override fun periodContaining(date: LocalDate): DateRange {
            val thisMonthStart = startIn(date.year, date.month.ordinal + 1)
            val start = if (date >= thisMonthStart) {
                thisMonthStart
            } else {
                val prev = LocalDate(date.year, date.month, 1).minus(1, DateTimeUnit.MONTH)
                startIn(prev.year, prev.month.ordinal + 1)
            }
            val nextMonth = LocalDate(start.year, start.month, 1).plus(1, DateTimeUnit.MONTH)
            return DateRange(start, startIn(nextMonth.year, nextMonth.month.ordinal + 1))
        }

        private fun startIn(year: Int, month: Int): LocalDate {
            val first = LocalDate(year, month, 1)
            val daysInMonth = first.daysUntil(first.plus(1, DateTimeUnit.MONTH))
            return LocalDate(year, month, minOf(startDay, daysInMonth))
        }
    }
}

private fun floorDiv(a: Int, b: Int): Int {
    val q = a / b
    return if ((a % b != 0) && ((a < 0) != (b < 0))) q - 1 else q
}
