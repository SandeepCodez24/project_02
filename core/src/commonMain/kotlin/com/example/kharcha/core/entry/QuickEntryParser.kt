package com.example.kharcha.core.entry

import com.example.kharcha.core.money.CurrencyCode
import com.example.kharcha.core.money.Money
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

data class QuickEntry(
    /** Positive amount. */
    val amount: Money,
    val isIncome: Boolean,
    /** What's left after removing the amount and filler words, e.g. "lunch swiggy". */
    val description: String,
    val date: LocalDate,
)

/**
 * Parses short typed or spoken entries, offline, without an LLM:
 *   "450 lunch swiggy", "spent 1.2k on uber yesterday", "₹2,500 rent",
 *   "received 50000 salary", "+300 cashback", "chai 20".
 * Returns null if there is no usable amount. Anything this can't handle can later
 * fall back to the (opt-in) LLM parser.
 */
class QuickEntryParser(private val currency: CurrencyCode = CurrencyCode.INR) {

    fun parse(input: String, today: LocalDate): QuickEntry? {
        var text = " ${input.trim()} "
        val match = pickAmount(text) ?: return null
        val amount = toMoney(match.groupValues[2], match.groupValues[3]) ?: return null
        if (amount.isZero) return null
        val explicitPlus = match.groupValues[1] == "+"
        text = text.removeRange(match.range).let { " $it " }

        val lower = text.lowercase()
        val isIncome = explicitPlus || incomeWords.any { Regex("""\b$it\b""").containsMatchIn(lower) }

        var date = today
        when {
            Regex("""\b(?:day before yesterday|parso)\b""").containsMatchIn(lower) -> date = today.minus(2, DateTimeUnit.DAY)
            Regex("""\b(?:yesterday|kal)\b""").containsMatchIn(lower) -> date = today.minus(1, DateTimeUnit.DAY)
        }

        val description = lower
            .replace(Regex("""\b(?:day before yesterday|yesterday|today|parso|kal|aaj)\b"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() && it !in fillerWords }
            .joinToString(" ")

        return QuickEntry(amount.abs(), isIncome, description, date)
    }

    /** Exact integer conversion of "1.25" + "k" → 125000 paise; no floating point. */
    private fun toMoney(number: String, suffix: String): Money? {
        val multiplier = when (suffix.lowercase()) {
            "k" -> 1_000L
            "l", "lac", "lakh", "lakhs" -> 1_00_000L
            "cr", "crore" -> 1_00_00_000L
            else -> 1L
        }
        val cleaned = number.replace(",", "")
        val whole = cleaned.substringBefore('.').toLongOrNull() ?: return null
        val frac = cleaned.substringAfter('.', missingDelimiterValue = "")
        if (frac.length > 6) return null
        if (multiplier == 1L && frac.length > currency.minorDigits) return null
        val minorPerUnit = Money.ofMajor(1, currency).minor * multiplier
        val fracMinor = if (frac.isEmpty()) 0L else {
            var scale = 1L
            repeat(frac.length) { scale *= 10 }
            (frac.toLong() * minorPerUnit + scale / 2) / scale
        }
        return Money(whole * minorPerUnit + fracMinor, currency)
    }

    /** Prefer an amount with ₹/Rs or a k/L suffix; otherwise the largest number ("2 samosas 40" → 40). */
    private fun pickAmount(text: String): MatchResult? {
        val all = amountPattern.findAll(text).toList()
        if (all.isEmpty()) return null
        all.firstOrNull { m -> m.groupValues[3].isNotEmpty() || currencyMarker.containsMatchIn(m.value) }?.let { return it }
        return all.maxByOrNull { it.groupValues[2].replace(",", "").toDoubleOrNull() ?: 0.0 }
    }

    private companion object {
        val amountPattern = Regex(
            """(?<![\w.])(\+)?(?:₹|rs\.?|inr)?\s*(\d[\d,]*(?:\.\d+)?)\s*(k|l|lac|lakhs?|cr|crore)?(?![\w.])""",
            RegexOption.IGNORE_CASE,
        )
        val currencyMarker = Regex("""₹|\brs\b|\brs\.|\binr\b""", RegexOption.IGNORE_CASE)
        val incomeWords = listOf("received", "got", "salary", "income", "refund", "cashback", "credited", "earned")
        val fillerWords = setOf(
            "spent", "paid", "pay", "for", "on", "at", "to", "the", "a", "an", "rs", "rs.", "inr", "₹",
            "rupees", "bucks", "received", "got", "from", "via", "using", "with", "of", "in",
        )
    }
}
