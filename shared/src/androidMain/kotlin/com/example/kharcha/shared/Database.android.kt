package com.example.kharcha.shared

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.kharcha.core.data.LedgerRepository
import com.example.kharcha.db.KharchaDb

/** Opens the on-device database (foreign keys on) and seeds built-in data. */
fun createRepository(context: Context): LedgerRepository {
    val driver = AndroidSqliteDriver(
        schema = KharchaDb.Schema,
        context = context.applicationContext,
        name = "kharcha.db",
        callback = object : AndroidSqliteDriver.Callback(KharchaDb.Schema) {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }
        },
    )
    return LedgerRepository(KharchaDb(driver)).also {
        it.seedDefaults()
        it.ensureCashAccount()
    }
}
