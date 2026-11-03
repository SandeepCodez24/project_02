package com.example.kharcha.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.example.kharcha.core.budget.DateRange
import com.example.kharcha.core.budget.Spend
import com.example.kharcha.core.categorize.CategorizationRule
import com.example.kharcha.core.categorize.Categorizer
import com.example.kharcha.core.categorize.DefaultCategories
import com.example.kharcha.core.categorize.MatchType
import com.example.kharcha.core.categorize.MerchantMemoryEntry
import com.example.kharcha.core.categorize.MerchantNormalizer
import com.example.kharcha.core.domain.Account
import com.example.kharcha.core.domain.AccountType
import com.example.kharcha.core.domain.Category
import com.example.kharcha.core.domain.CategoryKind
import com.example.kharcha.core.domain.Transaction
import com.example.kharcha.core.domain.TransactionLine
import com.example.kharcha.core.domain.TransactionSource
import com.example.kharcha.core.entry.QuickEntry
import com.example.kharcha.core.money.CurrencyCode
import com.example.kharcha.core.money.Money
import com.example.kharcha.core.sms.Direction
import com.example.kharcha.core.sms.ParsedSms
import com.example.kharcha.core.sms.REVIEW_THRESHOLD
import com.example.kharcha.db.KharchaDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** A row in the home-screen feed. */
data class TransactionSummary(
    val id: String,
    val accountId: String,
    val date: LocalDate,
    val payee: String?,
    val total: Money,
    val lineCount: Int,
    val categoryId: String?,
    val source: TransactionSource,
    val isTransfer: Boolean,
    val needsReview: Boolean,
)

sealed interface SmsImportResult {
    data class Imported(val transactionId: String) : SmsImportResult
    data object Duplicate : SmsImportResult
}

/**
 * Single entry point for reading and writing the ledger. All multi-row writes are
 * done in one DB transaction. IDs and clock are injectable for tests.
 */
const val CASH_ACCOUNT_ID = "acc.cash"

