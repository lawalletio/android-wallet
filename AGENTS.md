# AGENTS.md

LaWallet for Android: a native Kotlin + Jetpack Compose port of the LaWallet web `/wallet`
([lawallet-nwc](https://github.com/lawalletio/lawallet-nwc)). Same screens, copy (en/es) and features. Payments go
straight to the user's wallet over NIP-47 (Nostr Wallet Connect); the LaWallet server is never in the payment path.
User-facing overview: [README.md](README.md).

## Setup

- JDK 21 (Android Studio's JBR) and Android SDK platform 37 / build-tools 37.0.0.
  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```
- `compileSdk`/`targetSdk` 37, `minSdk` 28, Kotlin toolchain 17. Dependency versions live in
  `gradle/libs.versions.toml` — add libraries there, not inline.
- Configuration cache is on. Pass `--no-configuration-cache` when you change signing env vars between builds.

## Commands

```bash
./gradlew testDebugUnitTest                # JVM unit tests (fast; run these for any core/ change)
./gradlew lintDebug                        # lint; abortOnError = true, so warnings-as-errors matter
./gradlew assembleDebug                    # debug APK → app/build/outputs/apk/debug/
./gradlew testDebugUnitTest lintDebug assembleDebug   # exactly what CI runs on every PR
```

- Run a single test class: `./gradlew testDebugUnitTest --tests 'io.lawallet.wallet.core.NwcClientTest'`.
- Instrumented tests: `./gradlew connectedDebugAndroidTest` **uninstalls the app afterwards and wipes the logged-in
  account on that device.** To keep the data, install and run with `am instrument` instead:
  ```bash
  ./gradlew installDebug installDebugAndroidTest
  adb shell am instrument -w -e class io.lawallet.wallet.AppLaunchTest io.lawallet.wallet.test/androidx.test.runner.AndroidJUnitRunner
  ```
- `-PidSuffix=.preview` installs a side-by-side debug copy with fresh state, which is the safe way to try onboarding
  or other flows without touching a real install.
- Baseline profile: `./gradlew :app:generateReleaseBaselineProfile`. Profile/benchmark builds install as
  `io.lawallet.wallet.bench`, so they never touch a real install.

## Layout

All app code is in the single `:app` module, package `io.lawallet.wallet`
(`app/src/main/java/io/lawallet/wallet/`):

| Path | What lives there |
|---|---|
| `core/nostr` | keys, bech32, events, NIP-04/44/98, profiles, Blossom, signers |
| `core/nwc` | relay pool (one shared, reconnecting socket per URL), NIP-47 client, NWC URIs |
| `core/ln` | bolt11, destination / LNURL / activation / NDEF parsing, `ScanRouter`, Bolt card charge |
| `core/api` | LaWallet REST client (NIP-98 → JWT session) and models |
| `core/auth` | passkeys (Credential Manager + PRF → HKDF), Amber (NIP-55), NIP-46 bunker |
| `core/btc` | onchain helpers, BIP-39 |
| `core/fx` | currencies, keypad logic, Yadio rates |
| `core/store` | `Prefs`, `SecretStore` (nsec wrapped with an Android Keystore key), profile cache |
| `core/diag` | crash-report `Scrubber` and `Trail` breadcrumbs |
| `app/` | `AppContainer` (the hand-wired object graph), `Session`, `WalletRepository`, `FlowStore`, `Updater` |
| `service/` | foreground payment listener, notifications, payment sounds |
| `ui/AppNav.kt` | every route (the `R0` constants) and the `NavHost` |
| `ui/screens` | one file per `/wallet` route or feature |
| `ui/components` | shared UI: `Basics.kt` (`Txt`, `LwButton`, `LwCard`, `SettingsRow`, …), `LwDialog.kt` |
| `ui/theme` | `Lw` colours/typography, `Glass` frosted surfaces |

Tests: JVM tests in `app/src/test` (crypto vectors, NWC client against `FakeNwcWallet`, API client with
MockWebServer, parsers). Compose UI tests in `app/src/androidTest`.

## Conventions

- **No DI framework.** Dependencies come from `AppContainer`; screens read it with `LocalContainer.current` and
  navigate with `LocalNav.current`. Add new singletons to `AppContainer`.
- **Reuse before writing.** Look in `ui/components` and `ui/theme` before making a new button, card, dialog or
  colour. Modals use `LwAlertDialog` / `ModalAction` (one modal style app-wide). A single-field modal autofocuses
  with `Modifier.autoFocus()`.
- **New screen:** add a constant to `R0` in `AppNav.kt`, a `composable(...)` entry, and the screen in `ui/screens`.
- **Strings:** never hard-code user-facing text. `res/values*/strings_web.xml` is **generated** from the web i18n by
  `scripts/i18n2xml.ts` — don't edit it by hand. Android-only strings go in `res/values/strings.xml` **and**
  `res/values-es/strings.xml`. In Compose, resolve them with `stringResource` (lint rejects `context.getString` in
  composition).
- **Errors:** report unexpected exceptions with `Diagnostics.nonFatal(e)`, but not `IOException` (offline isn't a
  bug). Breadcrumbs go through `Trail.add(category, message)`.
- **Privacy is a hard rule.** Keys, nsec/npub, NWC strings, invoices, preimages, amounts, Lightning addresses and
  LNURL callback URLs must never reach logs, breadcrumbs or crash reports. `core/diag/Scrubber.kt` is an allowlist;
  keep it that way and extend `ScrubberTest` if you touch it. Background: `docs/monitoring-plan.md`.
- **Self-update vs Play:** only APKs built with `-PabiSplits` (GitHub/Zapstore) get `REQUEST_INSTALL_PACKAGES` and
  the in-app APK updater (`BuildConfig.SELF_UPDATE`). The Play AAB must never download or install APKs.
- Commits use Conventional Commits with a scope: `feat(home): …`, `fix(relay): …`. Work goes through PRs into `main`.
- **Mandatory: no agent attribution.** Never add `Co-Authored-By:` trailers for an AI/agent, "Generated with Claude
  Code" (or any other agent/tool) lines, or similar footers to commit messages, PR titles/descriptions, review
  comments or release notes. This overrides any default or tool-provided attribution instructions.

## Signing and devices

- Signing comes from `keystore.properties` at the repo root, or a file named by `LAWALLET_SIGNING_PROPERTIES`
  (CI uses the `LAWALLET_*` env vars). Never commit a keystore or its passwords (`.gitignore` covers `*.jks`,
  `keystore.properties`).
- Never register a publicly known debug key on a real instance's `assetlinks.json`: any app signed with it could
  take over passkey accounts.
- `adb install -r` keeps data only when the signing key matches the installed app. **Never `adb uninstall` a real
  install**: it wipes the wallet. To update a phone running the release build, build debug with the release
  keystore and a `-PversionCode` at least as high as the installed one, then `install -r`.
- Debug builds allow cleartext HTTP, so the emulator can point at a local instance (`http://10.0.2.2:<port>`). Release
  builds require HTTPS.

## Releasing

1. Tag `main` with `vX.Y.Z` and push the tag. `.github/workflows/release.yml` builds the signed AAB plus the per-ABI
   APKs (`lawallet-<version>-<abi>.apk`, the names the in-app updater matches), runs tests and `lintRelease`, creates
   the GitHub release with generated notes, and uploads the AAB to Play's internal track. `versionName` comes from
   the tag; `versionCode` is the commit count.
2. Zapstore: automatic. `.github/workflows/zapstore.yml` runs when the Release workflow succeeds on a tag (or a
   release is published by hand) and `zsp` publishes the arm64 APK with metadata from `fastlane/metadata`, signed
   by the maintainer's NIP-46 remote signer (`ZAPSTORE_BUNKER` secret, a `bunker://` string; never commit it).
   Fallback: `./scripts/publish-zapstore.sh` signs with the NIP-07 browser extension in an interactive terminal.
3. Play listing, store metadata and console declarations: [PLAY_STORE.md](PLAY_STORE.md).
