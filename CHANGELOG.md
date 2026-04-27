# Changelog

All notable changes to **Scan for Android** are recorded here. The format is loosely based on [Keep a Changelog](https://keepachangelog.com/) and the project follows [Semantic Versioning](https://semver.org/) for `versionName`. `versionCode` is auto-incremented per build by `Scan/build.gradle.kts` and tracked in `version.properties`.

The short, user-facing version of these notes ships to Google Play under `Scan/src/main/play/release-notes/<locale>/default.txt`. Keep that one ≤500 characters per locale.

## [Unreleased]

_(nothing yet)_

## [1.0] — 2026-04-27

First public release.

### Scanning

- Live camera scanning of QR, Aztec, PDF417, Data Matrix, EAN-8, EAN-13, UPC-A, UPC-E, Code 39, Code 93, Code 128, ITF, and Codabar via CameraX + ML Kit (bundled barcode model — no on-demand download).
- `MlKitAnalyzer` with `COORDINATE_SYSTEM_VIEW_REFERENCED` so detected codes report their bounding box in the PreviewView's own coordinate space.
- Tracking corner-bracket reticle (`ReticleOverlay`) that snaps to the detected code via `animateRectAsState` with a spring matching the iOS feel.
- Photo Picker import using `ActivityResultContracts.PickVisualMedia` — does **not** request `READ_MEDIA_IMAGES` on Android 13+.
- Torch toggle, dedupe window of 1.5 s on repeat decodes.

### Smart payload decomposition (Kotlin port of the iOS parsers)

- Web: URL, `mailto:`, `tel:`, `sms:` / `smsto:`.
- Connectivity: `WIFI:` (SSID + password + security + hidden flag).
- Geolocation: `geo:` URIs.
- Identity: vCard 3.0, MECARD.
- Calendar: iCalendar VEVENT (line-folded, UTC / TZID / all-day).
- Authentication: `otpauth://`.
- Retail: EAN-8 / EAN-13 / UPC-A / UPC-E / ITF-14 product codes.
- Cryptocurrency: Bitcoin (BIP-21), Ethereum (EIP-681 with chain ID), Litecoin, Bitcoin Cash, Dogecoin, Monero, Cardano, Solana, Lightning (BOLT-11).
- Bank payments: EPC SEPA / GiroCode (EU), Swiss QR-bill (SPC), Czech SPD (Spayd), Slovak Pay by Square (recognition only), Russian unified payment (ST00012 / ST00011), EMVCo Merchant QR with nested-template drilling for Pix / PayNow / PromptPay / CoDi / UPI-via-EMVCo / DuitNow / QRIS / FPS / NAPAS / NETS, Indian UPI (`upi://pay`), Bezahlcode (`bank://` / `bezahlcode://`), Serbian NBS IPS QR (PR / PT / PK).
- Mobile-payment apps: Swish (Sweden), Vipps (Norway), MobilePay (Denmark / Finland), Bizum (Spain), iDEAL (Netherlands).
- Receipts: Russian FNS retail receipt, Serbian SUF fiscal receipt.

### Smart actions

Per payload type, dispatched via standard Android intents so any installed handler picks them up:

- URL → browser, Email → `ACTION_SENDTO mailto:`, Phone → `ACTION_DIAL`, SMS → `ACTION_SENDTO sms:`.
- Wi-Fi → show SSID / security, copy password, open `Settings.ACTION_WIFI_SETTINGS`.
- Location → `geo:` intent (Google Maps, OsmAnd, etc.).
- Contact → `ContactsContract.Intents.Insert.ACTION` with vCard fields pre-filled.
- Calendar → `Intent.ACTION_INSERT` on `CalendarContract.Events.CONTENT_URI`.
- Crypto / UPI / regional payment URIs → `ACTION_VIEW` so the OS picks the wallet / banking app.
- Serbian SUF receipt → opens the official PURS verification page.
- All payloads: copy raw value to clipboard, share via `ACTION_SEND`.

### Generation

- ZXing-based generator for QR, Aztec, PDF417, Code 128.
- `CodeComposer.vCard()` and `CodeComposer.wifi()` produce well-formed input strings with proper escaping.
- Save to `MediaStore` under `Pictures/Scan/`, share via `ACTION_SEND`, copy the encoded text to clipboard.
- Live preview, integer-scaled rendering for crisp module edges.

### History

- Saved scans persist to Room (`scan_database` / `scan_records`).
- Searchable list with relative timestamps and a payload-kind icon.
- Per-record bottom sheet with editable notes (auto-persist on change), smart actions, and delete.

### Tooling & infra

- Compose Material 3 dark theme. Single-Activity, Compose-only navigation.
- Hilt + KSP DI; `@HiltViewModel` constructor injection.
- Auto-incrementing `versionCode` via `version.properties` + a `doLast` finalizer hooked to assemble*/bundle* — mirrors the iOS app's `agvtool bump` post-build action.
- Tag-driven CI release pipeline at `.github/workflows/release.yml`: pushing `vX.Y.Z` builds a signed AAB with `versionName=X.Y.Z` and uploads it to Play's internal-testing track.
- Adaptive launcher icon (vector gradient background + transparent QR foreground extracted from the iOS asset).
- 38 unit tests under `Scan/src/test/java` covering every parser, run with JUnit 4 + Robolectric.
