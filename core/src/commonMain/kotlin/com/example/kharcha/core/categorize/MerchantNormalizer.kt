package com.example.kharcha.core.categorize

/**
 * Turns raw payee strings from SMS / statements into a stable key so that
 * "SWIGGY", "Swiggy Instamart", "swiggy@icici" and "UPI/P2M/612345678901/SWIGGY LIMITED"
 * all map to something starting with "swiggy".
 */
object MerchantNormalizer {

    private val upiPathPrefix = Regex("""^(?:UPI|IMPS|NEFT|RTGS)[/-](?:P2[AM][/-])?(?:\d{6,}[/-])?""", RegexOption.IGNORE_CASE)
    private val vpaHandle = Regex("""@[a-z0-9.\-]+$""")
    /** Stripped only from the end ("swiggy limited" → "swiggy"; "air india" is kept). */
    private val corporateSuffixes = setOf(
        "pvt", "private", "ltd", "limited", "llp", "inc", "technologies", "technology",
        "services", "retail", "online", "co", "corp", "corporation",
    )
    private val nonAlnum = Regex("""[^a-z0-9 ]""")
    private val digitsRun = Regex("""\b\d{4,}\b""")
    private val spaces = Regex("""\s+""")

    fun normalize(raw: String): String {
        var s = raw.trim()
        s = upiPathPrefix.replace(s, "")
        s = s.lowercase()
        s = vpaHandle.replace(s, "")
        // VPAs like "swiggy.stores" or "paytm-12345" → words
        s = s.replace('.', ' ').replace('-', ' ').replace('_', ' ')
        s = nonAlnum.replace(s, " ")
        s = digitsRun.replace(s, " ")
        val words = spaces.replace(s, " ").trim().split(' ').toMutableList()
        while (words.size > 1 && words.last() in corporateSuffixes) words.removeAt(words.lastIndex)
        return words.joinToString(" ")
    }

    /**
     * True when the payee looks like a person rather than a business, e.g. a phone-number
     * VPA ("9876543210@ybl") — used to suggest "Sent to People".
     */
    fun looksLikePerson(raw: String): Boolean {
        val vpaUser = raw.substringBefore('@', missingDelimiterValue = "").trim()
        return vpaUser.isNotEmpty() && vpaUser.all { it.isDigit() } && vpaUser.length >= 10
    }
}
