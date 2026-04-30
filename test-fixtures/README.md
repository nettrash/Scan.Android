# Test fixtures

61 sample barcode images, one per payload type Scan recognises. Mostly QR codes; the EAN-13 retail product code uses an actual 1D barcode, and the AAMVA driver's licence + IATA boarding pass use PDF417 to match what users see in real life.

The same folder is mirrored at `Scan.Android/test-fixtures/` — both copies are generated from a single Python script (`outputs/make_test_fixtures.py` in the workspace) so they never drift.

## How to use

**On a real device**

Display each image on a second screen (laptop monitor, iPad, printed sheet) and point the device camera at it. The Scan tab should classify the payload and surface the right field set + smart action.

**In the Simulator / Emulator**

The live camera doesn't work in the simulator. Use the **Photo Picker** flow instead:

- iOS — copy the images into the Simulator's Photos library (drag-drop the PNG onto the Simulator window) → open Scan → Scan tab → tap the import button.
- Android — same flow via the system Photo Picker; or push to `/sdcard/Pictures/` with `adb push`.

Either path ends up exercising the parser — the live camera and the still-image decoder share the same dispatch table, so a passing photo-picker decode is a good proxy for live behaviour.

## What each image tests

| File | Payload | Expected `kindLabel` |
|---|---|---|
| `01-url.png` | `https://nettrash.me` | URL |
| `02-mailto.png` | `mailto:hi@example.com?subject=…&body=…` | Email |
| `03-tel.png` | `tel:+15551234567` | Phone |
| `04-sms.png` | `smsto:+15551234567:Howdy` | SMS |
| `05-wifi.png` | `WIFI:T:WPA;S:HomeNet;P:supersecret;H:false;;` | Wi-Fi |
| `06-geo.png` | `geo:37.3349,-122.0090?q=Apple+Park` | Location |
| `07-vcard-3.png` | vCard 3.0 | Contact |
| `08-vcard-4.png` | vCard 4.0 | Contact |
| `09-mecard.png` | MECARD | Contact |
| `10-icalendar-event.png` | VCALENDAR + VEVENT (UTC) | Calendar |
| `11-icalendar-allday.png` | VEVENT with `VALUE=DATE` | Calendar |
| `12-otpauth.png` | `otpauth://totp/...` | OTP |
| `13-bitcoin-uri.png` | BIP-21 with amount + label + message | Crypto |
| `14-bare-bitcoin.png` | bech32 BTC address, no scheme | Crypto |
| `15-ethereum-uri.png` | EIP-681 with chain ID + value | Crypto |
| `16-bare-ethereum.png` | 0x… ETH address, no scheme | Crypto |
| `17-bare-bolt11.png` | `lnbc…` Lightning invoice, no scheme | Crypto |
| `18-lnurl.png` | `LNURL1…` bech32, no scheme | Crypto |
| `19-bitcoin-cash.png` | `bitcoincash:` URI | Crypto |
| `20-litecoin.png` | `litecoin:` URI | Crypto |
| `21-dogecoin.png` | `dogecoin:` URI | Crypto |
| `22-monero.png` | `monero:` URI | Crypto |
| `23-cardano.png` | `cardano:` URI | Crypto |
| `24-solana.png` | `solana:` URI | Crypto |
| `25-xrp.png` | `xrpl:` URI | Crypto |
| `26-stellar.png` | `web+stellar:` URI | Crypto |
| `27-cosmos.png` | `cosmos:` URI | Crypto |
| `28-magnet.png` | BitTorrent magnet link | Magnet |
| `29-whatsapp.png` | `wa.me/<phone>?text=…` | WhatsApp |
| `30-telegram.png` | `t.me/<handle>` | Telegram |
| `31-pkpass.png` | `https://…/foo.pkpass` | Apple Wallet |
| `32-app-store.png` | apps.apple.com listing | App Store |
| `33-play-store.png` | play.google.com listing | Google Play |
| `34-youtube.png` | `youtu.be/<id>` short link | YouTube |
| `35-spotify.png` | open.spotify.com track | Spotify |
| `36-apple-music.png` | music.apple.com album | Apple Music |
| `37-google-maps.png` | google.com/maps/place — *re-classified* to **Location** (geo) |
| `38-apple-maps.png` | maps.apple.com `?ll=…&q=…` — *re-classified* to **Location** |
| `39-epc-sepa.png` | EPC GiroCode (line 1 = `BCD`) | SEPA Payment |
| `40-swiss-qrbill.png` | Swiss QR-bill (line 1 = `SPC`) | QR-bill (Swiss) |
| `41-russian-st00012.png` | ST00012 unified payment | Payment |
| `42-fns-receipt.png` | FNS retail receipt | Receipt |
| `43-emvco-pix.png` | EMVCo merchant QR with embedded Pix template | Merchant QR (sub-row labelled "Pix account (26)") |
| `44-suf-receipt.png` | suf.purs.gov.rs URL | Receipt (RS) |
| `45-nbs-ips-pr.png` | NBS IPS QR — printed bill (PR) | IPS Payment (RS) |
| `46-nbs-ips-pt.png` | NBS IPS QR — POS merchant (PT) | IPS Payment (RS) |
| `47-upi.png` | `upi://pay?…` | UPI |
| `48-czech-spd.png` | Czech Spayd | SPD (CZ) |
| `49-pay-by-square.png` | Slovak BySquare token | Pay by Square (SK) |
| `50-bezahlcode.png` | `bank://singlepaymentsepa?…` | Bezahlcode |
| `51-swish.png` | `swish://payment?data=<base64-JSON>` | Swish |
| `52-vipps.png` | `vipps://?…` | Vipps |
| `53-mobilepay.png` | `mobilepay://?…` | MobilePay |
| `54-bizum.png` | `bizum://?…` | Bizum |
| `55-ideal.png` | `ideal://?…` | iDEAL |
| `56-gs1-parens.png` | `(01)…(17)…(10)…(21)…` | GS1 |
| `57-gs1-fnc1.png` | FNC1-separated (GS = `0x1D`) | GS1 |
| `58-gs1-digital-link.png` | id.gs1.org/01/.../10/... | GS1 |
| `59-ean13.png` | EAN-13 retail barcode (real 1D barcode) | Product |
| `60-aamva-driver-license.png` | AAMVA DL (PDF417) | Driver's Licence |
| `61-iata-boarding-pass.png` | IATA RP 1740c (PDF417) | Boarding Pass |

