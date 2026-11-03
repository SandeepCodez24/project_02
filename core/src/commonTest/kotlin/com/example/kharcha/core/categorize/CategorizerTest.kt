package com.example.kharcha.core.categorize

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.example.kharcha.core.categorize.DefaultCategories as C

class MerchantNormalizerTest {
    @Test fun normalizesCommonShapes() {
        assertEquals("swiggy", MerchantNormalizer.normalize("SWIGGY"))
        assertEquals("swiggy", MerchantNormalizer.normalize("swiggy@icici"))
        assertEquals("swiggy", MerchantNormalizer.normalize("UPI/P2M/612345678901/SWIGGY LIMITED"))
        assertEquals("uber", MerchantNormalizer.normalize("uber@axisbank"))
        assertEquals("air india", MerchantNormalizer.normalize("AIR INDIA LTD"))
        assertEquals("acme", MerchantNormalizer.normalize("ACME PVT LTD"))
        assertEquals("amazon pay", MerchantNormalizer.normalize("AMAZON PAY"))
    }

    @Test fun detectsPersonVpas() {
        assertEquals(true, MerchantNormalizer.looksLikePerson("9876543210@ybl"))
        assertEquals(false, MerchantNormalizer.looksLikePerson("swiggy@icici"))
        assertEquals(false, MerchantNormalizer.looksLikePerson("RAJU KIRANA"))
    }
}

class CategorizerTest {

    @Test fun dictionaryMatchesWholeWords() {
        val c = Categorizer(emptyList(), emptyList())
        assertEquals(C.FOOD, c.suggest("SWIGGY")?.categoryId)
        assertEquals(C.TRANSPORT, c.suggest("OLA CABS")?.categoryId)
        assertEquals(C.TRAVEL, c.suggest("AIR INDIA LTD")?.categoryId)
        assertEquals(C.GROCERIES, c.suggest("UPI/P2M/612300001111/BIG BAZAAR")?.categoryId)
        assertNull(c.suggest("MOTOROLA SERVICE CENTRE")) // must not match "ola"
    }

    @Test fun userRulesBeatEverything() {
        val rule = CategorizationRule("r1", MatchType.CONTAINS, "amazon", C.GROCERIES, priority = 10)
        val c = Categorizer(listOf(rule), listOf(MerchantMemoryEntry("amazon", C.GIFTS, 3)))
        val s = c.suggest("AMAZON")
        assertEquals(C.GROCERIES, s?.categoryId)
        assertEquals(SuggestionSource.RULE, s?.source)
    }

    @Test fun memoryBeatsDictionary() {
        val c = Categorizer(emptyList(), listOf(MerchantMemoryEntry("swiggy", C.GROCERIES, 2)))
        val s = c.suggest("swiggy@icici")
        assertEquals(C.GROCERIES, s?.categoryId)
        assertEquals(SuggestionSource.MEMORY, s?.source)
    }

    @Test fun regexRulesAndInvalidRegex() {
        val ok = CategorizationRule("r1", MatchType.REGEX, "^raju\\b", C.GROCERIES)
        val broken = CategorizationRule("r2", MatchType.REGEX, "([", C.FOOD, priority = 99)
        val c = Categorizer(listOf(ok, broken), emptyList())
        assertEquals(C.GROCERIES, c.suggest("RAJU KIRANA")?.categoryId)
    }

    @Test fun heuristics() {
        val c = Categorizer(emptyList(), emptyList())
        assertEquals(C.PEOPLE, c.suggest("9876543210@ybl")?.categoryId)
        assertEquals(C.SALARY, c.suggest("ACME SALARY NOV", isCredit = true)?.categoryId)
        assertNull(c.suggest(""))
        assertNull(c.suggest(null))
    }
}
