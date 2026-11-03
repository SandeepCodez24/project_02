# Kharcha

A local-first expense tracker for Android and iOS, built with Kotlin Multiplatform and
Compose Multiplatform. Designed for India first: bank SMS / UPI auto-tracking, ₹ lakh/crore
formatting, and a privacy-first design where your data stays on your device.

> Working name. Package `com.example.kharcha` is a placeholder.

## What works today
- Core ledger: accounts, transactions with **splits** and **tags**, **transfers** that don't count as spending
- **Bank SMS parser** for HDFC, ICICI, SBI, Axis, Kotak and similar formats (UPI, card, NEFT, ATM),
  with OTP / spam / payment-request rejection and duplicate detection
- **Auto-categorization**: your rules → learned merchant memory → built-in Indian merchant dictionary
- **Quick entry**: `450 lunch swiggy`, `spent 1.2k on uber yesterday`, `+300 cashback`
- **Budgets** with weekly / bi-weekly / monthly (custom start day) periods and rollover
- Android app: home screen, quick add, SMS auto-import · iOS app: same shared UI

## Setup
Requirements: JDK 17+, Android Studio (with the Kotlin Multiplatform plugin), and for iOS a Mac with Xcode 16+.

```bash
./gradlew -Pkharcha.coreOnly=true :core:jvmTest   # logic tests, no Android SDK needed
./gradlew :androidApp:assembleDebug                # Android APK
```

iOS:
```bash
brew install xcodegen
cd iosApp && xcodegen && open Kharcha.xcodeproj
```

See [CLAUDE.md](CLAUDE.md) for architecture, conventions and the roadmap.
