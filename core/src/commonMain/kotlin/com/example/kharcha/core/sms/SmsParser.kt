package com.example.kharcha.core.sms

import com.example.kharcha.core.money.CurrencyCode
import com.example.kharcha.core.money.Money
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month

enum class Direction { DEBIT, CREDIT }

/** Parser / categorizer confidence below this marks a transaction "needs review". */
const val REVIEW_THRESHOLD = 0.7

enum class Instrument { UPI, CARD, BANK_TRANSFER, ATM, OTHER }

data class ParsedSms(
    /** Always positive; see [direction]. */
    val amount: Money,
    val direction: Direction,
    val bankCode: String?,
    val accountLast4: String?,
    val merchant: String?,
    val reference: String?,
    val balance: Money?,
    val date: LocalDate?,
    val instrument: Instrument,
    /** 0..1. Below [REVIEW_THRESHOLD] the transaction should be marked "needs review". */
    val confidence: Double,
) {
    /** Stable key to detect the same payment arriving twice (SMS + notification, duplicate SMS). */
    val dedupeKey: String
        get() = reference?.let { "ref:$it" }
            ?: "h:${bankCode}|${accountLast4}|${direction}|${amount.minor}|${date}"
}

/**
 * Parses Indian bank transaction SMS (UPI, card, NEFT/IMPS, ATM) into structured data.
 *
 * Design notes:
 *  - Only messages from DLT business headers ("VM-HDFCBK", "JD-SBIUPI-S") are accepted.
 *    Messages from plain phone numbers are ignored: anyone can send those (spoofing).
 *  - Non-transactions are rejected first: OTPs, payment requests, future/auto-debit
 *    notices, bill reminders, failed transactions and promotions.
 *  - Field extraction is heuristic so that unseen banks mostly work; bank-specific
 *    quirks belong in [BankDirectory] or new patterns here, each with a test case.
 */
class SmsParser(private val currency: CurrencyCode = CurrencyCode.INR) {

    fun parse(sender: String, body: String): ParsedSms? {
        val header = SenderId.header(sender) ?: return null
        val text = body.replace(' ', ' ')
        val lower = text.lowercase()
        if (rejectPatterns.any { it.containsMatchIn(lower) }) return null

        val direction = direction(lower) ?: return null

        // Remove balance / limit mentions so their amounts aren't mistaken for the payment.
        val balanceMatch = balancePattern.find(text)
        val balance = balanceMatch?.groupValues?.get(1)?.let { Money.parse(it, currency) }
        val withoutBalance = balanceMatch?.let { text.removeRange(it.range) } ?: text
        val amount = findAmount(withoutBalance) ?: return null
        if (amount.isZero) return null

        val bankCode = BankDirectory.bankFor(header)
        val upiPath = upiPathPattern.find(text)
        val reference = upiPath?.groupValues?.get(1) ?: referencePattern.find(text)?.groupValues?.get(1)
        val merchant = merchant(text, direction, upiPath)

        var confidence = 0.9
        if (bankCode == null) confidence -= 0.25
        if (merchant == null) confidence -= 0.1

        return ParsedSms(
            amount = amount,
            direction = direction,
            bankCode = bankCode,
            accountLast4 = accountPattern.find(text)?.groupValues?.get(1)?.takeLast(4),
            merchant = merchant,
            reference = reference,
            balance = balance,
            date = SmsDates.find(text),
            instrument = instrument(lower),
            confidence = confidence,
        )
    }

    private fun direction(lower: String): Direction? {
        val debit = debitKeywords.mapNotNull { it.find(lower)?.range?.first }.minOrNull()
        val credit = creditKeywords.mapNotNull { it.find(lower)?.range?.first }.minOrNull()
        return when {
            debit == null && credit == null -> null
            credit == null -> Direction.DEBIT
            debit == null -> Direction.CREDIT
            // e.g. ICICI: "Acct XX123 debited for Rs 1,250 on ...; ZOMATO credited." → first verb wins.
            debit < credit -> Direction.DEBIT
            else -> Direction.CREDIT
        }
    }

    private fun findAmount(text: String): Money? {
        val raw = amountPattern.find(text)?.groupValues?.get(1)
            ?: bareAmountAfterVerb.find(text)?.groupValues?.get(1)
            ?: return null
        return Money.parse(raw, currency)
    }

