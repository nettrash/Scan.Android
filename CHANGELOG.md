# Changelog

All notable changes to **Scan for Android** are recorded here. The format is loosely based on [Keep a Changelog](https://keepachangelog.com/) and the project follows [Semantic Versioning](https://semver.org/) for `versionName`. `versionCode` is auto-incremented per build by `Scan/build.gradle.kts` and tracked in `version.properties`.

The short, user-facing version of these notes ships to Google Play under `Scan/src/main/play/release-notes/<locale>/default.txt`. Keep that one ≤500 characters per locale.

## [Unreleased]

_(nothing yet)_

## [1.10] — 2026-11-14

Scan now explains codes, checks them for tricks before you act on them, reads
printed text and NFC tags, collects codes in bulk, and decodes the identity,
product-passport and e-invoice formats EU wallets and regulations introduced —
plus Passkey QR recognition and GS1 / driving-licence parser fixes. Mirrors the
iOS 1.10 work: parsers, safety rules, redaction, NFC bytes and CSV output are
identical on both platforms for identical input. `versionName` default in
`Scan/build.gradle.kts` bumped from `1.9` to `1.10`; `versionCode` continues to
auto-increment from `version.properties`. Scanned content is processed on the
device; the new network traffic is Google ML Kit's (translation models after
consent, plus its Firebase and telemetry calls — see Notes).

### Added

- **"What is this code?"** on every result sheet (scanner, History, App Links,
  Share to Scan): a plain-language explanation from Scan's built-in,
  deterministic explainer. Credential-shaped content (Wi-Fi passwords,
  `otpauth` secrets, private keys, seed phrases, API tokens, OpenID4VCI
  pre-authorised codes, long opaque blobs) is blanked first, and the cautions
  are always the rule engine's. (`Assistant.kt`, `HeuristicExplainer.kt`,
  `SecretRedaction.kt`)
- **"Describe the code"** at the top of the Generate tab — a sentence such as
  "Wi-Fi for network BlueHouse, password hunter2" fills in a text, link, Wi-Fi
  or contact form. (`ui/generator/ComposeFormFill.kt`)
- **QR safety check** — the graded note / caution / danger banner above the
  smart actions, with the same 28 rules as iOS: punycode / mixed-alphabet and
  look-alike hosts (including a brand pasted in front of someone else's
  domain), `user@host` and backslash address tricks, raw and numeric IP hosts,
  odd ports, open redirects, shorteners, plain HTTP, abuse-heavy TLDs, encoded
  or oversized links, bait wording, dangerous schemes, exposed secrets
  (private / WIF keys, BIP-39 seed phrases), 2FA enrolment codes, open Wi-Fi,
  payee-less or preset-amount payment codes, static posters, and more than one
  code in the frame. While a code is graded danger, its own Open / Verify /
  Open-in buttons become "Copy link" until "I understand — open anyway"; a
  link inside a contact card asks first when that link alone is dangerous.
  Ordinary links, internationalised domains included, raise nothing.
  (`QrRisk.kt`, `PayloadActions.kt`)
- **Settings → Insights** — switches for the assistant and the safety check
  (both on by default), the "which engine answers" line and the Translate
  target language. Every result surface reads them through
  `LocalScanSettings`.
- **Live Text and Bulk modes** — the Scan tab's mode switch, remembered between
  launches. Live Text (ML Kit text recognition, Latin script, bundled model)
  lists the recognised text blocks; tapping one opens a card with Copy, Share
  and Translate that stays until dismissed, and a code that stays in view
  opens its sheet once per stay. Bulk collects every distinct code with a
  sighting count (up to 500, in memory only); the list survives mode and tab
  switches until Clear, "Save all to History" writes only codes not saved yet
  (a failed save leaves the unwritten ones for a retry), and "Export CSV"
  shares `value,symbology,kind,sightings,first_seen`. (`TextScanner.kt`,
  `BulkScanSession.kt`, `ScanModeChrome.kt`, `BulkCaptureSheet.kt`)
- **Translation** — ML Kit on-device translation plus bundled ML Kit language
  identification for the source language (new dependency
  `com.google.mlkit:language-id`). The target is Settings → Insights →
  "Translate to" (default: the first preferred language ML Kit can translate
  into, otherwise English). A missing language model (~30 MB) is never
  fetched silently: a dialog names it, says it comes from Google, says what
  else ML Kit sends Google, flags mobile data, and waits for the user. Text
  the identifier can't place offers a "Choose the language" picker.
  (`TranslationKit.kt`, `ui/components/TranslatePanel.kt`)
