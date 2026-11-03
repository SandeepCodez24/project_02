package com.example.kharcha.core.budget

import com.example.kharcha.core.money.CurrencyCode
import com.example.kharcha.core.money.Money
import kotlinx.datetime.LocalDate

enum class RolloverMode {
    /** Every period starts fresh. */
    NONE,

    /** Unspent money carries forward; overspending does not reduce the next period. */
    SURPLUS_ONLY,

    /** Envelope / sinking-fund behaviour: both leftovers and overspends carry forward. */
    FULL,
}

data class BudgetLine(
    val categoryId: String,
    /** Positive amount allocated each period. */
    val allocation: Money,
    val rollover: RolloverMode = RolloverMode.NONE,
)

/** Net spending for one category on one day. Positive = money spent (refunds reduce it). */
data class Spend(val categoryId: String, val date: LocalDate, val spent: Money)

data class BudgetStatus(
    val categoryId: String,
    val period: DateRange,
    val carriedIn: Money,
    val allocated: Money,
    val spent: Money,
) {
    val available: Money get() = carriedIn + allocated - spent
    val isOver: Boolean get() = available.isNegative

    /** Fraction of the available pool used, for progress bars and 80%/100% alerts. */
    val usedFraction: Double
        get() {
            val pool = (carriedIn + allocated).minor
            return if (pool <= 0) (if (spent.minor > 0) Double.POSITIVE_INFINITY else 0.0)
            else spent.minor.toDouble() / pool
        }
}

enum class BudgetAlert { NONE, APPROACHING, EXCEEDED }

object BudgetCalculator {

    /**
     * Computes each line's status for the period containing [asOf], replaying every
     * period since [budgetStart] so that rollover balances are exact.
     * [spends] may contain any dates; those outside the replayed window are ignored.
     */
    fun statusFor(
        period: BudgetPeriod,
        budgetStart: LocalDate,
        lines: List<BudgetLine>,
        spends: List<Spend>,
        asOf: LocalDate,
        currency: CurrencyCode,
    ): List<BudgetStatus> {
        require(asOf >= budgetStart) { "asOf ($asOf) is before the budget started ($budgetStart)" }
        val byCategory = spends.groupBy { it.categoryId }
        val target = period.periodContaining(asOf)

        return lines.map { line ->
            statusForLine(period, budgetStart, line, byCategory[line.categoryId].orEmpty(), target, currency)
        }
    }

    private fun statusForLine(
        period: BudgetPeriod,
        budgetStart: LocalDate,
        line: BudgetLine,
        spends: List<Spend>,
        target: DateRange,
        currency: CurrencyCode,
    ): BudgetStatus {
        var range = period.periodContaining(budgetStart)
        var carry = Money.zero(currency)
        while (true) {
            val status = BudgetStatus(line.categoryId, range, carry, line.allocation, spentIn(spends, range, currency))
            if (range == target) return status
            carry = when (line.rollover) {
                RolloverMode.NONE -> Money.zero(currency)
                RolloverMode.SURPLUS_ONLY -> if (status.available.isNegative) Money.zero(currency) else status.available
                RolloverMode.FULL -> status.available
            }
            range = period.next(range)
        }
    }

    fun alertFor(status: BudgetStatus, approachingAt: Double = 0.8): BudgetAlert = when {
        status.usedFraction > 1.0 -> BudgetAlert.EXCEEDED
        status.usedFraction >= approachingAt -> BudgetAlert.APPROACHING
        else -> BudgetAlert.NONE
    }

    private fun spentIn(spends: List<Spend>, range: DateRange, currency: CurrencyCode): Money =
        spends.filter { it.date in range }.fold(Money.zero(currency)) { acc, s -> acc + s.spent }
}
