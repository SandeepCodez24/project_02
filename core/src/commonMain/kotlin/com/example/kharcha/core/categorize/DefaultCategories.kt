package com.example.kharcha.core.categorize

import com.example.kharcha.core.domain.Category
import com.example.kharcha.core.domain.CategoryKind

/**
 * Built-in categories with stable IDs (referenced by the merchant dictionary and
 * by sync, so never rename an ID — change the display name instead).
 */
object DefaultCategories {
    const val FOOD = "cat.food"
    const val GROCERIES = "cat.groceries"
    const val TRANSPORT = "cat.transport"
    const val FUEL = "cat.fuel"
    const val SHOPPING = "cat.shopping"
    const val BILLS = "cat.bills"
    const val RECHARGE = "cat.recharge"
    const val SUBSCRIPTIONS = "cat.subscriptions"
    const val RENT = "cat.rent"
    const val HEALTH = "cat.health"
    const val ENTERTAINMENT = "cat.entertainment"
    const val TRAVEL = "cat.travel"
    const val EDUCATION = "cat.education"
    const val INVESTMENTS = "cat.investments"
    const val EMI = "cat.emi"
    const val PERSONAL_CARE = "cat.personal_care"
    const val GIFTS = "cat.gifts"
    const val PEOPLE = "cat.people"
    const val OTHER_EXPENSE = "cat.other_expense"

    const val SALARY = "cat.salary"
    const val REFUND = "cat.refund"
    const val INTEREST = "cat.interest"
    const val OTHER_INCOME = "cat.other_income"

    val all: List<Category> = listOf(
        Category(FOOD, "Food & Dining", CategoryKind.EXPENSE, icon = "restaurant"),
        Category(GROCERIES, "Groceries", CategoryKind.EXPENSE, icon = "local_grocery_store"),
        Category(TRANSPORT, "Transport", CategoryKind.EXPENSE, icon = "directions_car"),
        Category(FUEL, "Fuel", CategoryKind.EXPENSE, icon = "local_gas_station"),
        Category(SHOPPING, "Shopping", CategoryKind.EXPENSE, icon = "shopping_bag"),
        Category(BILLS, "Bills & Utilities", CategoryKind.EXPENSE, icon = "receipt"),
        Category(RECHARGE, "Mobile & Internet", CategoryKind.EXPENSE, icon = "smartphone"),
        Category(SUBSCRIPTIONS, "Subscriptions", CategoryKind.EXPENSE, icon = "autorenew"),
        Category(RENT, "Rent", CategoryKind.EXPENSE, icon = "home"),
        Category(HEALTH, "Health", CategoryKind.EXPENSE, icon = "medical_services"),
        Category(ENTERTAINMENT, "Entertainment", CategoryKind.EXPENSE, icon = "movie"),
        Category(TRAVEL, "Travel", CategoryKind.EXPENSE, icon = "flight"),
        Category(EDUCATION, "Education", CategoryKind.EXPENSE, icon = "school"),
        Category(INVESTMENTS, "Investments", CategoryKind.EXPENSE, icon = "trending_up"),
        Category(EMI, "Loans & EMI", CategoryKind.EXPENSE, icon = "account_balance"),
        Category(PERSONAL_CARE, "Personal Care", CategoryKind.EXPENSE, icon = "spa"),
        Category(GIFTS, "Gifts & Donations", CategoryKind.EXPENSE, icon = "redeem"),
        Category(PEOPLE, "Sent to People", CategoryKind.EXPENSE, icon = "group"),
        Category(OTHER_EXPENSE, "Other", CategoryKind.EXPENSE, icon = "more_horiz"),
        Category(SALARY, "Salary", CategoryKind.INCOME, icon = "payments"),
        Category(REFUND, "Refunds & Cashback", CategoryKind.INCOME, icon = "undo"),
        Category(INTEREST, "Interest", CategoryKind.INCOME, icon = "savings"),
        Category(OTHER_INCOME, "Other Income", CategoryKind.INCOME, icon = "add_card"),
    )
}