- **NFC read and write** — a read button beside the import menu feeds a tag's
  first readable record (text, URI, Smart Poster, Wi-Fi configuration, vCard)
  into the normal result sheet; "Write to NFC tag" on the Generate tab writes
  the encoded content. Records are byte-identical to iOS: web, phone, e-mail,
  SMS and map links as RTD URI, WPA/WPA2 and open Wi-Fi as a Wi-Fi Simple
  Configuration token, contacts as `text/vcard`, anything else as text. Reader
  mode runs only after a tap and stops when the screen pauses; blank tags are
  NDEF-formatted on write; switched-off NFC offers "Turn on NFC"; devices
  without NFC show no NFC controls. (`nfc/`)
- **Digital-identity codes** (decode only — no hand-off, no `request_uri`
  fetch, no signature verification): ISO/IEC 18013-5 `mdoc:` engagement QR
  codes and the second-edition reader QR, ISO/IEC 18013-7 `mdoc://`, OpenID4VP
  1.0 / HAIP requests incl. DCQL (biometric claims flagged), OpenID4VCI 1.0
  offers (the pre-authorised code is never shown) and SD-JWT VCs.
  (`IdentityPayload.kt`, `VerifiableCredentialPayload.kt`, shared
  `CBORReader.kt`)
- **EN 16931 e-invoices (CII)** — ZUGFeRD / Factur-X / XRechnung in CII syntax,
  raw XML or base64: guideline, number, seller and the seller's own VAT id,
  buyer, Leitweg-ID, IBAN, total with currency, tax, dates.
  (`InvoicePayload.kt`)
- **Digital Product Passport** — a GS1 Digital Link with a `dpp` path segment
  or query key: product, batch, serial, dates, countries of origin and
  processing (AIs 422–426, full ISO 3166 table), resolver host (shown, never
  contacted), every other GS1 element and "Look up GTIN".
  (`DigitalProductPassport.kt`, `GS1Payload.kt`)
- **QR styles** — module and eye shapes, gradient inks, a logo and presets,
  identical to iOS: High error correction for shapes and logos, a whole-module
  logo clearing sized so the code still decodes (verified with ZXing), the
  contrast check on every ink, and warnings for dense dot codes and shaped
  codes with a logo. PNG, SVG and PDF keep the style; an unstyled code is
  byte-for-byte unchanged. (`QRStyle.kt`, `CodeSvg.kt`)
- **Home-screen widget, Quick Settings tile, launcher shortcuts** — the Android
  counterpart of the iOS App Intents. The widget lists recent scans by kind
  (plus the host of a link or the name of a Wi-Fi network — never a password,
  secret or full value) and refreshes while the app runs; the tile opens the
  camera; the shortcuts ("Scan a code", "Generate a code", "Recent scans") go
  through a trampoline activity so a running app keeps its state. "Scan a
  code" closes whatever covers the camera. (`widget/`, `tile/`,
  `res/xml/shortcuts.xml`)
- **What's New** sheet for 1.10.
- **Passkey QR (FIDO cross-device sign-in)** — recognises the `FIDO:/`
  hybrid-transport codes (CTAP 2.2) that websites show for the
  phone-as-authenticator flow. Decodes the digit-encoded CBOR to show what
  the code is for (sign in vs. create a passkey), when it was generated,
  tunnel-server count and device-linking support, with a phishing caution.
  The embedded one-time secret is never displayed, and the result sheet
  explains that only the system camera flow can complete the handshake.
  Mirrors the iOS app. (`PasskeyPayload.kt`)

### Changed

- The camera permission rationale mentions Live Text ("…and, in Live Text
  mode, printed text. Frames are processed on this device and are not
  saved.").
- History and Bulk CSV exports neutralise spreadsheet formulas: a cell starting
  with `=`, `+`, `-`, `@`, a tab or a carriage return gets a leading
  apostrophe. (`HistoryCsv.kt`)
- A generated code with a logo and no other styling now uses the style
  renderer (4-module quiet zone, the logo on a cleared square instead of a
  22 % white punch, logo kept in SVG and PDF).
- JSON inside scanned codes (credentials, Swish `data=`) is refused past 64
  nesting levels, as CBOR already is — the same limit as iOS.
- R8 keeps Glance's `ActionCallback` constructors (`proguard-rules.pro`), so
  the widget's Refresh works in release builds.

### Fixed

- **GS1 (FNC1 form)** — a scanned GS1 barcode whose group separators sit
  between elements (the normal `01…<GS>10…` shape) is now recognised
  instead of falling back to plain text, and a separator after a
  fixed-length AI no longer swallows the next element's first digit (which
  silently dropped the batch/lot).
- **AAMVA driving licence** — the licence number (`DAQ`, welded onto the
  subfile type with no separator) is no longer the one field the parser
  dropped.
