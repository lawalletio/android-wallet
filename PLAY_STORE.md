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
- Current values (both public), ready to paste:
  ```
  ANDROID_APP_CERT_SHA256=BC:7C:69:49:A7:E6:36:FA:15:42:20:E8:E1:DB:19:F4:58:D7:1E:56:AF:70:B2:BF:3B:D5:2D:18:9C:EA:C1:F7,9B:D5:EB:E8:D4:AD:36:91:EB:94:DF:48:8C:BB:EE:88:5D:E5:F3:1D:80:E2:2A:95:9E:55:5F:35:42:AD:81:13
  ```
  The first is the Play app signing key, the second the upload key.

## Already checked
- Target SDK 37 and min SDK 28; R8-minified; native libraries are 16 KB page-aligned (verified with `llvm-readelf`).
- The App Bundle keeps every language: the in-app language picker needs them all.
- No analytics or ads SDKs. An opt-in crash reporter (Sentry) is off until the user turns it on. Backups and device-to-device transfers are excluded (`data_extraction_rules.xml`).

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
  - **App info and performance → Crash logs; Diagnostics**: collected, not shared (Sentry is a service provider),
    **optional** (off by default; Settings → Diagnostics, or the prompt after an unexpected exit). Purposes: App
    functionality, Analytics. Encrypted in transit. Contents: stack traces, exit reasons, memory use, app/OS version,
    device model. Never keys, NWC strings, invoices, amounts or addresses (see `core/diag/Scrubber.kt`).
  - **Device or other IDs**: not collected — the reporter drops the SDK's installation id and sends no sessions. If
    sessions are ever turned on, this answer must change.
- **Foreground service (`specialUse`)**: "Keeps the user's Nostr Wallet Connect relay subscription open so incoming
  Lightning payments are notified in real time; there is no server push." It is user-visible: the listener can be
  turned off in Settings, and a persistent notification shows while it runs.
- **Updates**: the Play build updates through Play only, using Play's in-app update flow from `com.google.android.play:app-update`.
  It never downloads or installs APKs, and the AAB does not declare `REQUEST_INSTALL_PACKAGES`. Only the GitHub/Zapstore APKs
  (built with `-PabiSplits`) carry that permission and install their own updates.
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
