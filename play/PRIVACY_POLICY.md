# Privacy Policy

**Effective date:** 27 April 2026
**Applies to:** Scan — the Android app published by nettrash on Google Play (`me.nettrash.scan`). This policy is versioned alongside the app's source code; the most recent commit on `main` is authoritative.

## TL;DR

Scan **does not collect, transmit, sell, or share any personal data**. It contains no analytics, no advertising SDKs, no third-party trackers, and no remote servers operated by us. Everything you scan, generate, or save stays on your device.

If that already answers your question, you don't need to read the rest.

## What we collect

**Nothing.** Scan does not transmit any data about you to us or to any third party. There is no account to create, no email to register, and no telemetry pinging home from the app.

We have no servers in this picture. The app's network access is limited to the URLs you explicitly trigger by tapping a smart action — for example, opening a scanned `https://` URL in your browser, an `upi://pay` URI in your installed UPI app, or a SEPA verification URL on the issuing tax authority's site. In every such case, the network request goes from *your device* directly to *that destination* — Scan is not in the loop.

The barcode-scanning model is the **bundled** variant of Google ML Kit (`com.google.mlkit:barcode-scanning`), which runs entirely on-device. The model is shipped inside the APK and never contacts Google's servers — not for downloads, not for inference, not for telemetry.

## Data stored on your device

Scan keeps a single local database — your **scan history** — using Android's Room (a wrapper around SQLite):

- Each entry records the decoded value, the symbology, a timestamp, and any notes you choose to attach.
- The database lives inside the app's private internal-storage directory (`/data/data/me.nettrash.scan/databases/scan_database`). On a non-rooted device, no other app can read it.
- The history is **not** synced to the cloud. There is no Google account integration, no Drive backup, no Firebase, no remote mirror of any kind.
- Auto Backup (the system-level backup managed by Google Play Services on Android 6+) is governed by `backup_rules.xml` and is configured to back up nothing app-specific.
- Deleting a scan from the History tab removes it from the database.
- Deleting the app, or clearing the app's storage from *Settings → Apps → Scan → Storage*, removes the database entirely.

We have no access to this database under any circumstance.

Codes you generate and choose to save with the "Save to Photos" action are written through Android's MediaStore to `Pictures/Scan/` in your shared media collection. From that point they are normal images managed by the OS like any other photo.

## Permissions Scan asks for, and what each one is used for

| Permission | Used for | Scope |
| --- | --- | --- |
| **Camera** (`android.permission.CAMERA`) | Live barcode and QR-code scanning. | Frames are decoded on-device by Google's ML Kit barcode-scanning library. Nothing is recorded or transmitted. |
| **Photos / Media** (no permission required) | Importing a still image to scan via Android's Photo Picker. | The Photo Picker (`ActivityResultContracts.PickVisualMedia`, Android 13+) hands the app a temporary read-only handle to the *one* image you pick. Scan does not request the broad `READ_MEDIA_IMAGES` permission, so it cannot enumerate or read any image you didn't explicitly select. |
| **`READ_EXTERNAL_STORAGE`** (`maxSdkVersion=32`) | Same Photo-Picker import flow on Android 12 and below where the modern picker isn't available. | Auto-removed on Android 13+ via the manifest `maxSdkVersion` cap. |
| **Internet** (`android.permission.INTERNET`) | Reserved for future use; the app makes no network calls today. | When you tap a smart action that opens a URL, the request is performed by the destination app you've chosen, not by Scan. |

Several familiar permissions are *not* requested:

- `READ_CONTACTS` / `WRITE_CONTACTS` — Adding a scanned vCard or MECARD to your contacts is mediated by the system "New Contact" form launched via `ContactsContract.Intents.Insert.ACTION`. The form is owned by your Contacts app; Scan only pre-fills it.
- `READ_CALENDAR` / `WRITE_CALENDAR` — Adding a scanned iCalendar event to your calendar is mediated by the system event-editor opened via `Intent.ACTION_INSERT` on `CalendarContract.Events.CONTENT_URI`. The editor is owned by your Calendar app; Scan only pre-fills it.
- `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` — see Photo Picker above.
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — never requested. Smart actions on `geo:` payloads launch your Maps app via intent; Scan itself does not read your location.

Every privileged action is gated on a system-supplied edit-and-save sheet that you control. If you cancel that sheet, nothing is saved.

## Third-party services

**None operating off the device.** Scan ships with the on-device ML Kit barcode model, the Jetpack libraries (Compose, CameraX, Room, Hilt, etc.), the Google Accompanist permissions helper, and the ZXing core library used for *generating* codes. None of them contact the network at runtime in this app. There are no analytics tools (Firebase, Mixpanel, Sentry, etc.), no advertising SDKs (AdMob, Meta, AppLovin, etc.), no crash reporters that phone home, and no attribution providers.

Specifically, the **Google Advertising ID** is **not** requested, accessed, or used. Scan declares this on its Google Play Data Safety form.

## Tracking

Scan does not "track" you in any sense Google Play recognises in its Data Safety taxonomy: it does not link any data collected in the app with data from other apps, websites, or offline sources to build a user profile, and it does not share any data with data brokers. Scan is therefore declared with **Data not collected** and **Data not shared** on its Google Play Data Safety form.

## Children's privacy

Scan is rated for general audiences (Everyone / PEGI 3) and is suitable for all ages. We do not knowingly collect personal information from children, because we do not collect personal information from anyone. Scan is not a member of Google's Designed for Families programme — the app is a general-purpose utility, not directed at children under 13.

## International data transfers

Because Scan does not transmit personal data anywhere, there are no cross-border transfers to disclose under the GDPR, UK GDPR, CCPA, LGPD, or similar regimes.

## Your rights

Because we hold no data about you:

- There is **no record to access** under GDPR Article 15 / CCPA "right to know".
- There is **no record to delete** under GDPR Article 17 / CCPA "right to delete" (the local database is yours alone — deleting a scan, clearing the app's storage, or uninstalling the app removes it entirely).
- There is **no record to correct** under GDPR Article 16.
- There is **nothing being sold or shared** under CCPA / CPRA, so no opt-out is required.

If you'd like confirmation in writing that we hold no data about you, email the address below and we will reply.

## Changes to this policy

If a future version of Scan ever changes any of the above — adds analytics, integrates a third-party SDK, transmits data off-device, or requests new permissions — this document will be updated *in the same release* and the **Effective date** at the top will be bumped. The full history of this file is visible in the project's `git log` on GitHub: <https://github.com/nettrash/Scan.Android/commits/main/play/PRIVACY_POLICY.md>.

## Contact

For privacy questions, please email **nettrash@nettrash.me**.