- Lint: the multi-code chip overlay no longer uses an unused
  `BoxWithConstraints` scope (`UnusedBoxWithConstraintsScope`).
- `test-fixtures/43-emvco-pix.png` is regenerated with correct TLV lengths and
  CRC; it used to decode as plain text instead of a merchant QR.

### Notes

- New dependencies: ML Kit `text-recognition` 16.0.1 and `language-id` 17.0.6
  (both bundled models), ML Kit `translate` 17.0.3, Glance 1.2.0, and
  `androidx.work:work-runtime-ktx` pinned to 2.12.0 (Glance asks for 2.7.1).
  Together they add roughly 27 MB per arm64 device.
- WorkManager merges `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and
  `FOREGROUND_SERVICE` into the manifest (Glance runs widget sessions on it;
  Scan schedules no work of its own). `NFC` is new; `ACCESS_NETWORK_STATE` is
  used for the download dialog's mobile-data line.
- Network: ML Kit translation downloads its language models from Google after
  the user agrees, registers a Firebase installation ID and fetches remote
  configuration when a translator is created, and — like the barcode scanner
  before 1.10 — sends Google usage and diagnostic statistics (including the
  language pair). Scanned text is never sent. The Play Data-safety answers
  and the privacy policy must reflect this (see `DATA_SAFETY.md`).

## [1.9] — 2026-06-12

"Open in …" hand-off reliability — every payment / wallet / magnet button now
does something useful when the target app isn't installed, and UPI lets you
pick the app instead of silently routing to WhatsApp. Mirrors the iOS 1.9 work.

> Versions jump 1.7 → 1.9 to stay in lockstep with iOS. There is no Android 1.8:
> iOS 1.8 was the Mac Catalyst + visionOS release, which has no Android counterpart.

### Added

Six new payload types, byte-for-byte matching the iOS 1.9 detectors (shared
`test-fixtures/`, parallel `ScanPayloadParserTest` cases):

- **WalletConnect** (`wc:…@2` / `@1`) — dApp↔wallet pairing QR; "Open in wallet"
  with the Play Store fallback. (`CryptoPayload.kt`)
- **Nostr** — `nostr:` URIs and bare NIP-19 tokens (`npub1…`, `note1…`, …); an
  `nsec1…` secret key shows a do-not-share warning instead of an open action.
  (`CryptoPayload.kt`)
- **Google Authenticator export** (`otpauth-migration://…`) — a hand-rolled
  protobuf reader lists the exported accounts (issuer + name + TOTP/HOTP);
  secrets are never shown. (`ScanPayload.kt`)
- **Open Location Code / Plus Code** (`849VCWC8+R9` + optional locality) →
  "Open in Maps". (`ScanPayload.kt`)
- **what3words** (`///filled.count.soap`, `w3w://…`) → opens what3words.com.
  (`ScanPayload.kt`)
- **IBAN** — ISO 7064 mod-97 + country-prefix validated, grouped for display.
  (`BankPaymentPayloads.kt`)

Personal-payment handles are branded with an "Open in …" action (Play Store
fallback): **PayPal**, **Venmo**, **Cash App**, **Revolut**, **TWINT**,
**Alipay**, **WeChat Pay** — plus **Signal**, **Matrix**, and **Zoom / Teams /
Google Meet** links. (`RichURLPayload.kt`)

### Fixed

Custom-scheme hand-offs (`bitcoin:`, `ethereum:`, `lightning:`, `litecoin:`,
`magnet:`, `swish:`, `vipps:`, `bank:`/`bezahlcode:`, `mobilepay:`, `bizum:`,
`ideal:`, …) were launched with a plain `ACTION_VIEW` whose only failure
handling was a brief "No app to handle that link." toast. When no installed
app claims the scheme — the "Open in Wallet / torrent client / Swish / Vipps /
Bezahlcode does nothing" report — that toast was the entire fallback.

`Crypto` (Open in Wallet), `Magnet` (Open in torrent client) and `Regional`
(Open in Swish / Vipps / MobilePay / Bizum / iDEAL / a Bezahlcode banking app)
now route through `openExternally(...)`, which on `ActivityNotFoundException`
shows a **"No app installed"** dialog offering **Find on Play Store**
(deep-linked to a relevant search — "bitcoin wallet", "torrent client",
"swish", …) or **Copy link**, instead of dead-ending. `startActivity` (not
`PackageManager.resolveActivity`) is the failure signal, since Android 11+
package-visibility filtering can hide an installed handler from `resolveActivity`
while the launch itself still succeeds.

