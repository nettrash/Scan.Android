# Play Console — Data Safety form answers

This file mirrors every Data Safety question Play Console asks for the
**Scan** app, with the answers ready to type in. Sources: the Play Console
form structure as of 2026 and the actual data-handling described in
`PRIVACY_POLICY.md`.

## 1. Data collection and security

> Does your app collect or share any of the required user data types?

**No.**

Scan does not collect or transmit any personal data. Camera frames are
processed by an on-device ML Kit model and discarded; saved scans live in
the app's private Room database; generated images are written to the user's
own MediaStore folder. Nothing leaves the device.

> Is all of the user data collected by your app encrypted in transit?

**Not applicable** (no data is collected or transmitted).

> Do you provide a way for users to request that their data be deleted?

**Not applicable** (no data is collected). Users can clear local history
themselves by deleting scans from the History tab, or by uninstalling the
app or clearing app storage from Android Settings.

## 2. Data types

For every data type Play lists, the answer is **"No, my app does not
collect or share this data"**:

- Personal info (name, email, address, user IDs, phone, race/ethnicity,
  political/religious beliefs, sexual orientation, other).
- Financial info (payment info, purchase history, credit score, other).
- Health and fitness.
- Messages (emails, SMS or MMS, other in-app messages).
- Photos and videos.
- Audio files.
- Files and docs.
- Calendar events.
- Contacts.
- App activity (page views, in-app search history, installed apps,
  other actions, other history).
- Web browsing.
- App info and performance (crash logs, diagnostics, performance data).
- Device or other IDs.
- Location (approximate or precise).

The "Photos and videos" category is worth flagging in your own notes:
the app *does* read an image when the user explicitly picks one with the
Photo Picker, but Play's definition of "collect" is "transmit off the
device or persist beyond the current session in a way the user did not
initiate". The picked image is read in-memory and discarded after decoding,
so it is not collected.

## 3. Security practices

> Is data encrypted in transit?

**Not applicable** (no data in transit).

> Does the app follow the Families Policy?

**Not applicable** — the app is not directed at children.

> Has the app been independently security reviewed?

No (you can leave this unchecked unless an actual third-party review has
been done).

## 4. Ads

> Does your app contain ads?

**No.**

## 5. Government apps / Financial apps / Health apps / News apps

All **No**.
