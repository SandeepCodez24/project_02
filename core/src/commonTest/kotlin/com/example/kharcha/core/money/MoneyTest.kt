package com.example.kharcha.core.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MoneyTest {
    private val inr = CurrencyCode.INR

    @Test fun parsesIndianGroupedAmounts() {
        assertEquals(Money(12345678, inr), Money.parse("1,23,456.78", inr))
        assertEquals(Money(45000, inr), Money.parse("Rs.450", inr))
        assertEquals(Money(45050, inr), Money.parse("₹ 450.5", inr))
        assertEquals(Money(99900, inr), Money.parse("INR 999.00", inr))
        assertEquals(Money(-25000, inr), Money.parse("-250", inr))
    }

    @Test fun rejectsInvalidAmounts() {
        assertNull(Money.parse("", inr))
        assertNull(Money.parse("abc", inr))
        assertNull(Money.parse("1.234", inr)) // too many decimals for INR
        assertNull(Money.parse("1.2.3", inr))
    }

    @Test fun zeroDecimalCurrencies() {
        val jpy = CurrencyCode("JPY")
        assertEquals(Money(500, jpy), Money.parse("500", jpy))
        assertEquals("500", Money(500, jpy).toPlainString())
    }

    @Test fun arithmeticRequiresSameCurrency() {
        assertEquals(Money(300, inr), Money(100, inr) + Money(200, inr))
        assertFailsWith<IllegalArgumentException> { Money(100, inr) + Money(100, CurrencyCode.USD) }
    }

    @Test fun overflowFailsLoudly() {
        assertFailsWith<ArithmeticException> { Money(Long.MAX_VALUE, inr) + Money(1, inr) }
    }

    @Test fun splitEvenlyKeepsEveryPaisa() {
        val parts = Money(10000, inr).splitEvenly(3)
        assertEquals(listOf(3334L, 3333L, 3333L), parts.map { it.minor })
        assertEquals(Money(10000, inr), parts.sum(inr))

        val negative = Money(-100, inr).splitEvenly(3)
        assertEquals(listOf(-34L, -33L, -33L), negative.map { it.minor })
    }

    @Test fun formatsWithLakhCroreGrouping() {
        assertEquals("₹1,23,45,678.90", Money(1234567890, inr).format())
        assertEquals("₹450", Money(45000, inr).format())
        assertEquals("-₹999.50", Money(-99950, inr).format())
        assertEquals("$1,234,567.89", MoneyFormat.format(Money(123456789, CurrencyCode.USD)))
    }

    @Test fun compactFormat() {
        assertEquals("₹950", MoneyFormat.compact(Money(95000, inr)))
        assertEquals("₹12.5K", MoneyFormat.compact(Money(1250000, inr)))
        assertEquals("₹1.2L", MoneyFormat.compact(Money(12500000, inr)))
        assertEquals("₹3Cr", MoneyFormat.compact(Money(3000000000, inr)))
    }
}