Bare crypto addresses scanned without a URI scheme (a plain `bc1q…`, `0x…`, or
a raw `lnbc…` Lightning invoice) now synthesize the chain's BIP-21
`scheme:address` form (`CryptoPayload.walletUri()`) before launching, so "Open
in Wallet" actually reaches a wallet for the common bare-address case.

### Changed

**UPI now opens through an app chooser.** A plain `ACTION_VIEW` on `upi://pay?…`
goes straight to whichever app is the default UPI handler — frequently WhatsApp
Pay, which is why "Open in UPI app" was opening WhatsApp. The action now uses
`Intent.createChooser`, so the user picks the UPI app (Google Pay, PhonePe,
Paytm, …) every time. This is the Android-native equivalent of the manual app
picker the iOS build had to build by hand (iOS has no system chooser for custom
schemes).

## [1.7] — 2026-05-01

Camera UX: pinch-to-zoom + centred-frame scanning. Mirrors the iOS 1.7 work.

### Pinch-to-zoom

`ScannerScreen.kt`'s `AndroidView` wrapping `PreviewView` gains a `Modifier.pointerInput(cameraController) { detectTransformGestures(...) }`. Each gesture frame reads `cameraController.cameraInfo?.zoomState?.value`, multiplies `zoomRatio` by the gesture's `zoom` delta, clamps to `[zoomState.minZoomRatio, zoomState.maxZoomRatio]` (the camera-info-reported range, which is what multi-lens phones with telephoto/macro cameras need to honour), and applies via `cameraController.setZoomRatio(target)`. Returns early when `zoom == 1f` or before `bindToLifecycle` populates `cameraInfo`.

`panZoomLock = true` on the gesture detector keeps the user's pan / drift from leaking into the zoom factor — pinch in, pinch out, finger drift mid-gesture stays neutral.

### Region-of-interest cropping

ML Kit doesn't expose a server-side ROI knob, so we filter post-detection. `roiRectFor(previewSize)` returns a centred 78 % × 78 % rect (matching the iOS `roiFraction` exactly so cross-platform behaviour stays in lockstep). The analyzer block in `LaunchedEffect` drops any barcode whose `boundingBox.center` falls outside that rect.

The PreviewView's pixel size is captured via `Modifier.onSizeChanged { previewSize = it }`. Until layout finishes (`previewSize == IntSize.Zero`), `roiRectFor` returns null and the analyzer keeps every code — i.e. for the first ~30 ms the behaviour matches the previous full-frame mode, so no codes are dropped during the brief composition window.

### Imports added

`androidx.compose.foundation.gestures.detectTransformGestures`, `androidx.compose.ui.input.pointer.pointerInput`, `androidx.compose.ui.layout.onSizeChanged`.

## [1.6] — 2026-05-01

Share-to-Scan + PDF support.

### Share-to-Scan intent filters

- New `<intent-filter>` blocks on `MainActivity` for `ACTION_SEND` and `ACTION_SEND_MULTIPLE`, both accepting `image/*` and `application/pdf`. Scan now appears in the Android share sheet whenever the user shares one or more images / PDFs.
- New `me.nettrash.scan.ui.share.ShareIntakeDispatcher` — Hilt singleton with a `StateFlow<State>` slot (Idle / Loading / Ready(codes) / Failed). `MainActivity.routeIntent` dispatches `ACTION_SEND*` intents into it from both `onCreate` (cold start) and `onNewIntent` (warm start), parallel to how the existing `DeepLinkDispatcher` handles Universal Links.
- New `ShareResultSheet` Compose ModalBottomSheet renders all four states. `Ready` auto-drills into the single-result detail when there's exactly one code; otherwise shows a tappable list. The detail re-uses `PayloadActions` (URL / phone / Wi-Fi / contact / calendar smart actions) so a shared payload behaves exactly like a live camera capture.

### PDF support

