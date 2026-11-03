package com.example.kharcha.core.entry

import com.example.kharcha.core.money.CurrencyCode
import com.example.kharcha.core.money.Money
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickEntryParserTest {
    private val parser = QuickEntryParser()
    private val today = LocalDate(2026, 11, 3)
    private fun rs(paise: Long) = Money(paise, CurrencyCode.INR)

    @Test fun amountFirst() {
        val e = parser.parse("450 lunch swiggy", today)!!
        assertEquals(rs(45000), e.amount)
        assertEquals("lunch swiggy", e.description)
        assertEquals(false, e.isIncome)
        assertEquals(today, e.date)
    }

    @Test fun amountLastAndCurrencySymbols() {
        assertEquals(rs(2000), parser.parse("chai 20", today)!!.amount)
        val rent = parser.parse("₹2,500 rent", today)!!
        assertEquals(rs(250000), rent.amount)
        assertEquals("rent", rent.description)
        assertEquals(rs(9950), parser.parse("Rs. 99.50 for parking", today)!!.amount)
    }

    @Test fun suffixesAndDates() {
        val e = parser.parse("spent 1.2k on uber yesterday", today)!!
        assertEquals(rs(120000), e.amount)
        assertEquals("uber", e.description)
        assertEquals(LocalDate(2026, 11, 2), e.date)
        assertEquals(rs(1_50_000_00), parser.parse("1.5L car service", today)!!.amount)
        assertEquals(rs(125000), parser.parse("1.25k groceries", today)!!.amount)
    }

    @Test fun incomeDetection() {
        assertEquals(true, parser.parse("received 50000 salary", today)!!.isIncome)
        assertEquals(true, parser.parse("+300 cashback", today)!!.isIncome)
        assertEquals("salary", parser.parse("received 50000 salary", today)!!.description)
    }

    @Test fun picksTheLikelyAmount() {
        assertEquals(rs(4000), parser.parse("2 samosas 40", today)!!.amount)
        assertEquals(rs(30000), parser.parse("dinner for 2 rs 300", today)!!.amount)
    }

    @Test fun noAmount() {
        assertNull(parser.parse("lunch", today))
        assertNull(parser.parse("0 lunch", today))
    }
}