    private fun merchant(text: String, direction: Direction, upiPath: MatchResult?): String? {
        val candidates = buildList {
            upiPath?.groupValues?.get(2)?.let(::add)
            when (direction) {
                Direction.DEBIT -> {
                    semicolonCredited.find(text)?.groupValues?.get(1)?.let(::add)
                    atMerchant.find(text)?.groupValues?.get(1)?.let(::add)
                    toMerchant.find(text)?.groupValues?.get(1)?.let(::add)
                }
                Direction.CREDIT -> {
                    fromMerchant.find(text)?.groupValues?.get(1)?.let(::add)
                    byMerchant.find(text)?.groupValues?.get(1)?.let(::add)
                }
            }
        }
        return candidates.map(::cleanMerchant).firstOrNull(::isPlausibleMerchant)
    }

    private fun cleanMerchant(raw: String): String =
        raw.trim().trimEnd('.', ',', ';', ':', '-', ' ').take(60).trim()

    private fun isPlausibleMerchant(m: String): Boolean {
        if (m.length < 2) return false
        if (m.none { it.isLetter() }) return false
        val l = m.lowercase()
        if (notMerchant.any { l == it || l.startsWith("$it ") }) return false
        if (accountMention.containsMatchIn(l)) return false
        return true
    }

    private fun instrument(lower: String): Instrument = when {
        "upi" in lower || vpa.containsMatchIn(lower) -> Instrument.UPI
        Regex("""\b(atm|cash withdrawal|withdrawn)\b""").containsMatchIn(lower) -> Instrument.ATM
        Regex("""\bcard\b""").containsMatchIn(lower) -> Instrument.CARD
        Regex("""\b(neft|imps|rtgs)\b""").containsMatchIn(lower) -> Instrument.BANK_TRANSFER
        else -> Instrument.OTHER
    }

    private companion object {
        const val AMT = """(\d[\d,]*(?:\.\d{1,2})?)"""
        const val CUR = """(?:rs\.?|inr|₹)"""

        val amountPattern = Regex("""$CUR\s*$AMT""", RegexOption.IGNORE_CASE)
        // SBI style: "debited by 250.0" (no currency marker).
        val bareAmountAfterVerb = Regex("""(?:debited|credited)\s+(?:by|with|for)\s+$AMT""", RegexOption.IGNORE_CASE)
        val balancePattern = Regex(
            """(?:avl|avbl|available|clr|closing|total)\.?\s*(?:bal(?:ance)?|limit|lmt|credit limit)\s*(?:is|:|-)?\s*(?:$CUR)?\s*:?\s*$AMT""",
            RegexOption.IGNORE_CASE,
        )
        val accountPattern = Regex(
            """\b(?:a/c|acct|account|ac|card)\b(?:\s*no\.?)?\s*(?:ending(?:\s+(?:with|in))?)?\s*[x*]+\s*(\d{3,6})""",
            RegexOption.IGNORE_CASE,
        )
        val upiPathPattern = Regex("""\bUPI/(?:P2[AM]/)?(\d{9,18})/([^/\n]+)""", RegexOption.IGNORE_CASE)
        val referencePattern = Regex(
            """(?:\bupi\s*(?:ref|txn|transaction)?\s*(?:no|id|number)?|\bref(?:erence)?\s*(?:no|id|number)?|\brefno|\brrn|\btxn\s*id|\butr)\s*(?:no)?\s*[:.\-#]?\s*(\d{9,18})""",
            RegexOption.IGNORE_CASE,
        )

        private const val STOP = """(?=\s+(?:on|via|ref\w*|upi\w*|avl\w*|avbl\w*|at|from|for|dated|is)\b|\s*\.(?=\s|$)|\s*[\n(]|\s*$)"""
        private const val NAME = """([A-Za-z0-9@][A-Za-z0-9@._\-&' *]{0,59}?)"""
        val semicolonCredited = Regex(""";\s*([A-Za-z0-9@][^;\n]{0,59}?)\s+credited""", RegexOption.IGNORE_CASE)
        val atMerchant = Regex("""\bat\s+$NAME$STOP""", RegexOption.IGNORE_CASE)
        val toMerchant = Regex("""\bto\s+(?:vpa\s+)?$NAME$STOP""", RegexOption.IGNORE_CASE)
        val fromMerchant = Regex("""\bfrom\s+(?:vpa\s+)?$NAME$STOP""", RegexOption.IGNORE_CASE)
        val byMerchant = Regex("""\bby\s+(?:vpa\s+)?$NAME$STOP""", RegexOption.IGNORE_CASE)

        val vpa = Regex("""[a-z0-9.\-_]+@[a-z]+""")
        val accountMention = Regex("""\b(?:a/c|acct|account|ac|card)\b""")
        val notMerchant = listOf("your", "you", "the", "neft", "imps", "rtgs", "upi", "transfer", "self")

        val debitKeywords = listOf(
            Regex("""\bdebited\b"""), Regex("""\bdebit(?:ed)? of\b"""), Regex("""\bspent\b"""),
            Regex("""\bpaid\b"""), Regex("""\bsent\b"""), Regex("""\bwithdrawn\b"""),
            Regex("""\bpurchase\b"""), Regex("""\bdeducted\b"""), Regex("""\btransferred to\b"""),
        )
        val creditKeywords = listOf(
            Regex("""\bcredited\b"""), Regex("""\breceived\b"""), Regex("""\bdeposited\b"""),
            Regex("""\brefund(?:ed)?\b"""), Regex("""\badded to\b"""),
        )

        val rejectPatterns = listOf(
            Regex("""\botp\b"""), Regex("""one[ -]time password"""), Regex("""verification code"""),
            Regex("""\bwill be (?:auto[ -]?)?debited\b"""), Regex("""\bto be debited\b"""),
            Regex("""\brequested (?:money|payment|rs|inr|₹)"""), Regex("""\bcollect request\b"""),
            Regex("""\b(?:failed|declined|unsuccessful|could not be processed)\b"""),
            Regex("""\b(?:is due|due on|due date|due by|payment due|minimum (?:amount|amt) due|total (?:amount|amt) due)\b"""),
            Regex("""\b(?:pre-?approved|loan offer|apply now|get up to|eligible for|offer valid)\b"""),
            Regex("""\bmandate\b.*\b(?:created|registered|set up)\b"""),
        )
    }
}

