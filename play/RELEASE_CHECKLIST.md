# Release checklist — Scan.Android

Use this once for the **first** Play Console submission, then again — abridged — for every subsequent update.

## One-time setup

### Google Play Console account

- [ ] Sign up at https://play.google.com/console with the publishing Google account.
- [ ] Pay the **US$25** one-time registration fee.
- [ ] Verify identity (government ID + payment method).
- [ ] Decide between *Personal* or *Organisation* account (organisation needs a D-U-N-S number; personal needs the 12-tester / 14-day closed-track gate before production).

### Upload keystore

- [ ] Generate locally — keep this file out of git:
      ```sh
      keytool -genkeypair -v -keystore scan-upload.jks -alias scan-upload \
        -keyalg RSA -keysize 2048 -validity 10000
      ```
- [ ] Copy `keystore.properties.example` → `keystore.properties` and fill in `storePassword` / `keyPassword` / `storeFile=scan-upload.jks`.
- [ ] Verify a release build is signed with the upload key:
      ```sh
      ./gradlew :Scan:assembleRelease
      jarsigner -verify -verbose -certs Scan/build/outputs/apk/release/Scan-release.apk
      ```
- [ ] Back up the keystore file and the passwords somewhere safe (1Password, hardware token, etc.). Lose them and you lose the ability to push updates.

### Service account for the GitHub Actions release job

- [ ] In Google Cloud Console, create a service account in any project (the API is free).
- [ ] In Play Console → *Setup* → *API access*, link the project and grant the service account *Release manager* permission for the Scan app.
- [ ] Download the JSON key for the service account.
- [ ] Add these GitHub repo secrets (Settings → Secrets and variables → Actions):
      - `SCAN_KEYSTORE_BASE64` — base64 of `scan-upload.jks` (`base64 -i scan-upload.jks | pbcopy` on macOS).
      - `SCAN_KEYSTORE_PASSWORD`
      - `SCAN_KEY_ALIAS` — e.g. `scan-upload`.
      - `SCAN_KEY_PASSWORD`
      - `PLAY_SERVICE_ACCOUNT_JSON` — paste the entire JSON file contents.

### App entry on Play Console

- [ ] *Create app* → name `Scan`, language `English (United Kingdom)`, type `App`, free, accept policies / US export.
- [ ] *Set up your app*:
   - [ ] **App access** — All functionality is available without restrictions.
   - [ ] **Ads** — No, my app does not contain ads.
   - [ ] **Content rating** — fill the IARC questionnaire (it'll come back as Everyone / PEGI 3).
   - [ ] **Target audience** — 13+, not directed at children.
   - [ ] **News app** — No.
   - [ ] **COVID-19 contact tracing / status** — No.
   - [ ] **Government app** — No.
   - [ ] **Financial features** — No (the app shows financial *information* parsed from QRs, but performs no transactions).
   - [ ] **Health features** — No.
- [ ] *Privacy policy* URL — paste `https://nettrash.me/play/scan/privacy.html` (hosted from `nettrash-me/frontend/assets/play/scan/privacy.html`).
- [ ] *Data safety* — fill in using the answers in `play/DATA_SAFETY.md` (everything is "no data collected").

### Store listing

- [ ] **App name** — `Scan`.
- [ ] **Short description** — paste from `play/listing/short_description.txt`.
- [ ] **Full description** — paste from `play/listing/full_description.txt`.
- [ ] **App icon** — upload `play/graphics/store_icon_512.png`.
- [ ] **Feature graphic** — upload `play/graphics/feature_graphic_1024x500.png`.
- [ ] **Phone screenshots** — capture 2–8 from a real device or emulator. Suggested: scanner with a code in frame, result sheet for a Wi-Fi QR, result sheet for a SEPA payment, generator with a QR previewed, history list, history detail. PNG/JPEG, min 320 px on the long edge.
- [ ] **Tablet screenshots** — optional but recommended; 7-inch and 10-inch buckets.
- [ ] **App category** — Tools.
- [ ] **Tags** — pick 3-5 (Productivity / Tools / Utilities).
- [ ] **Contact details** — email, website (`nettrash.me`).

## First release

- [ ] Confirm `versionCode = 1` and `versionName = "1.0"` in `Scan/build.gradle.kts`.
- [ ] Build the AAB locally to sanity-check signing and R8:
      ```sh
      ./gradlew :Scan:bundleRelease
      ```
- [ ] On Play Console → *Internal testing* → *Create new release* → upload `Scan/build/outputs/bundle/release/Scan-release.aab`.
- [ ] *What's new* — Play Console reads it automatically from `Scan/src/main/play/release-notes/<locale>/default.txt` when uploaded via the GitHub Actions workflow. For manual uploads, paste from the `default.txt` matching your store-listing language. Update each locale **and** the `## [Unreleased]` section of `CHANGELOG.md` before tagging.
- [ ] Add yourself + a few testers (email addresses) to the internal-testing list. Save the opt-in URL it gives you.
- [ ] Install via the opt-in URL, smoke-test on a real device:
   - [ ] Camera permission prompt appears, scan a QR.
   - [ ] Photo Picker import works.
   - [ ] Generator produces a code and Save-to-Photos lands in `Pictures/Scan/`.
   - [ ] History persists across app restarts.
- [ ] *Closed testing* → 12 testers for 14 days (personal accounts only).
- [ ] Promote to *Production*. First-app review takes 1–7 days.

## Subsequent releases

- [ ] Make your code changes. **Every successful local build bumps `versionCode` by 1** in `version.properties`. Commit that file alongside the release so the bump propagates and Play sees a strictly-increasing number.
- [ ] Build the AAB locally to confirm everything compiles and to take the version-code bump:
      ```sh
      ./gradlew :Scan:bundleRelease -PversionName=1.0.5
      ```
      Watch for the `:Scan: bumped versionCode N -> N+1` line in the Gradle output. After it finishes, `git diff version.properties` should show the new value.
- [ ] Commit + tag + push:
      ```sh
      git add version.properties
      git commit -m "release v1.0.5"
      git tag v1.0.5
      git push origin main v1.0.5
      ```
      The release workflow strips the leading `v` and passes `-PversionName=1.0.5 -PnoBump` to Gradle. `-PnoBump` keeps CI from mutating `version.properties` on the runner — the tracked file you just pushed is the source of truth.
- [ ] On Play Console: review the new release, write a short *What's new*, promote internal → production.
- [ ] Keep an eye on *Quality* → *Android vitals* for crash spikes in the 24 hours after rollout.

> Note: Play Console requires `versionCode` to be **strictly greater** than any previously uploaded build. The local bump handles this automatically as long as you commit the bumped `version.properties` before tagging. If you ever forget and Play rejects the upload, just run `./gradlew :Scan:bundleRelease` once more locally, commit the new `version.properties`, and re-tag.
