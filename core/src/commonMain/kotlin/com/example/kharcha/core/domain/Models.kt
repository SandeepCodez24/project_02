package com.example.kharcha.core.domain

import com.example.kharcha.core.money.CurrencyCode
import com.example.kharcha.core.money.Money
import kotlinx.datetime.LocalDate

// Domain models used by the UI and services. The SQLDelight-generated row
// classes stay inside the repository layer; map to these at the boundary.

enum class AccountType { BANK, CREDIT_CARD, CASH, WALLET, LOAN, INVESTMENT }

data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val currency: CurrencyCode,
    val openingBalance: Money,
    /** Last 4 digits as printed in bank SMS ("A/c XX1234"), used to auto-match SMS to accounts. */
    val last4: String? = null,
    /** Bank code from the SMS sender ID, e.g. "HDFC". */
    val bankCode: String? = null,
    val archived: Boolean = false,
)

enum class CategoryKind { EXPENSE, INCOME }

data class Category(
    val id: String,
    val name: String,
    val kind: CategoryKind,
    val parentId: String? = null,
    val icon: String? = null,
)

/** Where a transaction came from. Drives dedupe and "needs review" UX. */
enum class TransactionSource { MANUAL, QUICK_ENTRY, SMS, NOTIFICATION, IOS_SHORTCUT, CSV_IMPORT, BANK_SYNC }

/**
 * One line of a transaction. Every transaction has >= 1 line; a split purchase has several.
 * [amount] is signed: negative = outflow (expense), positive = inflow (income/refund).
 */
data class TransactionLine(
    val id: String,
    val amount: Money,
    val categoryId: String?,
    val memo: String? = null,
    val tags: Set<String> = emptySet(),
)

data class Transaction(
    val id: String,
    val accountId: String,
    val date: LocalDate,
    val payee: String?,
    val lines: List<TransactionLine>,
    val note: String? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
    /** Bank / UPI reference used for deduplication across SMS, notifications and imports. */
    val externalRef: String? = null,
    /** Set on both legs of an internal transfer; such lines are excluded from income/expense. */
    val transferGroupId: String? = null,
    val needsReview: Boolean = false,
) {
    init {
        require(lines.isNotEmpty()) { "A transaction needs at least one line" }
        require(lines.all { it.amount.currency == lines.first().amount.currency }) {
            "All lines of a transaction must share one currency"
        }
    }

    val total: Money get() = lines.drop(1).fold(lines.first().amount) { acc, l -> acc + l.amount }
    val isTransfer: Boolean get() = transferGroupId != null
}
