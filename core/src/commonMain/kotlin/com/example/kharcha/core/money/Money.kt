package com.example.kharcha.core.money

import kotlin.jvm.JvmInline
import kotlin.math.absoluteValue

/** ISO-4217 currency code, e.g. "INR". */
@JvmInline
value class CurrencyCode(val code: String) {
    init {
        require(code.length == 3 && code.all { it in 'A'..'Z' }) { "Invalid currency code: $code" }
    }

    /** Number of minor-unit digits (paise for INR = 2). */
    val minorDigits: Int
        get() = when (code) {
            "JPY", "KRW", "VND", "IDR", "CLP", "ISK" -> 0
            "BHD", "KWD", "OMR", "JOD", "TND" -> 3
            else -> 2
        }

    val symbol: String
        get() = when (code) {
            "INR" -> "₹"
            "USD" -> "$"
            "EUR" -> "€"
            "GBP" -> "£"
            "JPY" -> "¥"
            "AED" -> "AED "
            else -> "$code "
        }

    companion object {
        val INR = CurrencyCode("INR")
        val USD = CurrencyCode("USD")
    }
}

/**
 * An amount of money stored as an integer number of minor units (paise, cents).
 * Never use floating point for money. Sign convention across the app:
 * negative = money leaving an account (expense), positive = money arriving.
 */
data class Money(val minor: Long, val currency: CurrencyCode) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(addExact(minor, checked(other).minor), currency)
    operator fun minus(other: Money): Money = Money(subtractExact(minor, checked(other).minor), currency)
    operator fun unaryMinus(): Money = Money(negateExact(minor), currency)
    operator fun times(factor: Int): Money = Money(multiplyExact(minor, factor.toLong()), currency)

    override fun compareTo(other: Money): Int = minor.compareTo(checked(other).minor)

    val isZero: Boolean get() = minor == 0L
    val isNegative: Boolean get() = minor < 0L
    fun abs(): Money = if (minor < 0) -this else this

    /**
     * Splits this amount into [parts] near-equal shares that sum exactly to this amount
     * (the remainder paise go to the first shares). Used for "split equally".
     */
    fun splitEvenly(parts: Int): List<Money> {
        require(parts > 0) { "parts must be > 0" }
        val base = minor / parts
        val remainder = (minor % parts).absoluteValue
        val step = if (minor < 0) -1 else 1
        return List(parts) { i -> Money(base + if (i < remainder) step else 0, currency) }
    }

    /** Plain decimal representation, e.g. "-1234.50". */
    fun toPlainString(): String {
        val digits = currency.minorDigits
        val absMinor = minor.absoluteValue
        val sign = if (minor < 0) "-" else ""
        if (digits == 0) return "$sign$absMinor"
        val divisor = pow10(digits)
        val whole = absMinor / divisor
        val frac = (absMinor % divisor).toString().padStart(digits, '0')
        return "$sign$whole.$frac"
    }

    private fun checked(other: Money): Money {
        require(other.currency == currency) { "Currency mismatch: $currency vs ${other.currency}" }
        return other
    }

    override fun toString(): String = "${toPlainString()} ${currency.code}"

    companion object {
        fun zero(currency: CurrencyCode) = Money(0, currency)

        fun ofMajor(major: Long, currency: CurrencyCode) =
            Money(multiplyExact(major, pow10(currency.minorDigits)), currency)

        /**
         * Parses user / SMS text such as "1,23,456.78", "Rs.450", "450.5" into Money.
         * Grouping commas (Indian or western) and currency prefixes are ignored.
         * Returns null when the text has no valid number or too many decimals.
         */
        fun parse(text: String, currency: CurrencyCode): Money? {
            val cleaned = text.trim()
                .removePrefix("₹").removePrefix("Rs.").removePrefix("Rs").removePrefix("INR")
                .trim()
                .replace(",", "")
                .replace(" ", "")
            if (cleaned.isEmpty()) return null
            val negative = cleaned.startsWith("-")
            val unsigned = cleaned.removePrefix("-").removePrefix("+")
            val match = Regex("""^(\d+)(?:\.(\d*))?$""").matchEntire(unsigned) ?: return null
            val wholePart = match.groupValues[1]
            val fracPart = match.groupValues[2]
            val digits = currency.minorDigits
            if (fracPart.length > digits) return null
            val whole = wholePart.toLongOrNull() ?: return null
            val frac = if (digits == 0) 0L else fracPart.padEnd(digits, '0').toLong()
            val minor = addExact(multiplyExact(whole, pow10(digits)), frac)
            return Money(if (negative) -minor else minor, currency)
        }

        internal fun pow10(n: Int): Long {
            var r = 1L
            repeat(n) { r *= 10 }
            return r
        }
    }
}

fun Iterable<Money>.sum(currency: CurrencyCode): Money =
    fold(Money.zero(currency)) { acc, m -> acc + m }