## Regenerating

```sh
cd outputs/
python3 make_test_fixtures.py
```

Writes to both `Scan/test-fixtures/` and `Scan.Android/test-fixtures/`. Edit the `QR_FIXTURES` list (or the AAMVA / BCBP constants) at the top of the script when you add new payload types or change the canonical fixture for an existing one.

## Notes about specific fixtures

- **`14-bare-bitcoin.png`** is a genuine bech32 v0 segwit address with a valid checksum. The classifier accepts the regex shape; it does not verify the checksum. (Useful side effect: malformed addresses still classify as Crypto rather than falling through to text — the parser's job is recognition, not validation.)
- **`18-lnurl.png`** is a placeholder bech32 string with the right `LNURL1` prefix and length. Resolving it would need network — we only check shape.
- **`40-swiss-qrbill.png`** has exactly the 31-line minimum mandatory section. Watch the blank lines carefully — there are 7 between `CH` (creditor country) and `199.95` (amount); 6 or 8 silently shifts every assertion downstream.
- **`43-emvco-pix.png`** has a real Pix-style nested template at tag 26 with sub-tag 00 = `BR.GOV.BCB.PIX` and sub-tag 01 = `alice@bcb.gov.br`. The expected behaviour is that the result sheet renames the parent row to "Pix account (26)" and surfaces both sub-fields with the `↳` marker.
- **`60-aamva-driver-license.png`** uses South Carolina's IIN (`636026`) so the parser resolves the friendly issuer name. Other fixtures using a different IIN would surface the raw 6-digit number instead.
- **`61-iata-boarding-pass.png`** carries only the 60-char mandatory section; multi-leg conditional sections are deliberately omitted because they vary per airline and we don't try to parse them anyway.
