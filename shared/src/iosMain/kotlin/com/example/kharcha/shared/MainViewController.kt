package com.example.kharcha.shared

import androidx.compose.ui.window.ComposeUIViewController
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.example.kharcha.core.data.LedgerRepository
import com.example.kharcha.db.KharchaDb
import platform.UIKit.UIViewController

private val repository: LedgerRepository by lazy {
    val driver = NativeSqliteDriver(
        schema = KharchaDb.Schema,
        name = "kharcha.db",
        onConfiguration = { config ->
            config.copy(extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true))
        },
    )
    LedgerRepository(KharchaDb(driver)).also {
        it.seedDefaults()
        it.ensureCashAccount()
    }
}

/** Entry point called from SwiftUI (see iosApp/iosApp/ContentView.swift). */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController {
    App(repository)
}
