# Kharcha — expense tracker (Android + iOS)

Local-first, privacy-first spending tracker, India first (UPI/SMS), built with
Kotlin Multiplatform + Compose Multiplatform. `Kharcha` / `com.example.kharcha` are
placeholder names; rename before the first store release.

## Modules
| Module | What | Rules |
|---|---|---|
| `core/` | Pure Kotlin: money, domain models, budgets, SMS parser, categorizer, quick-entry parser, SQLDelight schema + `LedgerRepository` | **No Android/iOS/Compose deps.** All business logic goes here, with tests. Targets: jvm, iosArm64, iosSimulatorArm64 (Android consumes the jvm variant). |
| `shared/` | Compose Multiplatform UI + platform DB drivers | UI only — no business logic. Platform features are injected via `PlatformHooks`. |
| `androidApp/` | Android shell: `MainActivity`, `SmsReceiver`, later widgets / notification listener | Thin. |
| `iosApp/` | SwiftUI shell hosting Compose (`project.yml` → XcodeGen) | Swift only for what must be native: WidgetKit, App Intents (Shortcuts), Vision OCR. |

## Commands
- Core tests (works anywhere, incl. cloud sessions): `./gradlew -Pkharcha.coreOnly=true :core:jvmTest`
- Android debug build: `./gradlew :androidApp:assembleDebug`
- iOS (Mac only): `cd iosApp && xcodegen && open Kharcha.xcodeproj`, or `./gradlew :core:iosSimulatorArm64Test`
- `-Pkharcha.coreOnly=true` skips `:shared`/`:androidApp` and the Android Gradle Plugin entirely. Use it
  wherever the Android SDK or Google Maven (`dl.google.com`) is unavailable, e.g. Claude Code cloud sessions.

## Conventions (do not break)
- **Money** is `Money(minor: Long, currency)` — integer paise, never Double. Use `Money.parse` / `MoneyFormat`.
- **Sign convention:** line amounts are signed; negative = money out, positive = money in.
- **Transactions have ≥1 `TransactionLine`**; splits are just multiple lines. Category/tags live on lines.
- **Transfers** = two transactions sharing `transfer_group_id`; always excluded from spending reports.
- **Dedupe:** SMS/notification/import set `externalRef` (UPI ref when present, `ParsedSms.dedupeKey`); unique per account.
- **DB:** dates are ISO `TEXT`, timestamps epoch ms, booleans `INTEGER`. Every table has `updated_at` +
  `deleted` (soft delete) for future sync. Stay within **SQLite 3.18** syntax (Android API 26): no UPSERT,
  no window functions. Schema changes after the first release need `.sqm` migrations.
- **Category IDs** (`DefaultCategories`) are stable identifiers — never rename an ID.
- **IDs and clock are injected** (`LedgerRepository(newId, now)`) so tests are deterministic.
- **Privacy:** SMS text is parsed on-device and never stored or uploaded — only the extracted transaction.
  No analytics SDKs. `allowBackup=false` on Android.

## SMS parser (`core/.../sms/SmsParser.kt`)
- Accept only DLT business headers (`VM-HDFCBK-S`); ignore phone-number senders (spoofing).
- Reject before extracting: OTPs, collect requests, "will be debited", dues/reminders, failed txns, promos.
- **Every new bank format or fix needs a sample in `SmsParserTest` first** (made-up numbers, real shape).
- Confidence below `REVIEW_THRESHOLD` (0.7) ⇒ transaction is marked `needsReview`.

## Categorization order
user rules → merchant memory (learned from `recategorize`) → `MerchantDictionary` → heuristics →
(planned) opt-in LLM that receives only the merchant string + amount, never the ledger.

## Workflow
- One feature per branch/PR, tests first for logic in `core`. Run core tests before every commit.
- Keep UI code free of logic; if a composable needs a computation, add it to `core` with a test.
- Can't compile Android/iOS in a cloud session — say so explicitly in the PR when UI code wasn't built.

## Roadmap (current position: MVP week 1–3 foundation)
1. **MVP:** accounts/transfers, quick + manual entry, splits, tags, rules, budgets (weekly/bi-weekly/monthly,
   rollover), reports with custom ranges, CSV/PDF export, biometric lock — Android + iOS, local only.
2. **v1:** Android SMS + UPI-app notification listener; iOS Shortcuts "message received" automation →
   App Intent → `SmsParser`; recurring detection + bill calendar; goals/sinking funds; widgets;
   end-to-end-encrypted sync (op-log, QR-code device pairing, no account required).
3. **v2:** receipt OCR (ML Kit / Vision + LLM line items), natural-language queries (LLM → structured query
   run locally), multi-currency, Sankey/net-worth, forecasting.
4. **v3:** Account Aggregator (via licensed partner), households/shared budgets, email receipts.
