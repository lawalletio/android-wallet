# Publishing to Google Play

The app ships as `io.lawallet.wallet` (an application ID can never change once published).

## Build a release
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
LAWALLET_SIGNING_PROPERTIES=/path/to/upload-key.properties \
  ./gradlew bundleRelease -PversionName=1.0.0 -PversionCode=1
# → app/build/outputs/bundle/release/app-release.aab (+ mapping.txt for deobfuscation)
```
CI does the same on a `v*` tag (`.github/workflows/release.yml`). It needs these secrets: `LAWALLET_KEYSTORE_BASE64`,
`LAWALLET_KEYSTORE_PASSWORD`, `LAWALLET_KEY_ALIAS` and `LAWALLET_KEY_PASSWORD`. Add `PLAY_SERVICE_ACCOUNT_JSON` as well
to upload to Play automatically.

## Signing and passkeys
- The upload key signs what we send to Play. Google re-signs installs with the **app signing key** (Play App Signing).
- Passkeys and App Links verify against the certificate that **installs on the phone**. That is the app signing key:
  Play Console → Test and release → App integrity → App signing → SHA-256 certificate fingerprint. Put that value
  (and the upload key's, for sideloaded builds) in the instance's `ANDROID_APP_CERT_SHA256`
  (lawallet-nwc PR #366) so `/.well-known/assetlinks.json` lists it.

## Already checked
- Target SDK 37 and min SDK 28; R8-minified; native libraries are 16 KB page-aligned (verified with `llvm-readelf`).
- The App Bundle keeps every language: the in-app language picker needs them all.
- No analytics or ads SDKs. Backups and device-to-device transfers are excluded (`data_extraction_rules.xml`).

## Store listing
`fastlane/metadata/android/{en-US,es-419}` holds the title, short and full descriptions, the 512×512 icon, the
1024×500 feature graphic, phone screenshots and changelogs.
The privacy policy URL is https://github.com/lawalletio/android-wallet/blob/main/PRIVACY.md.

## Console declarations
- **Data safety**:
  - Collected and shared with the instance: user IDs (the Nostr public key) and financial info (payment
    requests the user makes).
  - Data is encrypted in transit. Users can request deletion by removing the wallet in the app.
  - No data is collected for ads or analytics.
- **Foreground service (`specialUse`)**: "Keeps the user's Nostr Wallet Connect relay subscription open so incoming
  Lightning payments are notified in real time; there is no server push." It is user-visible: the listener can be
  turned off in Settings, and a persistent notification shows while it runs.
- **Permissions**:
  - CAMERA: QR scanning only.
  - NFC: Bolt card / LNURL-withdraw tags.
  - POST_NOTIFICATIONS: payment notifications.
  - RECEIVE_BOOT_COMPLETED: restart the listener after a reboot.
- **Financial features**: LaWallet is a non-custodial client. It controls no funds and connects over NWC to the
  user's own wallet, or to a third-party courtesy wallet (LNCurl) the community provides. The operator must confirm the
  cryptocurrency-wallet declaration for their countries.
- **Content rating**: no user-generated content and no gambling. Payments go between users.
- **Ads**: none.
