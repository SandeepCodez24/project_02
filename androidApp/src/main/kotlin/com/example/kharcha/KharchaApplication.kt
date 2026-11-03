package com.example.kharcha

import android.app.Application
import com.example.kharcha.core.data.LedgerRepository
import com.example.kharcha.shared.createRepository

class KharchaApplication : Application() {
    val repository: LedgerRepository by lazy { createRepository(this) }
}
