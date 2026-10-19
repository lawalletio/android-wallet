# LaWallet for Android

A native Android version of the [LaWallet](https://github.com/lawalletio/lawallet-nwc) `/wallet`, built with Kotlin and Jetpack Compose. It has the same screens, copy (English and Spanish) and features as the web wallet. It talks to any LaWallet instance (default: `https://beta.lawallet.io`), and you can change the instance under **Settings → Instance**.

## Features

- **Accounts:** create one with a fresh Nostr key (backup with an "I've saved it" confirmation), with a passkey, with an existing nsec or hex key, or through an Amber/NIP-55 signer.
- **Balance, send and receive:** everything goes straight to your wallet over NIP-47 (Nostr Wallet Connect). The LaWallet server never sits in the payment path.
  - Send to Lightning addresses, LNURL-pay (with LUD-12 comments) or bolt11 invoices. Payments are confirmed with swipe-to-pay.
  - Receive at your Lightning address, or create an invoice for an amount.
  - New accounts get a courtesy LNCurl NWC wallet automatically.
- **Realtime:** NWC notifications (kinds 23196/23197) trigger an in-app notice, a sound (chime or bananero) and a system notification. A foreground service keeps listening while the app is closed. Payments missed while offline are caught up with `list_transactions`.
- **Scan QR** (CameraX + ML Kit) and **deep links** (`lightning:`, `lnurlw:`, `lnurlp:`, activation URLs) route to the right place:
  - invoice, address or LNURL-pay → the send flow
  - LUD-03 withdraw → the claim screen with the treasure-chest animation
  - card activation QR → card activation
- **NFC:**
  - On the invoice screen, tapping a Bolt card charges the displayed invoice.
  - Anywhere else, tapping an LNURL-withdraw tag opens the withdraw flow.
- **Cards:** list your cards, enable or disable tap payments, link a card to your wallet, and activate a card from a QR.
- **Lightning address:** check availability, claim it, and handle paid registration (402 → invoice → LUD-21 verify → claim).
- **Remote wallets:**
  - Connect an NWC string or create an LNCurl wallet. Set it as primary, pause, rename or delete it.
  - The detail screen shows the balance, connection string, receive protocols, forwarding plan and webhook notifications.
- **Security:**
  - See your npub and role.
  - Reveal your nsec backup behind the device lock.
  - Manage passkeys (add, rename, delete).
  - Revoke connected OAuth apps.
  - Remove the wallet from the device.
- **Preferences:** display currencies (Yadio rates, reorderable), language (automatic / en / es), payment sound, background notifications.

The `.lottie` animation, the sounds and the logos come from the web app. The strings are generated from the web i18n (`scripts/i18n2xml.ts`).

## Build

You need Android Studio's JBR (JDK 21) and the Android SDK with platform 37.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest          # JVM tests: crypto vectors, NWC client vs a fake wallet relay, API client, parsers
./gradlew connectedDebugAndroidTest  # Compose UI tests on a device/emulator
./gradlew lintDebug assembleDebug    # debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease            # R8-minified release APK
```

Debug builds allow cleartext HTTP, so you can point the instance at a local dev server: `http://10.0.2.2:<port>` from the emulator. Release builds require HTTPS.

## Signing and passkeys

To use passkeys and App Links, the instance must serve `/.well-known/assetlinks.json` listing this app's signing certificate. Server support for that is in lawallet-nwc PR #366 (`ANDROID_APP_PACKAGE` / `ANDROID_APP_CERT_SHA256`).

1. Sign with a **private** keystore that is never committed. Create `keystore.properties` at the repo root:

   ```properties
   storeFile=/absolute/path/lawallet-wallet.jks
   storePassword=…
   keyAlias=…
   keyPassword=…
   ```

   Both debug and release builds use it when it is present.
2. Give the instance the keystore's SHA-256 fingerprint.

Never register a publicly known debug key on a real instance: any app signed with it could obtain passkey PRF outputs, which means taking over the account.

## Layout

```
core/nostr   keys, bech32, events, NIP-04/44/98, signers
core/nwc     relay (reconnecting WebSocket), NIP-47 client
core/ln      bolt11, destination/LNURL/activation/NDEF parsing, Bolt card charge
core/api     LaWallet REST client (NIP-98 → JWT session)
core/fx      currencies, keypad logic, Yadio rates
core/auth    passkeys (Credential Manager + PRF → HKDF), Amber
app/         session, wallet repository, flow store
service/     payment listener (foreground), notifications, sounds
ui/          theme, components, one screen file per /wallet route
```

MIT licensed.
