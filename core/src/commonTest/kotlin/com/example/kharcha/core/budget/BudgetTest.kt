package com.example.kharcha.core.budget

import com.example.kharcha.core.money.CurrencyCode
import com.example.kharcha.core.money.Money
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BudgetPeriodTest {

    @Test fun weeklyStartsOnConfiguredDay() {
        // 2026-11-04 is a Wednesday.
        val range = BudgetPeriod.Weekly(DayOfWeek.MONDAY).periodContaining(LocalDate(2026, 11, 4))
        assertEquals(LocalDate(2026, 11, 2), range.start)
        assertEquals(LocalDate(2026, 11, 9), range.endExclusive)

        val sunday = BudgetPeriod.Weekly(DayOfWeek.SUNDAY).periodContaining(LocalDate(2026, 11, 4))
        assertEquals(LocalDate(2026, 11, 1), sunday.start)
    }

    @Test fun biWeeklyAlignsToPaydayAnchor() {
        val period = BudgetPeriod.BiWeekly(anchor = LocalDate(2026, 1, 2))
        val range = period.periodContaining(LocalDate(2026, 1, 20))
        assertEquals(LocalDate(2026, 1, 16), range.start)
        assertEquals(LocalDate(2026, 1, 30), range.endExclusive)

        // Dates before the anchor still land in a correctly aligned period.
        val before = period.periodContaining(LocalDate(2025, 12, 25))
        assertEquals(LocalDate(2025, 12, 19), before.start)
    }

    @Test fun monthlyWithSalaryDayStart() {
        val period = BudgetPeriod.Monthly(startDay = 25)
        val range = period.periodContaining(LocalDate(2026, 11, 3))
        assertEquals(LocalDate(2026, 10, 25), range.start)
        assertEquals(LocalDate(2026, 11, 25), range.endExclusive)
        assertEquals(LocalDate(2026, 11, 24), range.lastDay)
    }

    @Test fun monthlyStartDayClampsInShortMonths() {
        val period = BudgetPeriod.Monthly(startDay = 31)
        val feb = period.periodContaining(LocalDate(2026, 3, 10))
        assertEquals(LocalDate(2026, 2, 28), feb.start)
        assertEquals(LocalDate(2026, 3, 31), feb.endExclusive)
    }

    @Test fun nextAndPreviousAreContiguous() {
        val period = BudgetPeriod.Monthly()
        val nov = period.periodContaining(LocalDate(2026, 11, 15))
        assertEquals(nov.endExclusive, period.next(nov).start)
        assertEquals(nov.start, period.previous(nov).endExclusive)
    }
}

class BudgetCalculatorTest {
    private val inr = CurrencyCode.INR
    private fun rs(major: Long) = Money.ofMajor(major, inr)
    private val weekly = BudgetPeriod.Weekly(DayOfWeek.MONDAY)
    private val start = LocalDate(2026, 11, 2) // Monday

    private fun statusAt(asOf: LocalDate, line: BudgetLine, spends: List<Spend>) =
        BudgetCalculator.statusFor(weekly, start, listOf(line), spends, asOf, inr).single()

    private val spends = listOf(
        Spend("food", LocalDate(2026, 11, 3), rs(700)),  // week 1: 700 of 1000
        Spend("food", LocalDate(2026, 11, 10), rs(1500)), // week 2: 1500
        Spend("food", LocalDate(2026, 11, 17), rs(200)),  // week 3: 200
        Spend("travel", LocalDate(2026, 11, 3), rs(9999)), // other category, ignored
    )

    @Test fun noRolloverStartsFresh() {
        val s = statusAt(LocalDate(2026, 11, 11), BudgetLine("food", rs(1000), RolloverMode.NONE), spends)
        assertEquals(rs(0), s.carriedIn)
        assertEquals(rs(-500), s.available)
        assertEquals(BudgetAlert.EXCEEDED, BudgetCalculator.alertFor(s))
    }

    @Test fun surplusOnlyCarriesLeftoversButForgivesOverspend() {
        val line = BudgetLine("food", rs(1000), RolloverMode.SURPLUS_ONLY)
        val week2 = statusAt(LocalDate(2026, 11, 11), line, spends)
        assertEquals(rs(300), week2.carriedIn)
        assertEquals(rs(-200), week2.available)

        val week3 = statusAt(LocalDate(2026, 11, 18), line, spends)
        assertEquals(rs(0), week3.carriedIn)
        assertEquals(rs(800), week3.available)
    }

    @Test fun fullRolloverActsLikeAnEnvelope() {
        val line = BudgetLine("food", rs(1000), RolloverMode.FULL)
        val week3 = statusAt(LocalDate(2026, 11, 18), line, spends)
        // week1 +300, week2 300+1000-1500 = -200, week3 -200+1000-200 = 600
        assertEquals(rs(-200), week3.carriedIn)
        assertEquals(rs(600), week3.available)
    }

    @Test fun approachingAlertAtEightyPercent() {
        val s = statusAt(LocalDate(2026, 11, 4), BudgetLine("food", rs(875), RolloverMode.NONE), spends)
        assertEquals(BudgetAlert.APPROACHING, BudgetCalculator.alertFor(s)) // 700 / 875 = 80%
    }
}