/** DLT sender headers look like "VM-HDFCBK", "JD-SBIUPI-S", "AX-ICICIT-T". */
object SenderId {
    private val pattern = Regex("""^(?:[A-Z]{2}-)?([A-Z0-9]{3,8})(?:-[A-Z])?$""")

    /** The 6-ish character header, or null for phone numbers and malformed senders. */
    fun header(sender: String): String? {
        val s = sender.trim().uppercase()
        val header = pattern.matchEntire(s)?.groupValues?.get(1) ?: return null
        if (header.all { it.isDigit() }) return null
        return header
    }
}

/** Maps sender headers to bank codes. Extend with each newly supported bank. */
object BankDirectory {
    private val prefixes = listOf(
        "HDFC" to "HDFC", "ICICI" to "ICICI", "SBI" to "SBI", "CBSSBI" to "SBI", "ATMSBI" to "SBI",
        "AXIS" to "AXIS", "KOTAK" to "KOTAK", "YESB" to "YES", "IDFC" to "IDFC", "INDUS" to "INDUSIND",
        "PNB" to "PNB", "BOB" to "BOB", "BARODA" to "BOB", "CANBNK" to "CANARA", "UNIONB" to "UNION",
        "FEDBNK" to "FEDERAL", "AUBANK" to "AU", "IOB" to "IOB", "BOI" to "BOI", "CENTBK" to "CENTRAL",
        "PAYTMB" to "PAYTM", "AIRBNK" to "AIRTEL", "JIOPBS" to "JIO", "SCBANK" to "SC", "HSBC" to "HSBC",
        "RBLBNK" to "RBL", "AMEX" to "AMEX", "ONECRD" to "ONECARD", "SLCEIT" to "SLICE",
    )

    fun bankFor(header: String): String? =
        prefixes.firstOrNull { (prefix, _) -> header.startsWith(prefix) || header.contains(prefix) }?.second
}

internal object SmsDates {
    private val numeric = Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})\b""")
    private val iso = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val named = Regex("""\b(\d{1,2})[- ]?([A-Za-z]{3})[a-z]*[- ,]?(\d{2,4})\b""")
    private val months = Month.entries.associateBy { it.name.take(3) }

    fun find(text: String): LocalDate? {
        iso.find(text)?.let { m ->
            build(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { return it }
        }
        numeric.find(text)?.let { m ->
            build(year(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt())?.let { return it }
        }
        named.find(text)?.let { m ->
            val month = months[m.groupValues[2].uppercase()] ?: return@let
            build(year(m.groupValues[3]), month.ordinal + 1, m.groupValues[1].toInt())?.let { return it }
        }
        return null
    }

    private fun year(y: String): Int = if (y.length == 2) 2000 + y.toInt() else y.toInt()

    private fun build(year: Int, month: Int, day: Int): LocalDate? =
        if (year !in 2000..2099) null else runCatching { LocalDate(year, month, day) }.getOrNull()
}
