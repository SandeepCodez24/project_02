package com.example.kharcha.core.sms

import com.example.kharcha.core.money.CurrencyCode
import com.example.kharcha.core.money.Money
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sample messages are modelled on the formats Indian banks send (amounts, account
 * numbers and references are made up). When adding a bank, add real-world-shaped
 * samples here first — every parser change must keep all of these passing.
 */
class SmsParserTest {
    private val parser = SmsParser()
    private fun rs(paise: Long) = Money(paise, CurrencyCode.INR)

    @Test fun hdfcUpiSentMultiline() {
        val sms = parser.parse(
            "VM-HDFCBK-S",
            "Sent Rs.450.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 02/11/26\nRef 612345678901\nNot You?\nCall your bank/SMS BLOCK UPI to the bank",
        )
        assertNotNull(sms)
        assertEquals(rs(45000), sms.amount)
        assertEquals(Direction.DEBIT, sms.direction)
        assertEquals("HDFC", sms.bankCode)
        assertEquals("1234", sms.accountLast4)
        assertEquals("SWIGGY", sms.merchant)
        assertEquals("612345678901", sms.reference)
        assertEquals(LocalDate(2026, 11, 2), sms.date)
        assertEquals(Instrument.UPI, sms.instrument)
    }

    @Test fun iciciDebitWithMerchantCredited() {
        val sms = parser.parse(
            "AX-ICICIT-S",
            "ICICI Bank Acct XX123 debited for Rs 1,250.00 on 01-Nov-26; ZOMATO credited. UPI:612398765432. Call 1800 for dispute.",
        )
        assertNotNull(sms)
        assertEquals(rs(125000), sms.amount)
        assertEquals(Direction.DEBIT, sms.direction)
        assertEquals("ICICI", sms.bankCode)
        assertEquals("123", sms.accountLast4)
        assertEquals("ZOMATO", sms.merchant)
        assertEquals("612398765432", sms.reference)
        assertEquals(LocalDate(2026, 11, 1), sms.date)
        assertEquals(Instrument.UPI, sms.instrument)
    }

    @Test fun sbiDebitWithoutCurrencyMarker() {
        val sms = parser.parse(
            "JD-SBIUPI-S",
            "Dear UPI user A/C X4321 debited by 250.0 on date 02Nov26 trf to RAJU KIRANA Refno 612311112222. If not u? call 1800. -SBI",
        )
        assertNotNull(sms)
        assertEquals(rs(25000), sms.amount)
        assertEquals(Direction.DEBIT, sms.direction)
        assertEquals("SBI", sms.bankCode)
        assertEquals("4321", sms.accountLast4)
        assertEquals("RAJU KIRANA", sms.merchant)
        assertEquals("612311112222", sms.reference)
        assertEquals(LocalDate(2026, 11, 2), sms.date)
    }

    @Test fun axisUpiPathFormat() {
        val sms = parser.parse(
            "VK-AXISBK-S",
            "INR 999.00 debited\nA/c no. XX5678\n03-11-26, 10:15:22\nUPI/P2M/612300001111/AMAZON PAY\nNot you? SMS BLOCKUPI to the bank\nAxis Bank",
        )
        assertNotNull(sms)
        assertEquals(rs(99900), sms.amount)
        assertEquals("AXIS", sms.bankCode)
        assertEquals("5678", sms.accountLast4)
        assertEquals("AMAZON PAY", sms.merchant)
        assertEquals("612300001111", sms.reference)
        assertEquals(LocalDate(2026, 11, 3), sms.date)
        assertEquals(Instrument.UPI, sms.instrument)
    }

    @Test fun kotakSentToVpa() {
        val sms = parser.parse(
            "BZ-KOTAKB",
            "Sent Rs.120.00 from Kotak Bank AC X9876 to uber@axisbank on 03-11-26.UPI Ref 612355556666. Not you, report fraud on the Kotak app",
        )
        assertNotNull(sms)
        assertEquals(rs(12000), sms.amount)
        assertEquals("KOTAK", sms.bankCode)
        assertEquals("9876", sms.accountLast4)
        assertEquals("uber@axisbank", sms.merchant)
        assertEquals("612355556666", sms.reference)
    }

