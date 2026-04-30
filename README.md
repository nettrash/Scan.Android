# Scan.Android

Android port of [Scan](https://github.com/nettrash/Scan), an app for reading and generating 1D and 2D barcodes. Built in Jetpack Compose on top of CameraX + ML Kit (live scanning), ZXing (generation), Room (history), and Hilt (DI). The point of the app is not just to *decode* a code, but to *understand* what's in it: scan a Wi-Fi QR and it shows the SSID and offers to open Wi-Fi Settings; scan a SEPA invoice and it surfaces the IBAN, beneficiary, and amount as separate copyable rows; scan a fiscal receipt and it shows the fiscal markers; and so on.

The payload-decomposition layer is a line-for-line Kotlin port of the iOS app's parsers — same recognised formats, same field labelling, same smart actions where the platform offers an equivalent intent.

## Features

### Scanning

Live-camera scanning of every symbology ML Kit's bundled barcode model supports: **QR**, **Aztec**, **PDF417**, **Data Matrix**, **EAN-8 / EAN-13**, **UPC-A / UPC-E**, **Code 39**, **Code 93**, **Code 128**, **ITF**, and **Codabar**. The viewfinder uses CameraX's `PreviewView` with an `ImageAnalysis` use-case running the ML Kit `BarcodeScanner` on each frame; results are streamed to the UI via a `StateFlow` with a 1.5 s dedupe window so the analyzer can run at full frame-rate without spamming the result sheet. Torch toggle, Photo-Picker import (uses `ActivityResultContracts.PickVisualMedia` so no `READ_MEDIA_IMAGES` permission is required), inline error surface for malformed images.

### Smart payload decomposition

The decoded string is parsed and rendered as structured fields with per-row tap-to-copy. Recognised formats:

| Domain | Formats |
| --- | --- |
| Web / messaging | URL, `mailto:`, `tel:`, `sms:` / `smsto:` |
| Connectivity | `WIFI:` (SSID + password + security) |
| Geolocation | `geo:` |
| Identity | vCard (3.0), MECARD |
| Calendar | iCalendar VEVENT (line-folded, UTC / TZID / all-day dates) |
| Authentication | `otpauth://` |
| Retail | EAN-8 / EAN-13 / UPC-A / UPC-E / ITF-14 product codes |
| Cryptocurrency | Bitcoin (BIP-21), Ethereum (EIP-681 with chain ID), Litecoin, Bitcoin Cash, Dogecoin, Monero, Cardano, Solana, Lightning (BOLT-11) |
| Bank payments | EPC SEPA Payment QR / GiroCode (EU), Swiss QR-bill (SPC), Czech SPD (Spayd), Slovak Pay by Square (recognition only — decoding needs LZMA), EMVCo Merchant QR with nested-template drilling for Pix, PayNow, PromptPay, CoDi, UPI-via-EMVCo, DuitNow, QRIS, FPS, NAPAS, NETS and friends, Indian UPI (`upi://pay`), Bezahlcode (German legacy `bank://` / `bezahlcode://`), Serbian NBS IPS QR (Prenesi — PR / PT / PK) |
| Mobile-payment apps | Swish (Sweden, base64-JSON-encoded `swish://`), Vipps (Norway), MobilePay (Denmark / Finland), Bizum (Spain), iDEAL (Netherlands) |
| Receipts | Serbian SUF fiscal receipt |

### Smart actions

Per payload type, dispatched via standard Android Intents so any installed handler app picks them up:

- **URL** — `ACTION_VIEW` with the URI.
- **Email / Phone / SMS** — `ACTION_SENDTO` with `mailto:` / `tel:` / `sms:`, with `subject` / `body` query params populated.
- **Wi-Fi** — Show network details, copy password, open `Settings.ACTION_WIFI_SETTINGS`.
- **Location** — `ACTION_VIEW` with `geo:` URI; resolved by Google Maps, OsmAnd, etc.
- **Contact** — `ContactsContract.Intents.Insert.ACTION` opens the system "New Contact" form pre-filled from the vCard / MECARD fields.
- **Calendar** — `ACTION_INSERT` against `CalendarContract.Events.CONTENT_URI` opens the system "Add Event" form with summary / location / description / start / end / all-day pre-filled.
- **Crypto** — `ACTION_VIEW` with the original BIP-21 / EIP-681 / BOLT-11 URI; the OS picks an installed wallet via the URI scheme.
- **Bank payments** — Per-field copy (IBAN, amount, recipient, reference, INN, KPP, KBK, OKTMO, Czech variable / constant / specific symbols, …). Currency mapped via ISO 4217 numeric → alpha for EMVCo. Nested EMVCo templates render with a "↳" marker so individual sub-fields (Pix key, PayNow merchant ID, PromptPay phone, etc.) are individually copyable.
- **UPI** — `ACTION_VIEW` with the `upi://` URI; the OS picks an installed UPI app — PhonePe, GPay, Paytm, BHIM…
- **Mobile-payment apps** — `ACTION_VIEW` with the registered URI scheme.
- **Serbian SUF receipt** — Open the official PURS verification page.
- All payloads — Copy raw to the system clipboard, Share via `ACTION_SEND`.

### Generation

A dedicated **Generate** tab builds 1D / 2D codes from structured input via ZXing's `MultiFormatWriter`:

- **Inputs** — Text, URL, Contact (emits well-formed vCard 3.0), Wi-Fi (emits the standard `WIFI:` payload with proper escaping).
- **Symbologies** — QR, Aztec, PDF417, Code 128.
- **Outputs** — Save the rendered PNG to `MediaStore` under `Pictures/Scan/`, share via `ACTION_SEND` with the resulting content URI, or copy the encoded *string* to the clipboard.
- Live preview that re-renders on every keystroke; integer-scaled rendering for crisp module edges.

### History

- Saved scans persist to **Room** (`scan_database` / `scan_records` table), exposed to Compose as a `StateFlow<List<ScanRecord>>`.
- Searchable list with relative timestamps and a payload-kind icon.
- Per-record detail bottom sheet with editable notes (auto-persist on change), smart actions, and delete.

### App icon

The launcher icon takes the QR motif from the iOS asset (a real, scannable QR code that decodes to `https://nettrash.me`) and presents it on a deep-blue radial gradient. The yellow viewfinder brackets from the iOS icon are intentionally omitted on Android — they fought with the various launcher mask shapes (squircle, teardrop, circle) which clipped one or two of the four brackets unevenly. Packaged as an adaptive icon: the cleaned QR is the foreground (transparent-background per-density PNGs at `mipmap-*/ic_launcher_foreground.png`, 108–432 px) and the gradient is a vector drawable (`drawable/ic_launcher_background.xml`). Legacy `ic_launcher.png` and round `ic_launcher_round.png` are composed at build-time from the same two layers so pre-Android 8 launchers see the identical look.

## Requirements

- **`compileSdk` / `targetSdk`**: 36 (mirrors `Geo.Android`).
- **`minSdk`**: 28 (Android 9).
- **JDK**: 17 (set via `compileOptions` and `kotlin.compilerOptions.jvmTarget`).
- **Gradle**: 9.4.1 (per `gradle/wrapper/gradle-wrapper.properties`).
- **AGP**: 9.1.1.
- **Kotlin**: 2.3.20.
- **Android Studio**: latest stable that ships with a Compose Compiler matching Kotlin 2.3.

## Permissions

Declared in `AndroidManifest.xml`:

| Permission | Used for |
| --- | --- |
| `android.permission.CAMERA` | Live barcode scanning. Requested at runtime via Accompanist Permissions when the user first opens the Scan tab. |
| `android.permission.INTERNET` | Reserved for future use; no network calls today. |
| `android.permission.READ_MEDIA_IMAGES` | Declared for SDK 33+, but the Photo Picker flow uses `ActivityResultContracts.PickVisualMedia` and does **not** require this permission to be granted on Android 13+. |
| `android.permission.READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) | Legacy storage for picked-image reads on Android 12 and below. |

Hardware features:

- `android.hardware.camera` — required.
- `android.hardware.camera.autofocus` — optional.

The app never reads the user's contacts, calendar, or photos directly — every privileged action is mediated by a system-supplied edit-and-save UI launched via Intent.

## Building

```sh
git clone https://github.com/nettrash/Scan.Android.git
cd Scan.Android
./gradlew :Scan:assembleDebug
```

Or open the project root in Android Studio. The first sync downloads the Gradle distribution (9.4.1) and the AGP / Compose / CameraX / ML Kit / ZXing / Room / Hilt artifacts via the `google()` and `mavenCentral()` repositories declared in `settings.gradle.kts`. Subsequent builds are incremental.

To install on a connected device or emulator:

```sh
./gradlew :Scan:installDebug
```

## Testing

Unit tests live under `Scan/src/test/java/me/nettrash/scan/` and mirror the iOS `ScanTests/ScanTests.swift` suite line for line — same fixtures, same assertions, against the Kotlin port of every parser. They run on the JVM with **JUnit 4 + Robolectric** (Robolectric provides a real `android.net.Uri` and `android.util.Base64` so the parsers that touch those APIs exercise the same code paths as on a device).

Coverage:

- `data/payload/ScanPayloadParserTest` — URL, Wi-Fi (with escaped semicolon), `mailto:` / `tel:` / `sms:` / `geo:`, vCard 3.0, MECARD, EAN-13 product code, kind labels, EPC-priority-over-text.
- `data/payload/BankPaymentParserTest` — EPC SEPA Payment, EMVCo Merchant QR (top-level + nested-template drilling for Pix), Swiss QR-bill (31-line minimum), Serbian SUF receipt URL, Serbian NBS IPS QR.
- `data/payload/CryptoUriParserTest` — Bitcoin (BIP-21), Ethereum with `@chainId` (EIP-681), Lightning (BOLT-11).
- `data/payload/CalendarParserTest` — VEVENT (UTC `Z` suffix), all-day event (`VALUE=DATE`).
- `data/payload/RegionalPaymentParserTest` — UPI (`upi://pay`), Czech SPD (with `+`-as-space), Slovak Pay by Square recognition + lookalike rejection, Bezahlcode (`bank://`), Swish (base64-JSON `data=` blob), Vipps.
- `generator/CodeComposerTest` — vCard composer + parser round-trip, Wi-Fi composer + parser round-trip, open-network composer omits the `P:` field.

Run from the terminal:

```sh
./gradlew :Scan:testDebugUnitTest                     # the standard bucket
./gradlew :Scan:test                                  # both debug + release
./gradlew :Scan:test --tests "*BankPaymentParserTest" # one class
```

In Android Studio, right-click `Scan/src/test/java` in the Project pane → **Run 'Tests in 'java''**, or click the green ▶ in the gutter next to a test class / method.

The unit-test bucket doesn't trigger the `assemble*` / `bundle*` lifecycle, so running tests does **not** bump `versionCode`.

## Project structure

```
Scan.Android/
├─ build.gradle.kts                    root project plugins
├─ settings.gradle.kts                 single :Scan module
├─ gradle/libs.versions.toml           version catalog
└─ Scan/
   ├─ build.gradle.kts                 module config (compose, hilt, ksp, room)
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ res/                          themes, strings, adaptive launcher icons
      └─ java/me/nettrash/scan/
         ├─ ScanApplication.kt         @HiltAndroidApp + WorkManager Configuration
         ├─ MainActivity.kt            single-activity Compose host
         │
         ├─ di/AppModule.kt            Hilt module — Room database + DAO
         │
         ├─ data/
         │  ├─ db/                     Room: ScanRecord entity, DAO, database
         │  └─ payload/                payload models + parsers (port of iOS)
         │     ├─ ScanPayload.kt       sealed class + master parser
         │     ├─ BankPaymentPayloads.kt  EPC, Swiss, EMVCo, Serbian
         │     ├─ RegionalPaymentPayloads.kt  UPI, Czech SPD, Pay by Square, Bezahlcode, Swish, Vipps, MobilePay, Bizum, iDEAL
         │     ├─ CryptoPayload.kt     BIP-21 / EIP-681 / BOLT-11
         │     ├─ CalendarPayload.kt   RFC 5545 VEVENT parser
         │     └─ LabelledField.kt
         │
         ├─ scanner/                   live + still-image scanning
         │  ├─ Symbology.kt            ML Kit format → display name mapping
         │  ├─ ScannedCode.kt
         │  ├─ BarcodeAnalyzer.kt      CameraX ImageAnalysis.Analyzer + ML Kit
         │  └─ ImageDecoder.kt         decode from a content Uri (Photo Picker)
         │
         ├─ generator/                 code generation
         │  ├─ CodeGenerator.kt        ZXing MultiFormatWriter wrapper
         │  └─ CodeComposer.kt         vCard 3.0 + WIFI: composers
         │
         └─ ui/                        Compose UI
            ├─ MainScreen.kt           NavigationBar host (Scan / Generate / History)
            ├─ theme/                  Material 3 dark theme
            ├─ scanner/                ScannerScreen + ScannerViewModel + result sheet
            ├─ generator/              GeneratorScreen
            ├─ history/                HistoryScreen + HistoryViewModel + ScanDetailDialog
            └─ components/             PayloadActions (smart actions + LabelledFieldsList)
```

## Architecture notes

- **Single Activity, Compose-only navigation.** `MainActivity` hosts `MainScreen` which wires `androidx.navigation.compose.NavHost` to three composable destinations.
- **Hilt + KSP for DI.** `@HiltViewModel` classes are constructor-injected with their `ScanRecordDao`. The single `AppModule` provides the Room database as a `@Singleton`.
- **CameraX + ML Kit on the analyzer thread.** `BarcodeAnalyzer` is bound as an `ImageAnalysis.Analyzer` with `STRATEGY_KEEP_ONLY_LATEST` and runs on a single-thread executor. Successful decodes are forwarded to the view model on the main thread; the dedupe filter is checked there to avoid races on the dedupe state.
- **Room exposes `Flow<List<ScanRecord>>`** which the history view model collects with `stateIn(SharingStarted.Eagerly)` so the list survives configuration changes without re-querying.
- **Privileged actions are intent-launched, never directly performed.** Add-to-Contacts and Add-to-Calendar both go through the system-provided edit forms; the app never holds `READ_CONTACTS` or `READ_CALENDAR`. Save-to-Photos uses scoped storage via `MediaStore.Images.Media.RELATIVE_PATH = "Pictures/Scan"`.
- **`versionCode` is auto-bumped after every successful build.** Mirrors the iOS app's `agvtool bump` post-build action. The current value lives in `version.properties` at the repo root (tracked in git); after each successful `assembleDebug` / `assembleRelease` / `bundleDebug` / `bundleRelease` a `doLast` finalizer rewrites the file with `versionCode + 1`. The new value is effective on the *next* build. Pass `-PnoBump` to skip the bump for one invocation; the GitHub Actions release workflow uses this so CI runs don't produce uncommitted file diffs on the runner. Commit `version.properties` along with your release so the bump propagates between machines. `versionName` defaults to `1.0` but can be overridden with `-PversionName=1.2.3` — the release workflow does this from the pushed git tag (`v1.2.3` → `1.2.3`).

## Roadmap

- ML Kit barcode scanner module download via Play Services for smaller APKs (currently using the bundled model for offline-first behaviour).
- Real decoding of Slovak Pay by Square — would need an LZMA Kotlin/Java port (e.g. XZ for Java); today the format is recognised and the user can route the raw token to a banking app via Share / Copy.
- Localised field labels for the Serbian, Czech, and Indian formats (currently English).
- Boarding-pass (BCBP), AAMVA driver's-licence, GS1 Application Identifier decoders.
- Home-screen widget showing recent scans, mirroring the Geo.Android Glance widget pattern.

## License

MIT — see [`LICENSE`](LICENSE).
