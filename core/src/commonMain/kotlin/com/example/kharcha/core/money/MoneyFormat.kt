package com.example.kharcha.core.money

import kotlin.math.absoluteValue

enum class Grouping {
    /** 1,23,45,678 — lakh/crore grouping used in India. */
    INDIAN,

    /** 12,345,678 */
    WESTERN,
}

object MoneyFormat {

    /** Default grouping for a currency; INR uses lakh/crore grouping. */
    fun groupingFor(currency: CurrencyCode): Grouping =
        if (currency == CurrencyCode.INR) Grouping.INDIAN else Grouping.WESTERN

    /**
     * Formats e.g. ₹1,23,456.78 / -₹450 (trailing ".00" dropped when [dropZeroFraction]).
     */
    fun format(
        money: Money,
        grouping: Grouping = groupingFor(money.currency),
        withSymbol: Boolean = true,
        dropZeroFraction: Boolean = true,
    ): String {
        val digits = money.currency.minorDigits
        val absMinor = money.minor.absoluteValue
        val divisor = Money.pow10(digits)
        val whole = absMinor / divisor
        val frac = absMinor % divisor
        val grouped = group(whole.toString(), grouping)
        val fracText = when {
            digits == 0 -> ""
            dropZeroFraction && frac == 0L -> ""
            else -> "." + frac.toString().padStart(digits, '0')
        }
        val sign = if (money.minor < 0) "-" else ""
        val symbol = if (withSymbol) money.currency.symbol else ""
        return "$sign$symbol$grouped$fracText"
    }

    /**
     * Compact form for charts and widgets: ₹1.2L, ₹3.4Cr, ₹950, ₹12.5K.
     */
    fun compact(money: Money): String {
        val major = money.minor.absoluteValue.toDouble() / Money.pow10(money.currency.minorDigits)
        val sign = if (money.minor < 0) "-" else ""
        val symbol = money.currency.symbol
        val indian = groupingFor(money.currency) == Grouping.INDIAN
        val (value, suffix) = when {
            indian && major >= 1_00_00_000 -> major / 1_00_00_000 to "Cr"
            indian && major >= 1_00_000 -> major / 1_00_000 to "L"
            !indian && major >= 1_000_000_000 -> major / 1_000_000_000 to "B"
            !indian && major >= 1_000_000 -> major / 1_000_000 to "M"
            major >= 1_000 -> major / 1_000 to "K"
            else -> return "$sign$symbol${major.toLong()}"
        }
        val oneDecimal = (value * 10).toLong()
        val text = if (oneDecimal % 10 == 0L) "${oneDecimal / 10}" else "${oneDecimal / 10}.${oneDecimal % 10}"
        return "$sign$symbol$text$suffix"
    }

    private fun group(digits: String, grouping: Grouping): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3)
        val size = if (grouping == Grouping.INDIAN) 2 else 3
        val chunks = rest.reversed().chunked(size).map { it.reversed() }.reversed()
        return chunks.joinToString(",") + "," + last3
    }
}

fun Money.format(): String = MoneyFormat.format(this)