    @Test fun neftCreditIgnoresBalance() {
        val sms = parser.parse(
            "AD-HDFCBK",
            "Rs.25,000.00 credited to your A/c XX1234 on 01-11-26 by NEFT from ACME PVT LTD. Avl Bal Rs.1,02,345.67",
        )
        assertNotNull(sms)
        assertEquals(rs(2500000), sms.amount)
        assertEquals(Direction.CREDIT, sms.direction)
        assertEquals(rs(10234567), sms.balance)
        assertEquals("ACME PVT LTD", sms.merchant)
        assertEquals(Instrument.BANK_TRANSFER, sms.instrument)
    }

    @Test fun balanceBeforeAmountIsNotTakenAsAmount() {
        val sms = parser.parse(
            "VM-SBIINB",
            "Avl Bal Rs 8,000.00. Your A/c XX7777 is debited with Rs 2,000.00 at ATM on 04-11-26.",
        )
        assertNotNull(sms)
        assertEquals(rs(200000), sms.amount)
        assertEquals(rs(800000), sms.balance)
    }

    @Test fun creditCardSpend() {
        val sms = parser.parse(
            "VM-HDFCBK",
            "Rs.2,499.00 spent on HDFC Bank Card x4455 at AMAZON on 2026-11-02:14:22:10. Not You? Block the card in the app",
        )
        assertNotNull(sms)
        assertEquals(rs(249900), sms.amount)
        assertEquals(Direction.DEBIT, sms.direction)
        assertEquals("4455", sms.accountLast4)
        assertEquals("AMAZON", sms.merchant)
        assertEquals(LocalDate(2026, 11, 2), sms.date)
        assertEquals(Instrument.CARD, sms.instrument)
    }

    @Test fun upiReceivedFromVpa() {
        val sms = parser.parse(
            "JM-KOTAKB-S",
            "Received Rs.500.00 in your Kotak Bank AC X9876 from rahul.k@okicici on 03-11-26.UPI Ref:612377778888.",
        )
        assertNotNull(sms)
        assertEquals(Direction.CREDIT, sms.direction)
        assertEquals("rahul.k@okicici", sms.merchant)
        assertEquals("612377778888", sms.reference)
    }

    @Test fun rejectsNonTransactions() {
        val rejected = listOf(
            "VM-HDFCBK" to "123456 is your OTP for txn of Rs.500.00 at AMAZON. Do not share it with anyone.",
            "VM-ICICIB" to "Rs 499.00 will be debited from your A/c XX123 on 05-11-26 towards NETFLIX autopay.",
            "VM-HDFCBK" to "Rahul has requested money from you. On approving, INR 200 will be debited from your A/c.",
            "AD-AXISBK" to "Txn of Rs 1,500 at FLIPKART failed. Any amount debited will be refunded.",
            "VM-SBICRD" to "Total amount due Rs 12,345.00 on your SBI Card xx1111 is due on 10-11-26.",
            "AD-HDFCBK" to "Get a pre-approved personal loan up to Rs 5,00,000. Apply now!",
        )
        rejected.forEach { (sender, body) -> assertNull(parser.parse(sender, body), "should reject: $body") }
    }

    @Test fun ignoresMessagesFromPhoneNumbers() {
        // Anyone can send an SMS from a phone number; only DLT business headers are trusted.
        assertNull(parser.parse("+919876543210", "Rs.5000 credited to your A/c XX1234 by NEFT"))
        assertNull(parser.parse("9876543210", "Rs.5000 credited to your A/c XX1234 by NEFT"))
    }

    @Test fun unknownSenderIsLowerConfidence() {
        val sms = parser.parse("AD-MYBANK", "Rs.300 debited from A/c XX1111 to CHAI POINT on 03-11-26")
        assertNotNull(sms)
        assertNull(sms.bankCode)
        assertTrue(sms.confidence < REVIEW_THRESHOLD)
    }

    @Test fun dedupeKeyPrefersReference() {
        val a = parser.parse("VM-HDFCBK", "Sent Rs.450.00 From HDFC Bank A/C *1234 To SWIGGY On 02/11/26 Ref 612345678901")!!
        val b = parser.parse("AD-HDFCBK-S", "Sent Rs.450.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 02/11/26\nRef 612345678901")!!
        assertEquals("ref:612345678901", a.dedupeKey)
        assertEquals(a.dedupeKey, b.dedupeKey)
    }
}
