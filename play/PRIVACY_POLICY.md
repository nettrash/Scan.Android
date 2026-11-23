# Privacy Policy

**Effective date:** 22 November 2026
**Applies to:** Scan — the Android app published by nettrash on Google Play (`me.nettrash.scan`). This policy is versioned alongside the app's source code; the most recent commit on `main` is authoritative.

## TL;DR

**We** (the developer) collect nothing: there are no accounts, no servers of ours, no advertising SDKs and no analytics of our own, and everything you scan, generate or save is processed and stored on your device. Scan uses **Google ML Kit** to read barcodes and text and to translate on the device; ML Kit itself sends Google diagnostic and usage statistics and app-installation identifiers, and fetches translation language models and settings from Google when you use translation — see *Google ML Kit* below. The images, codes and text you scan are never sent.

If that already answers your question, you don't need to read the rest.

## What we collect

**Nothing reaches us.** There is no account to create, no email to register, and no server operated by us. The only data that leaves your device is what Google's ML Kit library sends to Google, described in the next section.

We have no servers in this picture. The app's network access is limited to the URLs you explicitly trigger by tapping a smart action — for example, opening a scanned `https://` URL in your browser, an `upi://pay` URI in your installed UPI app, or a SEPA verification URL on the issuing tax authority's site. In every such case, the network request goes from *your device* directly to *that destination* — Scan is not in the loop.

## Google ML Kit

Scan uses these Google ML Kit libraries. All recognition and translation **runs on the device**; camera frames, images, decoded codes and the text you translate are never uploaded.

| Library | What it does in Scan |
| --- | --- |
| `barcode-scanning` (model bundled in the app) | Reads barcodes and QR codes from the camera and imported images. |
| `text-recognition` (model bundled) | Reads printed text in the Live Text scan mode. |
| `language-id` (model bundled) | Detects the language of text you ask to translate. |
| `translate` (models downloaded) | Translates text into the language you choose in Settings → Insights. |

What ML Kit sends to Google, and when:

- **Diagnostics and usage statistics** — automatically, in the background, for every ML Kit feature used (this was already the case for barcode scanning before version 1.10). Per Google, this covers the device model and OS version, the app's package name and version, per-installation identifiers, API settings, latency, input/output sizes, event types and error codes, and — for translation and language detection — the languages involved. There is no setting in Scan to turn this off.
- **Translation language models** (about 30 MB each) — downloaded from Google's servers only after you agree in Scan's download dialog, which also says whether you are on mobile data.
- **Firebase installation ID and remote configuration** — whenever a translator is set up (when you translate, or download a model), ML Kit registers an app-installation ID with Google's Firebase service and fetches its translation settings, sending your device's language and region, time zone, Android version and app version.

These transfers go to Google over HTTPS and are governed by Google's own privacy terms (see Google's ML Kit "Data disclosure" documentation). Scan's own code makes no network requests.

## Data stored on your device

Scan keeps a single local database — your **scan history** — using Android's Room (a wrapper around SQLite):

- Each entry records the decoded value, the symbology, a timestamp, and any notes you choose to attach.
- The database lives inside the app's private internal-storage directory (`/data/data/me.nettrash.scan/databases/scan_database`). On a non-rooted device, no other app can read it.
- The history is **not** synced to any server of ours; there is no account and no remote mirror.
- Android's own backup is allowed (`android:allowBackup="true"`, with no exclusions in `backup_rules.xml` / `data_extraction_rules.xml`): if backup is on for your device, your scan history and settings are included in your Google backup (end-to-end encrypted on Android 9+ when the device has a screen lock) and in device-to-device transfers. Scan never reads that backup; Settings → Backup shows the status and links to the system setting. Downloaded translation models are stored outside the backup.
- Deleting a scan from the History tab removes it from the database.
- Deleting the app, or clearing the app's storage from *Settings → Apps → Scan → Storage*, removes the database entirely.

We have no access to this database under any circumstance.

Codes you generate and choose to save with the "Save to Photos" action are written through Android's MediaStore to `Pictures/Scan/` in your shared media collection. From that point they are normal images managed by the OS like any other photo.

## Permissions Scan asks for, and what each one is used for

