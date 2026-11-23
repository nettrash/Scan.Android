# Play Console — Data Safety form answers

This file mirrors every Data Safety question Play Console asks for the
**Scan** app, with the answers ready to type in. Sources: the Play Console
form structure as of 2026 and the actual data-handling described in
`PRIVACY_POLICY.md`.

> **Re-answer the form in Play Console before the 1.10 rollout.** Up to 1.9
> the form said "No data collected". That was already generous for the
> bundled ML Kit barcode scanner (which sends Google diagnostics), and 1.10
> adds ML Kit text recognition, language identification and translation,
> whose Firebase installation ID, remote-configuration and telemetry calls
> are verified (a recording proxy saw `firebaseinstallations.googleapis.com`
> on every `Translation.getClient`). The answers below describe 1.10. Items
> marked *(owner decision)* need a judgement call, not just transcription.

## What leaves the device in 1.10 (the facts behind the answers)

Scan's own code makes no network requests. Everything below is done by the
Google ML Kit SDK (`barcode-scanning`, `text-recognition`, `language-id`,
`translate`):

| Data | When | Recipient |
| --- | --- | --- |
| ML Kit diagnostics and usage statistics (device model, OS version, app package and version, per-installation identifiers, API settings, latency, input/output sizes, event types, error codes, language pair for translation / detected language) | Automatically, batched in the background, whenever an ML Kit feature runs — including plain barcode scanning | Google (`firebaselogging.googleapis.com`) |
| Firebase installation ID + remote-configuration request (device language and region, time zone, Android and app version, app ID) | Whenever a translator is created: each Translate tap and each model download | Google (`firebaseinstallations.googleapis.com`, `firebaseremoteconfig.googleapis.com`) |
| Language-model download (no user data in the request beyond the language code) | Only after the user agrees in Scan's download dialog | Google (`dl.google.com`) |

Never sent: camera frames, imported images, decoded codes, recognised or
translated text, scan history.

## 1. Data collection and security

> Does your app collect or share any of the required user data types?

**Yes** — collected by the Google ML Kit SDK (see the table above).

> Is all of the user data collected by your app encrypted in transit?

**Yes** (ML Kit uses HTTPS).

> Do you provide a way for users to request that their data be deleted?

**No.** Scan has no account and no server; the data above is held by Google
under its own terms. Users clear local history themselves by deleting scans,
clearing app storage or uninstalling.

## 2. Data types

Declare as **collected** (not shared — Google receives it as the SDK /
service provider; confirm this reading of Play's "sharing" definition
*(owner decision)*):

- **App info and performance → Diagnostics.** Purpose: Analytics (ML Kit's
  own performance and usage statistics). Collection is **required** (ML Kit
  offers no switch), **not** processed ephemerally.
- **Device or other IDs.** ML Kit's per-installation identifier and the
  Firebase installation ID. Purposes: Analytics; App functionality (remote
  configuration for translation). Required.
- **App activity → Other actions** — ML Kit logs which of its features were
  used (event types). Declare it or argue it is covered by Diagnostics
  *(owner decision)*.

Everything else stays **"No, my app does not collect or share this data"**:
personal info, financial info, health and fitness, messages, photos and
videos, audio, files and docs, calendar, contacts, web browsing, location,
and app activity other than the item above.

The "Photos and videos" category is worth flagging in your own notes: the
app *does* read an image when the user explicitly picks one with the Photo
Picker, but it is decoded in memory on the device and never transmitted, so
it is not collected.

## 3. Security practices

> Is data encrypted in transit?

**Yes.**

> Does the app follow the Families Policy?

**Not applicable** — the app is not directed at children.

> Has the app been independently security reviewed?

No (you can leave this unchecked unless an actual third-party review has
been done).

## 4. Ads

> Does your app contain ads?

**No.** The Advertising ID is not used (no `AD_ID` permission in the merged
manifest).

## 5. Government apps / Financial apps / Health apps / News apps

All **No**.
