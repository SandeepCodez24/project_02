package com.example.kharcha.core.categorize

import com.example.kharcha.core.categorize.DefaultCategories as C

enum class MatchType { CONTAINS, EQUALS, STARTS_WITH, REGEX }

data class CategorizationRule(
    val id: String,
    val matchType: MatchType,
    /** Matched against the *normalized* merchant (lowercase), except REGEX which is case-insensitive. */
    val pattern: String,
    val categoryId: String,
    val priority: Int = 0,
) {
    private val regex: Regex? by lazy {
        if (matchType == MatchType.REGEX) runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() else null
    }

    fun matches(normalizedMerchant: String): Boolean {
        val p = pattern.lowercase().trim()
        return when (matchType) {
            MatchType.CONTAINS -> p.isNotEmpty() && normalizedMerchant.contains(p)
            MatchType.EQUALS -> normalizedMerchant == p
            MatchType.STARTS_WITH -> p.isNotEmpty() && normalizedMerchant.startsWith(p)
            MatchType.REGEX -> regex?.containsMatchIn(normalizedMerchant) == true
        }
    }
}

/** A learned merchant → category association (from the user's corrections). */
data class MerchantMemoryEntry(val merchant: String, val categoryId: String, val hits: Int)

enum class SuggestionSource { RULE, MEMORY, DICTIONARY, HEURISTIC }

data class CategorySuggestion(val categoryId: String, val source: SuggestionSource, val confidence: Double)

/**
 * Categorization pipeline, first confident answer wins:
 *  1. explicit user rules (highest priority first)
 *  2. merchant memory learned from corrections
 *  3. built-in Indian merchant dictionary
 *  4. heuristics (person-to-person UPI, salary credits)
 * An opt-in LLM step will slot in after (4) — see CLAUDE.md.
 */
class Categorizer(
    rules: List<CategorizationRule>,
    memory: List<MerchantMemoryEntry>,
    private val dictionary: Map<String, String> = MerchantDictionary.entries,
) {
    private val rules = rules.sortedByDescending { it.priority }
    private val memory = memory.associateBy { it.merchant }

    fun suggest(rawMerchant: String?, isCredit: Boolean = false): CategorySuggestion? {
        if (rawMerchant.isNullOrBlank()) return null
        val merchant = MerchantNormalizer.normalize(rawMerchant)

        rules.firstOrNull { it.matches(merchant) }?.let {
            return CategorySuggestion(it.categoryId, SuggestionSource.RULE, 1.0)
        }

        if (merchant.isNotEmpty()) {
            memory[merchant]?.let { entry ->
                // One correction is a strong signal; repeated ones are near-certain.
                val confidence = (0.8 + 0.05 * (entry.hits - 1)).coerceAtMost(0.99)
                return CategorySuggestion(entry.categoryId, SuggestionSource.MEMORY, confidence)
            }
            dictionaryLookup(merchant)?.let {
                return CategorySuggestion(it, SuggestionSource.DICTIONARY, 0.75)
            }
        }

        if (isCredit) {
            if (Regex("""\b(salary|sal|payroll)\b""").containsMatchIn(merchant)) {
                return CategorySuggestion(C.SALARY, SuggestionSource.HEURISTIC, 0.7)
            }
            if (Regex("""\b(refund|cashback|reversal)\b""").containsMatchIn(merchant)) {
                return CategorySuggestion(C.REFUND, SuggestionSource.HEURISTIC, 0.7)
            }
        } else if (MerchantNormalizer.looksLikePerson(rawMerchant)) {
            return CategorySuggestion(C.PEOPLE, SuggestionSource.HEURISTIC, 0.5)
        }
        return null
    }

    /** Matches whole words, so "ola" hits "ola cabs" but not "motorola". */
    private fun dictionaryLookup(merchant: String): String? {
        val words = merchant.split(' ')
        dictionary[merchant]?.let { return it }
        // Try two-word keys first ("big bazaar") then single words.
        for (i in 0 until words.size - 1) {
            dictionary["${words[i]} ${words[i + 1]}"]?.let { return it }
        }
        for (w in words) dictionary[w]?.let { return it }
        return null
    }
}

/** Common Indian merchants → default categories. Keys are normalized (lowercase words). */
object MerchantDictionary {
    val entries: Map<String, String> = buildMap {
        fun add(category: String, vararg keys: String) = keys.forEach { put(it, category) }
        add(C.FOOD, "swiggy", "zomato", "eatsure", "dominos", "mcdonalds", "kfc", "starbucks", "haldirams", "chaayos", "burger king", "pizza hut")
        add(C.GROCERIES, "bigbasket", "blinkit", "zepto", "instamart", "dmart", "jiomart", "big bazaar", "more retail", "reliance fresh", "natures basket")
        add(C.TRANSPORT, "uber", "ola", "rapido", "namma yatri", "metro", "fastag", "redbus")
        add(C.FUEL, "indian oil", "iocl", "hpcl", "bpcl", "bharat petroleum", "hindustan petroleum", "shell")
        add(C.SHOPPING, "amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "tata cliq", "croma", "decathlon", "ikea")
        add(C.BILLS, "bescom", "tata power", "adani electricity", "mseb", "bses", "mahanagar gas", "indane", "bharat gas", "billdesk")
        add(C.RECHARGE, "jio", "airtel", "vodafone", "vi", "bsnl", "act fibernet", "hathway")
        add(C.SUBSCRIPTIONS, "netflix", "spotify", "hotstar", "jiohotstar", "primevideo", "youtube", "sonyliv", "zee5", "apple", "google play")
        add(C.HEALTH, "apollo", "pharmeasy", "netmeds", "1mg", "practo", "medplus", "cult fit", "cultfit")
        add(C.ENTERTAINMENT, "bookmyshow", "pvr", "inox")
        add(C.TRAVEL, "irctc", "makemytrip", "goibibo", "cleartrip", "indigo", "air india", "akasa", "ixigo", "oyo", "airbnb")
        add(C.EDUCATION, "byjus", "unacademy", "udemy", "coursera")
        add(C.INVESTMENTS, "zerodha", "groww", "upstox", "indmoney", "kuvera", "coin", "nps", "ppf")
    }
}