| Permission | Used for | Scope |
| --- | --- | --- |
| **Camera** (`android.permission.CAMERA`) | Live barcode and QR-code scanning and, in Live Text mode, reading printed text. | Frames are processed on the device by Google's ML Kit barcode and text-recognition libraries. Nothing is recorded or transmitted. |
| **NFC** (`android.permission.NFC`) | Reading a tag into the scanner and writing a generated code to a writable tag. | Only after you tap *Read* or *Write to NFC tag*; reader mode ends when you leave the screen. Nothing read or written leaves the device. |
| **Photos / Media** (no permission required) | Importing a still image to scan via Android's Photo Picker. | The Photo Picker (`ActivityResultContracts.PickVisualMedia`, Android 13+) hands the app a temporary read-only handle to the *one* image you pick. Scan does not request the broad `READ_MEDIA_IMAGES` permission, so it cannot enumerate or read any image you didn't explicitly select. |
| **`READ_EXTERNAL_STORAGE`** (`maxSdkVersion=32`) | Same Photo-Picker import flow on Android 12 and below where the modern picker isn't available. | Auto-removed on Android 13+ via the manifest `maxSdkVersion` cap. |
| **Internet** (`android.permission.INTERNET`) | Used only by Google ML Kit (see above): translation-model downloads, its Firebase installation / remote-configuration calls, and its diagnostics. | Scan's own code makes no network requests. When you tap a smart action that opens a URL, the request is performed by the destination app you've chosen, not by Scan. |
| **Network state** (`android.permission.ACCESS_NETWORK_STATE`) | Telling you in the download dialog that you are on mobile data. | Reads only whether the connection is metered. |
| **Wake lock / run at startup / foreground service** (`WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`) | Declared by Android's WorkManager library, which the home-screen widget uses to update. | Scan schedules no background work of its own. |

Several familiar permissions are *not* requested:

- `READ_CONTACTS` / `WRITE_CONTACTS` — Adding a scanned vCard or MECARD to your contacts is mediated by the system "New Contact" form launched via `ContactsContract.Intents.Insert.ACTION`. The form is owned by your Contacts app; Scan only pre-fills it.
- `READ_CALENDAR` / `WRITE_CALENDAR` — Adding a scanned iCalendar event to your calendar is mediated by the system event-editor opened via `Intent.ACTION_INSERT` on `CalendarContract.Events.CONTENT_URI`. The editor is owned by your Calendar app; Scan only pre-fills it.
- `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` — see Photo Picker above.
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — never requested. Smart actions on `geo:` payloads launch your Maps app via intent; Scan itself does not read your location.

Every privileged action is gated on a system-supplied edit-and-save sheet that you control. If you cancel that sheet, nothing is saved.

## Third-party services

**Google ML Kit** (described above) is the only library that contacts a server. Scan also ships the Jetpack libraries (Compose, CameraX, Room, Hilt, Glance, WorkManager, etc.), the Google Accompanist permissions helper, and the ZXing core library used for *generating* codes; none of them contact the network in this app. There are no advertising SDKs (AdMob, Meta, AppLovin, etc.), no analytics tools of our own, no crash reporters and no attribution providers.

The **home-screen widget** shows only the kind of each recent scan, plus a link's host or a Wi-Fi network's name — never a password, secret or full value.

Specifically, the **Google Advertising ID** is **not** requested, accessed, or used. Scan declares this on its Google Play Data Safety form.

## Tracking

Scan does not "track" you: it does not link data from the app with data from other apps, websites or offline sources to build a profile, does not use the Advertising ID, and does not share data with data brokers. Its Google Play Data Safety form declares the diagnostics and installation identifiers that Google's ML Kit collects, as described above.

## Children's privacy

Scan is rated for general audiences (Everyone / PEGI 3) and is suitable for all ages. We do not knowingly collect personal information from children, because we do not collect personal information from anyone. Scan is not a member of Google's Designed for Families programme — the app is a general-purpose utility, not directed at children under 13.

## International data transfers

Scan sends nothing to us. What Google's ML Kit sends (above) goes to Google's servers, which may be located outside your country, under Google's terms.

## Your rights

We hold no data about you (requests about what ML Kit sends go to Google):

- There is **no record to access** under GDPR Article 15 / CCPA "right to know".
- There is **no record to delete** under GDPR Article 17 / CCPA "right to delete" (the local database is yours alone — deleting a scan, clearing the app's storage, or uninstalling the app removes it entirely).
- There is **no record to correct** under GDPR Article 16.
- There is **nothing being sold or shared** under CCPA / CPRA, so no opt-out is required.

If you'd like confirmation in writing that we hold no data about you, email the address below and we will reply.

## Changes to this policy

If a future version of Scan ever changes any of the above — adds analytics, integrates a third-party SDK, transmits data off-device, or requests new permissions — this document will be updated *in the same release* and the **Effective date** at the top will be bumped. The full history of this file is visible in the project's `git log` on GitHub: <https://github.com/nettrash/Scan.Android/commits/main/play/PRIVACY_POLICY.md>.

## Contact

For privacy questions, please email **nettrash@nettrash.me**.