- `ImageDecoder.decodePdf(context:Context, uri:Uri)` walks every page of a PDF via `PdfRenderer`, rasterises each at 2× density into an `ARGB_8888` bitmap (with white background — `PdfRenderer` doesn't paint one), runs the existing ML Kit decoder over each, and aggregates with `Set`-based dedup on `value`.
- `ImageDecoder.decodeBatch(context, uris)` is the share-intake entry point — handles mixed image/PDF input lists, swallows per-entry failures so one bad file doesn't poison the batch.
- `openSeekablePfd` falls back to a `cache/share-intake/` copy when the share source's `ParcelFileDescriptor` isn't seekable (typical for cloud-storage URIs).

### MainViewModel

- New `shareIntakeState: StateFlow<ShareIntakeDispatcher.State>` and `consumeShareIntake()` method. `MainScreen` collects the flow and presents `ShareResultSheet` whenever it's not Idle.

## [1.5] — 2026-05-01

Architectural pass — App Links + Auto-Backup surface + F-Droid metadata.

### Android App Links

- New `<intent-filter android:autoVerify="true">` on MainActivity claiming `https://nettrash.me/scan/*`. Play Services' intent verifier fetches `https://nettrash.me/.well-known/assetlinks.json` on first install and on every `versionName` bump; verification flips the filter from "browsable" to "verified" only when *every* SHA-256 fingerprint in the JSON validates against the installed APK's signing cert.
- New `ui/deeplink/DeepLink.kt` decodes `https://nettrash.me/scan/<base64url-payload>` URLs using `android.util.Base64` with `URL_SAFE | NO_PADDING | NO_WRAP`. Mirrors iOS's `DeepLink.swift` exactly — same URL shape, same encoding rules, payloads round-trip cleanly between platforms.
- `DeepLinkDispatcher` (Hilt singleton) holds the pending payload as a `StateFlow<String?>`. `MainActivity` feeds it from both `onCreate` (cold start) and `onNewIntent` (warm start, since the activity is `singleTask`-shaped via the standard launchMode).
- `MainViewModel` exposes the pending payload to `MainScreen`, which presents a new `DeepLinkResultSheet` (Compose `ModalBottomSheet`) re-using `PayloadActions` so the displayed fields and smart actions match a freshly-scanned code.
- `ScannerViewModel.saveDeepLinkScan(code, notes)` is the dedicated persistence method — bypasses the camera path's dedupe / continuous-scan / banner machinery so a deep-link save can't compete with a live capture for `state.lastScan`.

### Backup status surface

- New "Sync" section in the Settings screen. Reads `ApplicationInfo.flags & FLAG_ALLOW_BACKUP` to surface whether the manifest declares Auto Backup; the OS-level toggle isn't queryable without privileged permissions, so the row offers an "Open" button that deep-links into `Settings.ACTION_PRIVACY_SETTINGS` (or generic Settings on older devices).

### F-Droid

- New `metadata/me.nettrash.scan.yml` — F-Droid build recipe a maintainer pastes into fdroiddata. `RepoType: git`, source from GitHub, `subdir: Scan`, `AutoUpdateMode: Version`, `UpdateCheckMode: Tags`. Cleans `keystore.properties` from the build env so F-Droid signs with their own key.
- `fastlane/metadata/android/en-US/{title,short_description,full_description}.txt` plus `changelogs/<versionCode>.txt`. F-Droid scrapes these on every build; they're shaped exactly like Play's `play/listings/` so the same source can drive both stores.

### Server-side

- `nettrash.me/frontend/assets/.well-known/assetlinks.json` lists two SHA-256 fingerprints — the upload-key fingerprint (extracted from `scan-upload.jks`, used by side-load APKs from `nettrash.me/play/scan/scan-latest.apk`) and a placeholder for the Play App Signing fingerprint (rotate from Play Console → *Setup* → *App integrity* before deploy).
- `nginx.conf` block ensures `application/json` content type with `Cache-Control: no-store`. Apple and Google still cache for hours; an honest origin keeps the deploy cycle friendlier.

## [1.4] — 2026-05-01

Payload-recognition pass — four new flavours, mirrored exactly with iOS.

### Wi-Fi: WPA3 + Passpoint

- `CodeComposer.WifiSecurity` gains `WPA3` (`SAE`) and `PASSPOINT` (`HS20`) cases. Display labels updated to "WPA / WPA2", "WPA3 (SAE)", "Passpoint (HS20)".
- `PayloadActions` Wi-Fi block routes the raw `security` field through a new `friendlyWifiSecurity()` helper — same logic and labels as iOS. Unknown tokens fall through verbatim.
- Passpoint payloads surface a "must be installed manually" caveat — Android's public API doesn't expose programmatic Passpoint provisioning either.
- The composer now skips emitting a `P:` field for both `OPEN` and `PASSPOINT` security types (the latter doesn't carry a password in the payload).

### Crypto: USDC / USDT / DAI

- `CryptoPayload.Token` data class added (`symbol`, `contract`, `chain`). `labelledFields()` leads with "USDC on Ethereum" + contract address when a token is recognised.
- `CryptoPayload.Chain.TRON` added. Schemes `tron:` and `tronlink:` recognised. Strict 34-char base58 regex for bare Tron addresses (`T…`), checked before Bitcoin's regex to avoid mis-classification.
- `CryptoURIParser.parse` handles ERC-20 (`ethereum:…/transfer?address=…&uint256=…`), Solana Pay SPL (`solana:RECIPIENT?spl-token=MINT&amount=…`), and TRC-20 (both `tron:CONTRACT?address=…` and `tron:RECIPIENT?contract=…` shapes).
- `knownTokens` registry covers USDC, USDT, DAI on Ethereum, USDT + USDC on Tron, USDC + USDT on Solana. Lowercased keys so checksum / base58 case differences don't matter. Unknown contracts surface as generic `ERC-20` / `TRC-20` / `SPL` tags with the contract preserved.
- `parseBare` enriches `0x…` and `T…` matches with the registry lookup.

### Digital identity: DigiD + EUDI + OpenID4VC

- `RichURLPayload.Kind.DIGITAL_IDENTITY` added. Detection in `RichURLParser.digitalIdentityPayload(...)` triggers on DigiD hosts, EUDI Wallet hosts, and OpenID4VC path-level markers — same rules as iOS, conservative by design.
- `PayloadActions` shows an orange-tinted security warning ("Identity flow — only continue if you started this login yourself") above the action button when the payload is `DIGITAL_IDENTITY`.
- New "Continue in browser" action label. History row icon overridden to `Person` for DIGITAL_IDENTITY scans (vs. the generic `OpenInBrowser` for other RichUrl kinds).

### Loyalty cards

- `PayloadActions` gains an optional `onSaveAsLoyaltyCard: ((String) -> Unit)?` parameter. When non-null, product-code payloads render a "Save as loyalty card" outlined button that opens an `AlertDialog` with a merchant TextField.
- `ScannerViewModel.saveAsLoyaltyCard(merchant)` writes a `ScanRecord` with `symbology = "Loyalty"`, `notes = "Loyalty: <merchant>"`, and `isFavorite = true`. Pinned to the top of History via the favourite-first sort, found via the merchant tag in the search field.
- The History detail dialog passes `null` for the loyalty callback (already-saved scans don't re-offer the action) — the action is only on the live scanner result sheet.
- Google Wallet's loyalty-pass API is *deliberately* not used: it needs a server-side JWT signed with a Wallet service-account key, which is not viable for an offline app.

## [1.3] — 2026-05-01

Generator + scanner UX pass. `versionName` default in `Scan/build.gradle.kts` bumped from `1.2` to `1.3`. `versionCode` continues to auto-increment from `version.properties`.

### Generator

- **Foreground / background colour pickers.** New `ui/components/ColorPicker.kt` ships an HSV picker (saturation/value square + hue strip + hex field + preset swatches) since Material3 doesn't ship one. Two `ColorPickerRow`s on `GeneratorScreen.kt` thread the colours into the generator pipeline and the `bitmap` recomposition triggers on every change.
- **QR error-correction picker** exposed alongside the colour controls. Level forced to `HIGH` whenever a logo is set — callers don't have to know.
- **Logo embedding.** `Photo Picker` → `Bitmap` → centred composite at `~22 %` of the QR canvas, with a white rounded-rect "punch" behind the logo. Punch is forced white regardless of the user's chosen background, so the finder pattern keeps maximum contrast against whatever the QR is being scanned over.
- **SVG and PDF exports.** New `data/CodeSvg.kt`. SVG is run-length-encoded per row (~5× smaller than per-module). PDF uses `android.graphics.pdf.PdfDocument` for native vector output. Both go out via `FileProvider` (`<package>.fileprovider`, `cache/shared/` whitelisted in `res/xml/file_provider_paths.xml`) and an `ACTION_SEND` chooser with the right MIME type.
- **Contrast warning** at the bottom of the Style block when WCAG relative-luminance contrast drops below 3:1. Computation lives in `CodeGenerator.contrastRatio()` — same algorithm as iOS so the warning fires on the same colour pairs on both platforms.
- `CodeGenerator.kt` reworked: `bitmap()` now takes `foregroundArgb`, `backgroundArgb`, `errorCorrection`, and `logo`. New `matrix()` helper returns the raw `BitMatrix` so `CodeSvg` can vectorise without re-encoding.

### Scanner

- **Multi-code disambiguation.** `ScannerScreen.kt`'s `MlKitAnalyzer` callback now collects every recognised barcode (deduped on `rawValue`, order preserved). `ScannerViewModel.onBatch(codes)` decides what to do with multiplicity: 0 codes clears any pending chooser; 1 code routes through the existing `onScan` path; ≥ 2 codes stash into `state.multiCodeChoices` and the UI renders numbered chips at each code's bounding rect via a `BoxWithConstraints` + `Modifier.offset` overlay. Tapping a chip calls `viewModel.pickFromChoices(code)`, which bypasses the dedupe window and routes through `onScan` — so the chosen scan goes through haptic / sound / continuous-scan / save exactly the same way as a single-code framing would have.
- The reticle now tracks the largest detected rect rather than the first, so the user has a clear primary anchor while still seeing all alternatives in the chooser.

### What's-New copy

`WhatsNew.kt` (and its iOS counterpart `WhatsNew.swift`) refreshed for 1.3 with the four new bullets plus a "carried over from 1.2" pointer for users who skipped that release.

## [1.2] — 2026-05-01

Polish + History pass on top of 1.1's payload coverage. `versionName` default in `Scan/build.gradle.kts` bumped from `1.1` to `1.2`. `versionCode` continues to auto-increment from `version.properties` per build.

### Settings screen (new)

- New bottom-nav destination `BottomTab.Settings` driven by `SettingsScreen` (Compose) → `SettingsViewModel` → `SettingsRepository` (DataStore-backed).
- Three toggles, mirrored exactly with the iOS app's `ScanSettingsKey` slots:
  - **Haptic on scan** (default ON) — wired through `Haptics.success(context)`, which uses `VibratorManager` on API 31+ and falls back to the deprecated `Vibrator` API on older devices.
  - **Sound on scan** (default OFF) — `ScanSound.playScanned()` uses a one-shot `ToneGenerator` on `STREAM_NOTIFICATION` so the user's ringer / DnD policy still applies.
  - **Continuous scanning** (default OFF) — described below.
- "Test feedback" button fires both feedback channels at once.
- About block surfaces `BuildConfig.VERSION_NAME` / `VERSION_CODE` plus the GitHub + privacy URLs.

### Continuous-scanning mode (new)

`ScannerViewModel` reads `settings.continuousScan` and, when on, auto-inserts the recognised code into Room and surfaces the value via `state.lastContinuous` instead of routing it through `state.lastScan` (which drives the result sheet). `ScannerScreen` renders a green banner across the top of the preview with the most recent value; tapping it calls `viewModel.openLastContinuous()` to manually pop the sheet for that scan. Image-import path is unchanged — it always goes through the sheet.

### History favourites + CSV export (new)

- **Room schema v1 → v2.** `is_favorite` column added to `scan_records` via `MIGRATION_1_2` (`ALTER TABLE … ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0`). `AppModule.provideDatabase` registers the migration; we never use `fallbackToDestructiveMigration` — a Play upgrade silently nuking a user's saved scans is strictly worse than crashing visibly.
- **Star a scan to pin it.** New trailing icon button in `HistoryRow`. `ScanRecordDao.observeAll()` now sorts on `(is_favorite DESC, timestamp DESC)`, mirroring the iOS Core Data sort exactly. New targeted `setFavourite(id, favourite)` query so the toggle path doesn't have to round-trip the entire row.
- **Filter by favourites.** Toolbar `FilterChip` toggles `favouritesOnly` state.
- **Export to CSV.** `HistoryCsv.kt` writes UTF-8 / CRLF / RFC 4180 output to `cache/shared/Scan-history.csv`, then mints a `content://` URI via `FileProvider` (`<package>.fileprovider`, paths declared in `res/xml/file_provider_paths.xml`). Same column schema as the iOS export so power users get a portable artefact across platforms. Share intent uses `text/csv` MIME type and the `FLAG_GRANT_READ_URI_PERMISSION` per-Intent grant.

### What's-New sheet (new)

`WhatsNew` object holds the bundled release-note items; `WhatsNewSheet` is a `ModalBottomSheet` rendering them with icons + descriptions. `MainScreen.kt` carries the gate: a `MainViewModel` collects `SettingsRepository.state`, and on first composition (after `lastSeenVersion` resolves) we compare it to `BuildConfig.VERSION_NAME`. When the running build matches `WhatsNew.VERSION` *and* the user hasn't acknowledged it yet, the sheet shows; mismatches silently catch the stored value up so the sheet shows for the version it was actually written for, not whichever future build the user happens to install first.

### FileProvider plumbing

- New `<provider>` declaration in `AndroidManifest.xml` exposing `${applicationId}.fileprovider` as a non-exported authority for the History CSV export. Per-Intent URI grants only — we don't expose any global URI permission at the manifest level.
- New `res/xml/file_provider_paths.xml` whitelisting just `cache/shared/`. The barcode-model cache, OkHttp response cache, and Compose font cache stay opaque to consumers.

## [1.1] — 2026-04-28

Parity release with iOS Scan 1.1 — same payload-recognition surface on both platforms.

### Marketing version

- Default `versionName` in `Scan/build.gradle.kts` bumped from `1.0` to `1.1`. Local builds without `-PversionName=…` now produce `1.1`; tag-driven CI builds continue to override (e.g. `git tag v1.1.0` → `versionName = "1.1.0"`). `versionCode` continues to auto-increment from `version.properties` per build.

### New payload types — Pass 1

Line-for-line Kotlin port of the iOS additions:

- **Magnet URIs** — new `MagnetPayload.kt` + `MagnetURIParser`. Surfaces info-hash, display name, exact length, tracker list. "Open in torrent client" smart action.
- **Rich URLs** — new `RichURLPayload.kt` + `RichURLParser`. Recognises:
  - WhatsApp click-to-chat (`wa.me/…`, `api.whatsapp.com/send?…`)
  - Telegram (`t.me/…`)
  - Apple Wallet `.pkpass`
  - App Store / Google Play store URLs (extract App ID / package name)
  - YouTube watch / `youtu.be` / Shorts (extract video ID)
  - Spotify, Apple Music
  Each kind gets its own per-action label in `PayloadActions.kt`.
- **Maps URLs (Google + Apple) re-classify to `.Geo`** when coordinates can be pulled out, so the user gets the same "Open in Maps" smart action as a `geo:` payload.
- **vCard 4.0** transparently supported — the existing parser doesn't gate on `VERSION:`.
- **More crypto chains.** `CryptoPayload.Chain` gained `RIPPLE`, `STELLAR`, `COSMOS`, `LNURL`, `LIGHTNING_ADDRESS`. Schemes recognised: `xrp` / `xrpl` / `ripple` / `stellar` / `web+stellar` / `cosmos`.
- **Bare crypto address detection** in `CryptoURIParser.parseBare()`. Strings without a scheme but matching well-known address formats classify as `.Crypto`: legacy + bech32 Bitcoin, Ethereum `0x…`, XRP `r…`, Stellar `G…`, Cosmos `cosmos1…`, bare bolt11 invoices (`lnbc…` / `lntb…`), LNURL bech32 (`LNURL1…`).

### New payload types — Pass 2

- **GS1 Application Identifier** — new `GS1Payload.kt` + `GS1Parser`. Three forms: parens (`(01)…(17)…`), Digital Link (`https://…/01/<gtin>/10/<batch>`), and FNC1-separated. Registry of ~40 common AIs with friendly names; date AIs render as `YYYY-MM-DD`. Smart action: GTIN web lookup.
- **IATA Bar Coded Boarding Pass** — new `BoardingPassPayload.kt` + `BoardingPassParser`. Mandatory 60-char leg layout per RP 1740c.
- **AAMVA driver's licence (PDF417)** — new `DrivingLicensePayload.kt` + `DrivingLicenseParser`. Header IIN extraction + jurisdiction-name lookup (every US state + Canadian province), element-ID walker for the standard identity / dates / address fields. Auto-detects MM/DD/YYYY vs YYYY/MM/DD by checking whether the expiry's first four chars look like a year.

### Sealed-class hierarchy

`ScanPayload` gained five new cases — `Magnet`, `RichUrl`, `GS1`, `BoardingPass`, `DrivingLicense` — with exhaustive `kindLabel` and dispatch ordering tuned so BCBP + AAMVA run before the bank-payment chain (their prefixes are very specific and would otherwise be at risk of being mis-classified by later stages).

### UI

- Per-payload smart actions in `PayloadActions.kt` for all five new cases.
- History-row icons in `HistoryScreen.kt` for the new cases.

### Tests

- New `Pass1Pass2PortTest.kt` with **27 tests** mirroring the iOS Pass-1 + Pass-2 additions one-for-one. Run under Robolectric so the `android.net.Uri` / `android.util.Base64` paths exercised by the Maps / Spotify / GS1 Digital Link / Magnet parsers actually use real Android implementations rather than stub-returns.
- Total Android test count: 49 → **76**.

### Behavioural divergence from iOS (deliberate)

- Apple Maps URL test asserts `query == "Apple Park"` (with a space) where iOS asserts `"Apple+Park"` (literal `+`). Java's `URLDecoder` follows `application/x-www-form-urlencoded` semantics; iOS `URLComponents` doesn't. Same divergence already in place for the original `geo:` parser.

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
- Bank payments: EPC SEPA / GiroCode (EU), Swiss QR-bill (SPC), Czech SPD (Spayd), Slovak Pay by Square (recognition only), EMVCo Merchant QR with nested-template drilling for Pix / PayNow / PromptPay / CoDi / UPI-via-EMVCo / DuitNow / QRIS / FPS / NAPAS / NETS, Indian UPI (`upi://pay`), Bezahlcode (`bank://` / `bezahlcode://`), Serbian NBS IPS QR (PR / PT / PK).
- Mobile-payment apps: Swish (Sweden), Vipps (Norway), MobilePay (Denmark / Finland), Bizum (Spain), iDEAL (Netherlands).
- Receipts: Serbian SUF fiscal receipt.

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