class LedgerRepository(
    private val db: KharchaDb,
    private val newId: () -> String = { Uuid.random().toString() },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Inserts built-in categories that don't exist yet. Safe to call on every launch. */
    fun seedDefaults() {
        db.transaction {
            DefaultCategories.all.forEachIndexed { index, c ->
                db.categoryQueries.insertOrIgnore(c.id, c.name, c.kind.name, c.parentId, c.icon, index.toLong(), 1L, now())
            }
        }
    }

    // ---- Accounts -------------------------------------------------------------------

    fun addAccount(account: Account) {
        db.accountQueries.insert(
            account.id, account.name, account.type.name, account.currency.code,
            account.openingBalance.minor, account.last4, account.bankCode, now(),
        )
    }

    fun accounts(): List<Account> = db.accountQueries.selectActive().executeAsList().map {
        Account(
            id = it.id,
            name = it.name,
            type = AccountType.valueOf(it.type),
            currency = CurrencyCode(it.currency),
            openingBalance = Money(it.opening_balance_minor, CurrencyCode(it.currency)),
            last4 = it.last4,
            bankCode = it.bank_code,
            archived = it.archived != 0L,
        )
    }

    /** Every install has a Cash wallet so quick entry works before any bank is set up. */
    fun ensureCashAccount(currency: CurrencyCode = CurrencyCode.INR): String {
        db.accountQueries.selectById(CASH_ACCOUNT_ID).executeAsOneOrNull()?.let { return it.id }
        addAccount(Account(CASH_ACCOUNT_ID, "Cash", AccountType.CASH, currency, Money.zero(currency)))
        return CASH_ACCOUNT_ID
    }

    fun balance(accountId: String): Money {
        val account = db.accountQueries.selectById(accountId).executeAsOne()
        val minor = db.accountQueries.balance(accountId).executeAsOneOrNull() ?: 0L
        return Money(minor, CurrencyCode(account.currency))
    }

    fun categories(): List<Category> = db.categoryQueries.selectActive().executeAsList().map {
        Category(it.id, it.name, CategoryKind.valueOf(it.kind), it.parent_id, it.icon)
    }

    // ---- Transactions ---------------------------------------------------------------

    /** Inserts a transaction with all its lines and tags. Returns false if its externalRef already exists. */
    fun addTransaction(tx: Transaction): Boolean {
        var inserted = false
        db.transaction {
            if (tx.externalRef != null &&
                db.txnQueries.findByExternalRef(tx.accountId, tx.externalRef).executeAsOneOrNull() != null
            ) {
                return@transaction
            }
            val ts = now()
            db.txnQueries.insertTxn(
                id = tx.id,
                account_id = tx.accountId,
                occurred_on = tx.date.toString(),
                occurred_at = null,
                payee = tx.payee,
                payee_normalized = tx.payee?.let(MerchantNormalizer::normalize),
                note = tx.note,
                source = tx.source.name,
                external_ref = tx.externalRef,
                transfer_group_id = tx.transferGroupId,
                needs_review = if (tx.needsReview) 1L else 0L,
                created_at = ts,
                updated_at = ts,
            )
            tx.lines.forEachIndexed { index, line ->
                db.txnQueries.insertLine(
                    line.id, tx.id, line.categoryId, line.amount.minor, line.amount.currency.code,
                    line.memo, index.toLong(), ts,
                )
                line.tags.forEach { tagName -> attachTag(line.id, tagName, ts) }
            }
            inserted = true
        }
        return inserted
    }

    /** Saves a parsed quick entry ("450 lunch swiggy"), auto-categorized. Returns the transaction ID. */
    fun addQuickEntry(entry: QuickEntry, accountId: String): String {
        val suggestion = categorizer().suggest(entry.description, isCredit = entry.isIncome)
        val id = newId()
        val payee = entry.description.ifBlank { null }?.replaceFirstChar { it.uppercase() }
        addTransaction(
            Transaction(
                id = id,
                accountId = accountId,
                date = entry.date,
                payee = payee,
                lines = listOf(
                    TransactionLine(
                        id = newId(),
                        amount = if (entry.isIncome) entry.amount else -entry.amount,
                        categoryId = suggestion?.categoryId
                            ?: if (entry.isIncome) DefaultCategories.OTHER_INCOME else DefaultCategories.OTHER_EXPENSE,
                    ),
                ),
                source = TransactionSource.QUICK_ENTRY,
            ),
        )
        return id
    }

    /**
     * Records a transfer between two of the user's accounts as two linked transactions.
     * Transfers never count as income or expense.
     */
    fun addTransfer(fromAccountId: String, toAccountId: String, amount: Money, date: LocalDate, note: String? = null): String {
        require(!amount.isNegative && !amount.isZero) { "Transfer amount must be positive" }
        val group = newId()
        db.transaction {
            addTransaction(
                Transaction(newId(), fromAccountId, date, "Transfer", listOf(TransactionLine(newId(), -amount, null)),
                    note = note, transferGroupId = group),
            )
            addTransaction(
                Transaction(newId(), toAccountId, date, "Transfer", listOf(TransactionLine(newId(), amount, null)),
                    note = note, transferGroupId = group),
            )
        }
        return group
    }

    fun transaction(id: String): Transaction? {
        val row = db.txnQueries.selectById(id).executeAsOneOrNull() ?: return null
        val lines = db.txnQueries.linesForTxn(id).executeAsList().map { l ->
            TransactionLine(
                id = l.id,
                amount = Money(l.amount_minor, CurrencyCode(l.currency)),
                categoryId = l.category_id,
                memo = l.memo,
                tags = db.txnQueries.tagsForLine(l.id).executeAsList().toSet(),
            )
        }
        return Transaction(
            id = row.id,
            accountId = row.account_id,
            date = LocalDate.parse(row.occurred_on),
            payee = row.payee,
            lines = lines,
            note = row.note,
            source = TransactionSource.valueOf(row.source),
            externalRef = row.external_ref,
            transferGroupId = row.transfer_group_id,
            needsReview = row.needs_review != 0L,
        )
    }

    fun recent(limit: Long = 50): List<TransactionSummary> =
        db.txnQueries.recentWithTotals(limit).executeAsList().map { it.toSummary() }

    /** Live-updating feed for the UI. */
    fun observeRecent(limit: Long = 50): Flow<List<TransactionSummary>> =
        db.txnQueries.recentWithTotals(limit) { id, accountId, occurredOn, payee, source, transferGroupId,
                                                needsReview, totalMinor, currency, lineCount, firstCategoryId ->
            TransactionSummary(
                id = id,
                accountId = accountId,
                date = LocalDate.parse(occurredOn),
                payee = payee,
                total = Money(totalMinor, CurrencyCode(currency)),
                lineCount = lineCount.toInt(),
                categoryId = firstCategoryId,
                source = TransactionSource.valueOf(source),
                isTransfer = transferGroupId != null,
                needsReview = needsReview != 0L,
            )
        }.asFlow().mapToList(ioDispatcher)

    fun delete(id: String) {
        db.transaction {
            val ts = now()
            db.txnQueries.softDeleteLines(ts, id)
            db.txnQueries.softDelete(ts, id)
        }
    }

    // ---- Reports & budgets ----------------------------------------------------------

    /** Net spending per category (positive = spent). Uncategorized spending is keyed by null. */
    fun spendingByCategory(range: DateRange, currency: CurrencyCode = CurrencyCode.INR): Map<String?, Money> =
        db.txnQueries.spendingByCategory(currency.code, range.start.toString(), range.endExclusive.toString())
            .executeAsList()
            .associate { it.category_id to Money(it.spent_minor, currency) }

    fun dailySpending(range: DateRange, currency: CurrencyCode = CurrencyCode.INR): List<Spend> =
        db.txnQueries.dailySpending(currency.code, range.start.toString(), range.endExclusive.toString())
            .executeAsList()
            .mapNotNull { row ->
                val category = row.category_id ?: return@mapNotNull null
                Spend(category, LocalDate.parse(row.occurred_on), Money(row.spent_minor, currency))
            }

    // ---- Categorization -------------------------------------------------------------

    fun categorizer(): Categorizer {
        val rules = db.categorizationQueries.activeRules().executeAsList().map {
            CategorizationRule(it.id, MatchType.valueOf(it.match_type), it.pattern, it.category_id, it.priority.toInt())
        }
        val memory = db.categorizationQueries.allMemory().executeAsList().map {
            MerchantMemoryEntry(it.merchant, it.category_id, it.hits.toInt())
        }
        return Categorizer(rules, memory)
    }

    fun addRule(rule: CategorizationRule) {
        db.categorizationQueries.insertRule(rule.id, rule.matchType.name, rule.pattern, rule.categoryId, rule.priority.toLong(), now())
    }

    /**
     * The user changed a line's category. Updates the line and teaches merchant memory,
     * so the next transaction from the same merchant is categorized correctly.
     */
    fun recategorize(transactionId: String, lineId: String, categoryId: String) {
        db.transaction {
            val ts = now()
            db.txnQueries.setLineCategory(categoryId, ts, lineId)
            db.txnQueries.markReviewed(ts, transactionId)
            val payee = db.txnQueries.selectById(transactionId).executeAsOneOrNull()?.payee_normalized
            if (!payee.isNullOrBlank()) {
                db.categorizationQueries.memoryInsertIfMissing(payee, categoryId, ts)
                db.categorizationQueries.memoryRecord(categoryId, ts, payee)
            }
        }
    }

    // ---- SMS import -----------------------------------------------------------------

    /**
     * Turns a parsed bank SMS into a transaction. Finds the account by bank + last 4 digits,
     * creating a placeholder account on first sight. Duplicates (same reference) are skipped.
     */
    fun importSms(sms: ParsedSms, receivedOn: LocalDate): SmsImportResult {
        var result: SmsImportResult = SmsImportResult.Duplicate
        db.transaction {
            val accountId = resolveAccountFor(sms)
            val signed = if (sms.direction == Direction.DEBIT) -sms.amount else sms.amount
            val suggestion = categorizer().suggest(sms.merchant, isCredit = sms.direction == Direction.CREDIT)
            val txId = newId()
            val inserted = addTransaction(
                Transaction(
                    id = txId,
                    accountId = accountId,
                    date = sms.date ?: receivedOn,
                    payee = sms.merchant,
                    lines = listOf(TransactionLine(newId(), signed, suggestion?.categoryId)),
                    source = TransactionSource.SMS,
                    externalRef = sms.dedupeKey,
                    needsReview = suggestion == null || suggestion.confidence < REVIEW_THRESHOLD || sms.confidence < REVIEW_THRESHOLD,
                ),
            )
            if (inserted) result = SmsImportResult.Imported(txId)
        }
        return result
    }

    private fun resolveAccountFor(sms: ParsedSms): String {
        val last4 = sms.accountLast4
        val existing = if (last4 != null) {
            db.accountQueries.matchForSms(last4, sms.bankCode).executeAsList().firstOrNull()
        } else {
            db.accountQueries.matchBankOnly(sms.bankCode).executeAsList().firstOrNull()
        }
        if (existing != null) return existing.id
        val id = newId()
        val name = listOfNotNull(sms.bankCode ?: "Bank", last4?.let { "••$it" }).joinToString(" ")
        addAccount(
            Account(id, name, AccountType.BANK, sms.amount.currency, Money.zero(sms.amount.currency), last4, sms.bankCode),
        )
        return id
    }

    private fun attachTag(lineId: String, name: String, ts: Long) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        db.txnQueries.insertTagIfMissing(newId(), trimmed, ts)
        val tagId = db.txnQueries.tagIdByName(trimmed).executeAsOne()
        db.txnQueries.insertLineTag(lineId, tagId)
    }

    private fun com.example.kharcha.db.RecentWithTotals.toSummary() = TransactionSummary(
        id = id,
        accountId = account_id,
        date = LocalDate.parse(occurred_on),
        payee = payee,
        total = Money(total_minor, CurrencyCode(currency)),
        lineCount = line_count.toInt(),
        categoryId = first_category_id,
        source = TransactionSource.valueOf(source),
        isTransfer = transfer_group_id != null,
        needsReview = needs_review != 0L,
    )
}
