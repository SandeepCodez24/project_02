package com.example.kharcha.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.kharcha.core.budget.BudgetCalculator
import com.example.kharcha.core.budget.BudgetLine
import com.example.kharcha.core.budget.BudgetPeriod
import com.example.kharcha.core.budget.DateRange
import com.example.kharcha.core.budget.RolloverMode
import com.example.kharcha.core.categorize.DefaultCategories
import com.example.kharcha.core.domain.Account
import com.example.kharcha.core.domain.AccountType
import com.example.kharcha.core.domain.Transaction
import com.example.kharcha.core.domain.TransactionLine
import com.example.kharcha.core.entry.QuickEntryParser
import com.example.kharcha.core.money.CurrencyCode
import com.example.kharcha.core.money.Money
import com.example.kharcha.core.sms.SmsParser
import com.example.kharcha.db.KharchaDb
import kotlinx.datetime.LocalDate
import java.util.Properties
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LedgerRepositoryTest {
    private val inr = CurrencyCode.INR
    private fun rs(major: Long) = Money.ofMajor(major, inr)
    private var counter = 0
    private lateinit var repo: LedgerRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") })
        KharchaDb.Schema.create(driver)
        repo = LedgerRepository(KharchaDb(driver), newId = { "id${++counter}" }, now = { 1_000L })
        repo.seedDefaults()
        repo.addAccount(Account("hdfc", "HDFC Savings", AccountType.BANK, inr, rs(10_000), last4 = "1234", bankCode = "HDFC"))
        repo.addAccount(Account("cash", "Cash", AccountType.CASH, inr, rs(0)))
    }

    private fun expense(id: String, date: LocalDate, vararg lines: Pair<Long, String?>) = Transaction(
        id = id,
        accountId = "hdfc",
        date = date,
        payee = "Test",
        lines = lines.mapIndexed { i, (amount, cat) -> TransactionLine("$id-l$i", rs(-amount), cat) },
    )

    @Test fun seedingIsIdempotent() {
        repo.seedDefaults()
        assertEquals(DefaultCategories.all.size, repo.categories().size)
    }

    @Test fun splitTransactionCountsPerCategory() {
        val costco = expense("t1", LocalDate(2026, 11, 3), 1500L to DefaultCategories.GROCERIES, 500L to DefaultCategories.SHOPPING)
        assertTrue(repo.addTransaction(costco))

        val stored = assertNotNull(repo.transaction("t1"))
        assertEquals(rs(-2000), stored.total)
        assertEquals(2, stored.lines.size)

        val spend = repo.spendingByCategory(DateRange(LocalDate(2026, 11, 1), LocalDate(2026, 12, 1)))
        assertEquals(rs(1500), spend[DefaultCategories.GROCERIES])
        assertEquals(rs(500), spend[DefaultCategories.SHOPPING])
        assertEquals(rs(8000), repo.balance("hdfc"))
    }

    @Test fun transfersMoveMoneyButAreNotSpending() {
        repo.addTransfer("hdfc", "cash", rs(2000), LocalDate(2026, 11, 3))
        assertEquals(rs(8000), repo.balance("hdfc"))
        assertEquals(rs(2000), repo.balance("cash"))
        val spend = repo.spendingByCategory(DateRange(LocalDate(2026, 11, 1), LocalDate(2026, 12, 1)))
        assertTrue(spend.isEmpty())
    }

    @Test fun tagsAreStoredPerLine() {
        val tx = Transaction(
            "t1", "hdfc", LocalDate(2026, 11, 3), "Goa trip",
            listOf(TransactionLine("l1", rs(-3000), DefaultCategories.TRAVEL, tags = setOf("goa-2026", "Friends"))),
        )
        repo.addTransaction(tx)
        assertEquals(setOf("Friends", "goa-2026"), repo.transaction("t1")!!.lines.single().tags)
    }

    @Test fun smsImportMatchesAccountCategorizesAndDedupes() {
        val parser = SmsParser()
        val body = "Sent Rs.450.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 02/11/26\nRef 612345678901"
        val sms = parser.parse("VM-HDFCBK-S", body)!!

        val first = repo.importSms(sms, receivedOn = LocalDate(2026, 11, 2))
        assertIs<SmsImportResult.Imported>(first)
        val tx = repo.transaction(first.transactionId)!!
        assertEquals("hdfc", tx.accountId)
        assertEquals(DefaultCategories.FOOD, tx.lines.single().categoryId)
        assertFalse(tx.needsReview)

        // Same payment again (duplicate SMS, or the app notification for it).
        assertEquals(SmsImportResult.Duplicate, repo.importSms(sms, LocalDate(2026, 11, 2)))
        assertEquals(1, repo.recent().size)
    }

    @Test fun smsFromUnknownAccountCreatesOnePlaceholderAccount() {
        val parser = SmsParser()
        repo.importSms(parser.parse("AX-ICICIT", "ICICI Bank Acct XX999 debited for Rs 100.00 on 01-Nov-26; CHAI POINT credited. UPI:600000000001.")!!, LocalDate(2026, 11, 1))
        repo.importSms(parser.parse("AX-ICICIT", "ICICI Bank Acct XX999 debited for Rs 200.00 on 01-Nov-26; CHAI POINT credited. UPI:600000000002.")!!, LocalDate(2026, 11, 1))
        val icici = repo.accounts().filter { it.bankCode == "ICICI" }
        assertEquals(1, icici.size)
        assertEquals("ICICI ••999", icici.single().name)
        assertEquals(rs(-300), repo.balance(icici.single().id))
    }

    @Test fun recategorizingTeachesMerchantMemory() {
        val parser = SmsParser()
        val first = repo.importSms(
            parser.parse("JD-SBIUPI", "Dear UPI user A/C X1234 debited by 250.0 on date 02Nov26 trf to RAJU KIRANA Refno 612311112222.")!!,
            LocalDate(2026, 11, 2),
        ) as SmsImportResult.Imported
        val tx = repo.transaction(first.transactionId)!!
        assertTrue(tx.needsReview) // unknown merchant
        repo.recategorize(tx.id, tx.lines.single().id, DefaultCategories.GROCERIES)
        assertFalse(repo.transaction(tx.id)!!.needsReview)

        val second = repo.importSms(
            parser.parse("JD-SBIUPI", "Dear UPI user A/C X1234 debited by 80.0 on date 03Nov26 trf to RAJU KIRANA Refno 612311113333.")!!,
            LocalDate(2026, 11, 3),
        ) as SmsImportResult.Imported
        assertEquals(DefaultCategories.GROCERIES, repo.transaction(second.transactionId)!!.lines.single().categoryId)
    }

    @Test fun dailySpendingFeedsBudgetCalculator() {
        repo.addTransaction(expense("t1", LocalDate(2026, 11, 3), 700L to DefaultCategories.FOOD))
        repo.addTransaction(expense("t2", LocalDate(2026, 11, 4), 200L to DefaultCategories.FOOD))
        // A refund reduces spending.
        repo.addTransaction(
            Transaction("t3", "hdfc", LocalDate(2026, 11, 5), "Swiggy refund",
                listOf(TransactionLine("t3-l0", rs(100), DefaultCategories.FOOD))),
        )
        val period = BudgetPeriod.Monthly()
        val nov = period.periodContaining(LocalDate(2026, 11, 15))
        val status = BudgetCalculator.statusFor(
            period, nov.start, listOf(BudgetLine(DefaultCategories.FOOD, rs(1000), RolloverMode.NONE)),
            repo.dailySpending(nov), LocalDate(2026, 11, 15), inr,
        ).single()
        assertEquals(rs(800), status.spent)
        assertEquals(rs(200), status.available)
    }

    @Test fun quickEntryGoesToCashAndIsCategorized() {
        val cash = repo.ensureCashAccount()
        assertEquals(cash, repo.ensureCashAccount()) // idempotent
        val entry = QuickEntryParser().parse("450 lunch swiggy", LocalDate(2026, 11, 3))!!
        val tx = repo.transaction(repo.addQuickEntry(entry, cash))!!
        assertEquals(rs(-450), tx.total)
        assertEquals(DefaultCategories.FOOD, tx.lines.single().categoryId)
        assertEquals("Lunch swiggy", tx.payee)

        val income = QuickEntryParser().parse("+300 cashback", LocalDate(2026, 11, 3))!!
        assertEquals(rs(300), repo.transaction(repo.addQuickEntry(income, cash))!!.total)
        assertEquals(rs(-150), repo.balance(cash))
    }

    @Test fun deletedTransactionsDisappear() {
        repo.addTransaction(expense("t1", LocalDate(2026, 11, 3), 700L to DefaultCategories.FOOD))
        repo.delete("t1")
        assertTrue(repo.recent().isEmpty())
        assertEquals(rs(10_000), repo.balance("hdfc"))
    }
}
