package com.example.kharcha.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.kharcha.core.budget.BudgetPeriod
import com.example.kharcha.core.budget.DateRange
import com.example.kharcha.core.data.LedgerRepository
import com.example.kharcha.core.data.TransactionSummary
import com.example.kharcha.core.domain.Category
import com.example.kharcha.core.entry.QuickEntryParser
import com.example.kharcha.core.money.CurrencyCode
import com.example.kharcha.core.money.Money
import com.example.kharcha.core.money.MoneyFormat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

@Composable
fun App(repository: LedgerRepository, platform: PlatformHooks = PlatformHooks()) {
    KharchaTheme {
        HomeScreen(repository, platform)
    }
}

@Composable
fun KharchaTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF7FD8B0), secondary = Color(0xFFB4CCBC))
    } else {
        lightColorScheme(primary = Color(0xFF006C4C), secondary = Color(0xFF4D6357))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun HomeScreen(repository: LedgerRepository, platform: PlatformHooks) {
    val recent by remember(repository) { repository.observeRecent(limit = 100) }
        .collectAsState(initial = emptyList())
    val categories = remember(repository) { repository.categories().associateBy { it.id } }
    val cashAccountId = remember(repository) { repository.ensureCashAccount() }
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    val month = remember(today) { BudgetPeriod.Monthly().periodContaining(today) }
    // Recomputed whenever the feed changes (i.e. after any insert/delete).
    val monthSpend = remember(recent, month) {
        repository.spendingByCategory(month).values
            .fold(Money.zero(CurrencyCode.INR)) { acc, m -> acc + m }
    }
    val parser = remember { QuickEntryParser() }

    Scaffold(topBar = { TopAppBar(title = { Text("Kharcha") }) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SummaryCard(monthSpend, month) }

            val enableSms = platform.onEnableSmsTracking
            if (enableSms != null && !platform.smsTrackingEnabled) {
                item { SmsTrackingCard(onEnable = enableSms) }
            }

            item {
                QuickAddCard(
                    onSubmit = { text ->
                        val entry = parser.parse(text, today)
                        if (entry == null) {
                            "Include an amount, e.g. \"450 lunch\""
                        } else {
                            repository.addQuickEntry(entry, cashAccountId)
                            null
                        }
                    },
                )
            }

            if (recent.isEmpty()) {
                item {
                    Text(
                        "No transactions yet. Add one above, or turn on SMS tracking.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                item { Text("Recent", style = MaterialTheme.typography.titleMedium) }
                items(recent, key = { it.id }) { tx ->
                    TransactionRow(tx, categories[tx.categoryId])
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(spent: Money, month: DateRange) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Spent ${shortDate(month.start)} – ${shortDate(month.lastDay)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                MoneyFormat.format(spent),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun SmsTrackingCard(onEnable: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Track bank SMS automatically", style = MaterialTheme.typography.titleMedium)
            Text(
                "Kharcha reads only transaction messages from banks, on this phone. Nothing is uploaded.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onEnable) { Text("Turn on") }
        }
    }
}

@Composable
private fun QuickAddCard(onSubmit: (String) -> String?) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Quick add", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("450 lunch swiggy") },
                    singleLine = true,
                    isError = error != null,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        error = onSubmit(text)
                        if (error == null) text = ""
                    },
                    enabled = text.isNotBlank(),
                ) { Text("Add") }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TransactionRow(tx: TransactionSummary, category: Category?) {
    val isInflow = !tx.total.isNegative
    val subtitle = buildString {
        append(if (tx.isTransfer) "Transfer" else category?.name ?: "Uncategorized")
        append(" · ")
        append(shortDate(tx.date))
        if (tx.lineCount > 1) append(" · split")
    }
    ListItem(
        headlineContent = { Text(tx.payee ?: "Unknown", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (isInflow) "+" else "") + MoneyFormat.format(tx.total.abs()),
                    fontWeight = FontWeight.SemiBold,
                    color = if (isInflow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (tx.needsReview) {
                    Text("Review", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
    )
}

private fun shortDate(date: LocalDate): String =
    "${date.day} ${date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"
