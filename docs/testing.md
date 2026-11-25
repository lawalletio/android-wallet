# Testing

| Layer | Where | Runs in CI | Command |
|---|---|---|---|
| Unit (pure logic, parsers, crypto, API client) | `app/src/test` | yes | `./gradlew testDebugUnitTest` |
| Robolectric (`Prefs`, `Session`, `OnchainStore`, repositories) | `app/src/test` | yes | same |
| Compose flows (Onboarding, Home, Send, Receive, CashOut, Settings, …) | `app/src/test/.../ui/flows` | yes | same |
| Screenshot goldens (Roborazzi) | `app/src/test/screenshots` | no (local) | `./gradlew verifyRoborazziDebug` |
| Instrumented (Keystore, NFC intents, real runtime) | `app/src/androidTest` | no (local) | `scripts/test-device.sh` |
| End to end (full journeys on a device) | `app/src/androidTest/.../e2e` | no (local) | `scripts/test-device.sh` |

Run one class: `./gradlew testDebugUnitTest --tests 'io.lawallet.wallet.app.SessionTest'`, or
`scripts/test-device.sh -c io.lawallet.wallet.SecretStoreTest` on a device.

## Fakes

`app/src/sharedTest/java` is compiled into both `test` and `androidTest`, so JVM and device tests share one set:

- `core/FakeNwcWallet`: a relay and NIP-47 wallet service on MockWebServer. It holds a balance, pays and creates
  invoices, and sends payment notifications.
- `testing/FakeInstance`: a LaWallet instance on MockWebServer. Its account's wallet is a `FakeNwcWallet`. Every
  response is a settable field (addresses, remote wallets, swaps, bank accounts, rates…). `offline = true` drops every
  request, and `requests` records each call for assertions. `FakeInstance.invoice(sats, description)` builds a valid
  BOLT11 invoice.
- `testing/TestContainer`: a real `AppContainer` pointed at a `FakeInstance`, plus `loginThrowaway()`. On the JVM it
  installs `FakeAndroidKeyStore`, because Robolectric has no Keystore.

Nothing in a test reaches a real instance, relay or wallet. Keys are throwaway (`FakeInstance.throwawaySecret()`).
Never paste a real nsec, NWC string or address into a test: the privacy rule in `AGENTS.md` applies to tests too.

Production code only bends for tests through defaults: `AppContainer(context, scope, http)`, `AppLock(…, auth)`, and
the `internal` setter on `LaWalletApp.container`.

## Screenshots

Flow tests call `captureRoboImage()` at key states, in English and Spanish. Plain `testDebugUnitTest` only writes the
images; it doesn't compare them.

```bash
./gradlew verifyRoborazziDebug     # fails on any visual diff (report: app/build/outputs/roborazzi)
./gradlew recordRoborazziDebug     # after an intended UI change: rewrite goldens, review the PNG diff, commit
```

Screens with countdowns, timestamps or random keys are asserted but not captured.

## On a device

`scripts/test-device.sh [-s serial] [-c Class[#method]]` builds a side-by-side `io.lawallet.wallet.e2e` copy
(`-PidSuffix=.e2e`), installs it and its test APK, clears only that copy, and runs `am instrument`. The real
`io.lawallet.wallet` install and its account are never touched. By default it uses the first emulator `adb devices`
lists. Don't use `connectedDebugAndroidTest`: it uninstalls the app afterwards.

The e2e tests start a `FakeInstance` inside the app process (127.0.0.1) and swap `LaWalletApp.container` for a
`TestContainer` before `MainActivity` starts.

## Regression tests

A `fix(...)` commit ships with a test that fails without the fix. It is named `regression_<sha>_<what>` and carries a
`// regression: <sha> <subject>` comment, so `grep -r "regression: "` lists what is pinned.

Robolectric's biometric shadow always reports a device lock, so the "no device lock" path of `AppLock` is only
checked by hand. A biometric prompt can't be passed on an emulator without enrolled credentials either.
