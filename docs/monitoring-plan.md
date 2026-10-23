# Crash, process-death and performance monitoring — plan

_Status: Phases 1–3 implemented (see [Implementation notes](#implementation-notes)): Phase 3 is the app-wide trail
and sampled tracing. Phase 4 (review) is still to do._

**The problem.** After running for hours, the app dies with no visible error, and the payment-listener
foreground service notification disappears with it. The suspected cause is memory. Today we can't tell a crash, an
ANR, a low-memory kill, an OEM battery killer and the user tapping "Stop" in the Android task manager apart. That is
the first gap to close.

**TL;DR**
1. **Today, with no code changes:** read the device's own exit history with `adb` and check Play Console → Android
   vitals (see [Phase 0](#phase-0--today-no-code)).
2. **Recommended service:** the **Sentry Android SDK** (`sentry-android-core` only, no NDK, replay or OkHttp
   integration). It is **opt-in, off by default**, uses an allowlist scrubber, and sends to **sentry.io in the EU
   region** on the free Developer plan. Because GlitchTip speaks the same protocol, we can later move to a self-hosted
   GlitchTip by changing only the DSN.
3. **Code review:** I found no unbounded memory growth. There are two likely ways the service can die and stay dead,
   both described in [Code review findings](#code-review-findings). They should be fixed in their own PR, separate from
   the monitoring work.

---

## Implementation notes

**Where things are:**
- **Fixes:** `PaymentListenerService`, `LaWalletApp` and `AppContainer`.
- **Exit recorder, state summary and Sentry setup:** `app/Diagnostics.kt`.
- **Scrubber:** `core/diag/Scrubber.kt`, tested by `ScrubberTest`.
- **Trail (flight recorder):** `core/diag/Trail.kt`, tested by `TrailTest`. One redacted line per notable event,
  written to `filesDir/diag/trail.log`. Each launch moves the last run's log to `trail.prev.log`.
  - **What it records:** screens (route patterns such as `activity/{hash}`, never their arguments), each NWC request
    (method, outcome or error code, duration; balance polls only when slower than 5 s), relay open/close/failure/reconnect
    (close code or exception class, never the URL), LaWallet API errors (path shape such as `/api/wallet/addresses/:id`,
    status, error code), LNURL/HTTP errors (status or exception class, no host), auth state and login method, wallet
    client and wallet errors, payment notice type (no amount), foreground/background, listener on/off, memory trim
    levels, network available/lost, and a state line every 15 min (`fgs`, `relay`, uptime, heap, native).
  - **Crashes:** a chained uncaught-exception handler writes the exception classes and top frames, never messages,
    synchronously, so the stack survives with reports off. In release builds the frames are R8-obfuscated; retrace
    them with that version's `mapping.txt`.
  - **Where it goes:** with reports on, every line is also a Sentry breadcrumb with `origin = "trail"`. The newest
    `exit.*` event carries the dead run's last 60 lines when its pid matches the trail header. Copy report appends
    both trails.
- **Tracing:** `tracesSampleRate = 0.2` (app start, screen loads, slow/frozen frames). `Scrubber.transaction` drops
  any span whose description would need redacting, and clears span data.
- **Alerts:** "lawallet-android: new crash, ANR or kill" emails active members on every new issue at warning level
  or above. "lawallet-android: issue spiking (>10/h)" covers spikes. Both are `production` only, so debug and
  emulator events stay quiet. Sentry's default "high priority issues" alert also stays on.
- **UI:** Settings → Diagnostics, plus the prompt after an unexpected exit.

**Sentry project:** `la-crypta/lawallet-android`. The existing la-crypta org is in the **US** region, not the EU region
this plan proposed. `PRIVACY.md` says US.

**Where the implementation differs from the plan below:**
- **ANR thread dump:** `isAttachAnrThreadDump` is **off**. The raw dump is an attachment `beforeSend` can't scrub,
  and OkHttp names its threads after URLs. The ANR event's own threads are scrubbed and are enough. The on-device
  report keeps a redacted main-thread stack of the newest ANR instead.
- **Plugin config:** the org and project names are hard-coded in the Gradle `sentry {}` block. CI only needs
  `SENTRY_AUTH_TOKEN` (secret) and `SENTRY_DSN` (variable).
- **Breadcrumb allowlist:** instead of a list of extra categories, breadcrumbs marked `origin = "trail"` (ours,
  already redacted) pass in any category. The SDK's own breadcrumbs are still limited to app/ui lifecycle, device
  and network events, so an SDK `navigation` or `http` breadcrumb with a real route or URL stays out. `Relay` and
  `NwcClient` stay free of Sentry; they only call `Trail`.
- **Foreground-service crash found in Phase 0:** the stop-before-`startForeground()` crash is guarded. A `stop()`
  that arrives while a start is pending is deferred until the service has gone foreground.

**Release setup (done Oct 22–23):**
- Sentry → Security & Privacy: "Prevent Storing of IP Addresses", Data Scrubber and Default Scrubbers are on.
- GitHub: the `SENTRY_DSN` variable and the `SENTRY_AUTH_TOKEN` secret are set on `lawalletio/android-wallet`.

## Phase 0 results (Oct 22)

The connected test phone (Z92, API 33) doesn't have the wallet installed. The development emulator (API 36) had:

| When | Reason | Importance | Detail |
|---|---|---|---|
| Oct 20 03:24 | `CRASH` | 400 (background) | `ForegroundServiceDidNotStartInTimeException: Context.startForegroundService() did not then call Service.startForeground()`, for `PaymentListenerService` |
| Oct 22 19:30 and 19:31 | `ANR` | 400 | "bg anr: … failed to complete startup", right after a system_server restart |

The crash kills the app whenever a start races a stop, or whenever the main thread is too slow to reach
`startForeground()` in time. That makes it a direct candidate for "the notification stopped".

**First end-to-end run:** I installed a debug build with the DSN on the emulator, accepted the prompt, and the history
above arrived in Sentry.
- **The background ANRs aren't app code.** Sentry's ANR event (LAWALLET-ANDROID-5) shows the main thread inside
  `handleBindApplication` → `DexFile::CalculateChecksum` → `adler32`. ART was checksumming the 90 MB debug APK's dex on
  an emulator with a load average of 12, so none of our code had run yet.
- **Two privacy gaps:**
  - The SDK's `device.id` (an installation id) was being sent. The scrubber now removes it, and `ScrubberTest`
    covers it.
  - Sentry derived `user.geo` from the IP address on the server. Only the project setting "Prevent Storing of IP
    Addresses" stops that, which is why it's listed above as required before release.

## Code review findings

I read [`app/build.gradle.kts`](../app/build.gradle.kts), [`LaWalletApp`](../app/src/main/java/io/lawallet/wallet/LaWalletApp.kt),
[`AppContainer`](../app/src/main/java/io/lawallet/wallet/app/AppContainer.kt), [`PaymentListenerService`](../app/src/main/java/io/lawallet/wallet/service/PaymentListenerService.kt),
[`WalletRepository`](../app/src/main/java/io/lawallet/wallet/app/WalletRepository.kt), [`Relay`](../app/src/main/java/io/lawallet/wallet/core/nwc/Relay.kt),
[`NwcClient`](../app/src/main/java/io/lawallet/wallet/core/nwc/NwcClient.kt), [`Session`](../app/src/main/java/io/lawallet/wallet/app/Session.kt),
[`Prefs`](../app/src/main/java/io/lawallet/wallet/core/store/Prefs.kt) and [`PaymentSound`](../app/src/main/java/io/lawallet/wallet/service/PaymentSound.kt).

### Likely kill or "service gone" causes

1. **A system restart of the sticky service can crash on Android 12+.**
   In [`PaymentListenerService.kt:24-43`](../app/src/main/java/io/lawallet/wallet/service/PaymentListenerService.kt:24), the service
   returns `START_STICKY` and calls `startForeground()` unguarded from `onCreate`. After a low-memory kill, the system
   restarts the service in the background with a null intent. In that case `startForeground()` can throw
   `ForegroundServiceStartNotAllowedException`. Thunderbird
   [hit this in the field](https://git.wbrawner.com/wbrawner/thunderbird-android/commit/9401de25802c8251bd5beb098234c509f87650d7)
   and could not reproduce it reliably. The result is a crash on the restart. After a few of those, the system stops
   restarting the service, so the notification is gone for good. This fits the symptom.
   *Fix:* wrap `startForeground` in a try/catch for that exception on API 31+, call `stopSelf()`, and record the
   event. With the guard in place, a low-memory kill means only "the listener was down until the app was next opened".
   It no longer causes a crash loop.

2. **Opening the app doesn't restart a service that has stopped.**
   In [`LaWalletApp.kt:70-76`](../app/src/main/java/io/lawallet/wallet/LaWalletApp.kt:70), the service starts only when the
   `combine(auth, bg, client)` value *changes* while the app is in the foreground. Suppose the service stops while the
   process stays alive: the user tapped "Stop" in the Android 13+ task manager, the crash loop above gave up, or an OEM
   killer stopped it. Bringing the app to the foreground does not re-emit that value, so the listener stays off until
   the process dies.
   *Fix:* also call `PaymentListenerService.start` from the `ProcessLifecycleOwner.onStart` observer when the listen
   condition holds. `start` is already idempotent and wrapped in `runCatching`.

3. **The app-wide scope has no exception handler.**
   [`AppContainer.kt:19`](../app/src/main/java/io/lawallet/wallet/app/AppContainer.kt:19) creates
   `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`. Any exception that escapes a `launch` on it kills the
   process. The long-lived collectors are mostly wrapped in `runCatching` or `try`, with one exception: the payment-notice
   collector in [`LaWalletApp.kt:63-68`](../app/src/main/java/io/lawallet/wallet/LaWalletApp.kt:63) calls
   `PaymentSound.play`. That function builds an `AudioTrack` ([`PaymentSound.kt:41`](../app/src/main/java/io/lawallet/wallet/service/PaymentSound.kt:41)),
   and `build()` throws when the audio server is out of tracks.
   *Fix:* catch inside that collector, because an escaped exception would also end payment notifications. Then add a
   `CoroutineExceptionHandler` to the scope that reports the exception as a non-fatal event, so we learn about future
   escapes instead of crashing.

4. **The notification can disappear while the service is still alive.** On Android 14+, the user can swipe away a
   foreground-service notification even with `setOngoing(true)`. On Android 13+, the task-manager "Stop" button kills
   the whole app. Exit-info records the second case as `REASON_USER_REQUESTED`, so the data below will separate it from
   a real crash.

### Smaller issues (not kill causes, but worth capturing)

- **Work on the main thread.** The app scope runs on `Main.immediate`. Each relay event is decrypted (NIP-44/04) and
  JSON-parsed on the main thread. Each payment or activity refresh re-encodes up to 200 `Tx` (invoices included) and
  writes them to SharedPreferences ([`WalletRepository.kt:187-191`](../app/src/main/java/io/lawallet/wallet/app/WalletRepository.kt:187)).
  `connect()` also decodes that cache on the main thread ([`:137`](../app/src/main/java/io/lawallet/wallet/app/WalletRepository.kt:137)).
  The `apply()` writes are flushed by `QueuedWork.waitToFinish()` on service start/stop and activity stop, which is a
  well-known ANR source. Sentry's ANR reports and StrictMode in debug builds will show whether this matters in
  practice.
- **Malformed relay frames.** [`Relay.kt:108-114`](../app/src/main/java/io/lawallet/wallet/core/nwc/Relay.kt:108)
  indexes `arr[1]` without checking it exists. A malformed frame throws inside OkHttp's reader, which ends up in
  `onFailure` and triggers a reconnect. That's not a crash, but a misbehaving relay could cause a reconnect storm. A
  reconnect counter breadcrumb makes this visible.
- **Memory.** Nothing grows without bound. The relay message buffer is capped at 256, the seen-ids list at 200
  ([`Prefs.kt:89`](../app/src/main/java/io/lawallet/wallet/core/store/Prefs.kt:89)), the activity cache at 200, sound
  de-dup at 200, Coil at 15% of the heap plus 32 MB on disk, and subscriptions are removed in `finally`/`onCompletion`.
  If exit-info shows `REASON_LOW_MEMORY` with importance 125 (foreground service), the likelier explanation is system
  memory pressure plus how long the process has been alive, not a leak in our code. The PSS/RSS values recorded at
  death will settle it.

---

## Service comparison

| | **Sentry Android** (SaaS) | **Firebase Crashlytics + Performance** | **Bugsnag** (SmartBear) | **GlitchTip** (hosted or self-hosted) | **Self-hosted Sentry** |
|---|---|---|---|---|---|
| JVM crashes | ✅ | ✅ | ✅ | ✅ (Sentry SDK) | ✅ |
| ANRs | ✅ Exit-info–based on API 30+, watchdog on 28–29, optional thread dump | ✅ API 30+ | ✅ | ✅ Arrive as error events; less ANR-specific UI | ✅ |
| Native crashes | ✅ Tombstones on Android 12+ (SDK ≥ 8.32, out of process), or NDK integration | ✅ `crashlytics-ndk` | ✅ NDK plugin | Partial: events arrive, symbolication is basic | ✅ |
| Other exit reasons (low memory, user stop, excessive resource use) | ❌ We record them ourselves (below) | ❌ | ❌ | ❌ | ❌ |
| Breadcrumbs | ✅ Lifecycle, trim-memory and connectivity built in; custom | ✅ Custom logs/keys | ✅ | ✅ | ✅ |
| Performance tracing | ✅ App start, slow/frozen frames, custom spans | ✅ But auto-traces every OkHttp URL (LNURL `k1`, `pr=` invoices) | ✅ | Basic transactions | ✅ |
| R8 mapping upload | ✅ Gradle plugin, keyed by a build UUID | ✅ Gradle plugin | ✅ Gradle plugin / CLI | ✅ Via `sentry-cli` debug files ([backend MR !84](https://gitlab.com/glitchtip/glitchtip-backend/-/merge_requests/84)), needs a test | ✅ |
| Scrubbing hook before send | ✅ `beforeSend` / `beforeBreadcrumb`, full event access | ⚠️ No pre-send hook for crash messages | ✅ `onError` callbacks | ✅ (same SDK) | ✅ |
| Proprietary / Google Play services dependency | No (MIT SDK) | **Yes**: Firebase + GMS | No (MIT SDK, proprietary backend) | No | No |
| F-Droid | OK if off by default (FOSS SDK) | ❌ Blocks inclusion (non-free deps) | OK SDK, proprietary sink | ✅ Best fit | ✅ |
| Data location | US or EU region | Google | US (EU on higher tiers) | Their host, or ours | Ours |
| Cost | Free Developer plan (1 seat, ~5k errors/mo); Team ~$26/mo | Free | Free ~7.5k events/mo, 1 seat | Hosted: free 1k/mo, $15/mo for 100k. Self-host: a small VPS | Free licence, but heavy ops (Kafka, ClickHouse, …, ≥16 GB RAM) |

_Pricing comes from vendor pages and third-party summaries as of Oct 2026. Recheck before signing up._

### Recommendation: Sentry SDK, opt-in, sending to sentry.io (EU)

- **Privacy control.** `beforeSend` and `beforeBreadcrumb` see the whole event, so we can drop every exception
  message and every breadcrumb not on an allowlist. Crashlytics has no equivalent for crash messages. This matters
  here: a `kotlinx.serialization` `JsonDecodingException` puts the *input JSON* in its message, which can be a decrypted
  NWC payload with an invoice, preimage or amount.
- **It's the only option that leaves room to move.** The same SDK works with sentry.io, self-hosted Sentry and
  GlitchTip, so we're never locked in. Start on the free plan. If seats, volume or data sovereignty become a problem,
  move to a GlitchTip instance run by La Crypta, after one test crash confirms that mapping and ANR rendering work there.
- **It covers what the bug needs.** Sentry reads ANRs from exit-info and native crashes from tombstones on API 30/31+.
  We add our own reporting of the other exit reasons (below), which no vendor sends.
- **Crashlytics is ruled out** by its Google Play services dependency (no F-Droid, and a Google SDK inside a Nostr
  wallet) and by the lack of a pre-send scrubber. Firebase Performance would trace every OkHttp request URL.
  **Bugsnag** offers nothing Sentry lacks and can't be self-hosted short of an enterprise contract.

---

## Phase 0 — today, no code

Run these on the phone that showed the problem, with USB debugging on:

```bash
adb shell dumpsys activity exit-info io.lawallet.wallet
```

The output lists the last exits with `reason`, `importance`, `pss`, `rss` and `description`. To read it:

| What you see | Meaning |
|---|---|
| `LOW_MEMORY` with importance 125 | lmkd killed the foreground-service process. Check `pss`: a few hundred MB would mean a leak, ~100 MB means system pressure |
| `CRASH` | A JVM crash. A `ForegroundServiceStartNotAllowedException` here confirms finding 1 |
| `ANR` | The main thread was blocked |
| `USER_REQUESTED` | The user tapped Stop in the task manager, or force-stopped the app |
| `SIGNALED` (sig 9) / `OTHER` | Often an OEM battery killer |
| `EXCESSIVE_RESOURCE_USAGE` / `FREEZER` | The system judged the app too expensive in the background |

```bash
adb shell dumpsys meminfo io.lawallet.wallet
```

Also check Play Console → Android vitals for crash and ANR clusters. The release workflow already uploads the mapping
there, and native symbols ship in the AAB. On the phone, check whether battery optimisation exempts LaWallet; for OEM
behaviour, see dontkillmyapp.com.

## Phase 1 — fixes and local diagnostics (no third party)

This ships as its own PR, so the fixes can go out before any privacy-policy change.

1. **Fix findings 1–3** above.
2. **`ExitInfoRecorder`** (about 40 lines, API 30+, run on `Dispatchers.IO` from `LaWalletApp.onCreate`):
   ```kotlin
   val am = getSystemService(ActivityManager::class.java)
   am.getHistoricalProcessExitReasons(packageName, 0, 16)
       .filter { it.timestamp > prefs.lastExitTs }
       .forEach { record(reason, importance, pss, rss, description, timestamp, processStateSummary) }
   prefs.lastExitTs = max(timestamp)   // add "last-exit-ts" to clearSession()'s keep set
   ```
   - Skip `REASON_EXIT_SELF`, and skip `REASON_USER_REQUESTED` when it isn't the most recent exit.
   - Store the last 20 records in one Prefs string, and show them in **Settings → About → Diagnostics** with a "Copy"
     button. This gives F-Droid and sideload users (and us) the same data with no third party involved.
   - In Phase 2, the same records also go to Sentry as warning-level events.
3. **Process-state summary.** On API 30+, `ActivityManager.setProcessStateSummary()` stores up to 128 bytes that come
   back as `getProcessStateSummary()` on the next exit record. Update it when the relay connects or disconnects and
   every 15 minutes, for example `fgs=1;relay=1;up=312m;heap=41M;native=18M`. This shows the process's state just
   before it died, at no network cost.
4. **Debug-only tooling:**
   - `debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")`. It needs no code; it watches
     activities, view models and services.
   - `StrictMode` in `LaWalletApp.onCreate` behind `BuildConfig.DEBUG`, with `detectDiskReads`, `detectDiskWrites` and
     `penaltyLog`. This surfaces the SharedPreferences work on the main thread.
5. **Soak test.** Install a debug build, log in, turn the listener on, background the app and leave it for 12 hours.
   Meanwhile, on the host:
   ```bash
   while true; do adb shell dumpsys meminfo io.lawallet.wallet | grep -E 'TOTAL PSS|Java Heap|Native Heap'; sleep 900; done | tee soak.log
   ```
   A rising Java or Native heap points to a leak. Flat numbers followed by a death point to the system.

## Phase 2 — Sentry, opt-in

### Consent

- A Settings toggle, "Send crash reports", **off by default**.
- When `ExitInfoRecorder` finds an abnormal exit, show a one-time prompt on the next start: "LaWallet closed
  unexpectedly. Send an anonymous crash report?" The choices are "Send" (which also turns the toggle on) and "Not now".
- The SDK is initialised only when consent is on. Disable auto-init with
  `<meta-data android:name="io.sentry.auto-init" android:value="false"/>` and call `SentryAndroid.init` from
  `LaWalletApp`.

### SDK setup

```kotlin
// libs.versions.toml: sentry = "<latest 8.x ≥ 8.32>", sentryGradle = "<latest>"
implementation(libs.sentry.android.core)          // NOT io.sentry:sentry-android (that pulls ndk + replay)
plugins { alias(libs.plugins.sentry) }

sentry {
    autoInstallation { enabled = false }           // no OkHttp/Compose/Fragment auto-integrations
    tracingInstrumentation { enabled = false }     // no bytecode-injected OkHttp spans (URLs carry invoices)
    includeSourceContext = false
    includeProguardMapping = true                  // generates the mapping UUID
    autoUploadProguardMapping = System.getenv("SENTRY_AUTH_TOKEN") != null   // only CI uploads
    ignoredBuildTypes = setOf("debug")
}
// DSN from CI only; local and F-Droid builds get "" → SDK stays off.
buildConfigField("String", "SENTRY_DSN", "\"${findProperty("sentryDsn") ?: ""}\"")
```

```kotlin
SentryAndroid.init(this) { o ->
    o.dsn = BuildConfig.SENTRY_DSN
    o.isSendDefaultPii = false
    o.isAttachScreenshot = false            // would show the balance
    o.isAttachViewHierarchy = false
    o.isEnableUserInteractionBreadcrumbs = false
    o.isEnableAutoSessionTracking = false   // sessions carry the installation id; Play vitals has crash-free rates
    o.isAnrEnabled = true
    o.isAttachAnrThreadDump = true          // thread names and stack frames only
    o.isTombstoneEnabled = false            // turn on only after checking a tombstone payload (see below)
    o.tracesSampleRate = 0.0                // Phase 3
    o.beforeBreadcrumb = SentryScrubber::breadcrumb
    o.beforeSend = SentryScrubber::event
}
```

**Native crashes.** The only native code in the app is secp256k1 JNI, through which private keys pass. Keep
`sentry-android-ndk` out. Enable tombstones only after we've inspected one event and confirmed it carries frames but no
register or stack-memory dump. Until then, exit-info `REASON_CRASH_NATIVE` and Play vitals cover native crashes.

### What to scrub (`SentryScrubber`)

This is an allowlist: anything not listed is dropped.

| Field | Rule |
|---|---|
| `exception.values[].value` (messages) | **Drop all of them.** Keep the type, module and frames. Our stack frames are enough, and messages from serialization, URLDecoder, NWC errors and relay `OK` reasons can contain payloads |
| `event.message` | Keep only messages we wrote ourselves (exit-info events, `fgs.*`). Drop anything else |
| `event.user`, `request`, `serverName` | Set to `null`. This also removes the SDK's default installation-id user |
| Breadcrumbs | Keep the categories `app.lifecycle`, `ui.lifecycle`, `device.event` (trim-memory, low battery), `network.event` (connectivity), plus our own `relay`, `fgs` and `memory`. Drop `navigation` (routes include `activity/{paymentHash}`), `http` and `console`/logcat |
| Tags and contexts | Only ours. Allowed: `fgs_enabled`, `relay_connected`, `uptime_min`, `standby_bucket`, `battery_opt_ignored`, `heap_mb`, `native_mb`, `api`. Never the instance URL, Lightning address, pubkey or balance |
| Every remaining string (backstop) | Redact with these regexes: `(nsec\|npub\|nprofile\|lnurl\|lnbc\|lntb\|lnbcrt\|lnts)1[0-9a-z]+`, `nostr\+?walletconnect:\S+`, `[0-9a-f]{64}` (keys, hashes, preimages, event ids), `secret=\S+`, `eyJ[\w-]+\.[\w-]+\.[\w-]+` (JWT), `\S+@\S+\.\S+` (Lightning addresses) |

The PR must include **one unit test**. It builds a `SentryEvent` with an nsec, an NWC URI, a bolt11 invoice, a
64-hex value, a Lightning address and an amount in every field above, runs it through the scrubber, and asserts that
none survive in the serialized JSON.

Server-side settings on the Sentry project:
- "Prevent storing of IP addresses": on.
- Data scrubber and default scrubbers: on.
- Add the regexes above as advanced scrubbing rules, as defence in depth.

### Exit-info events and custom breadcrumbs

- Each `ExitInfoRecorder` record becomes `Sentry.captureMessage("exit.<reason>", WARNING)`, with tags `reason`,
  `importance`, `pss_mb`, `rss_mb` and the process-state summary. Grouping by reason gives a dashboard of *why the
  process died*.
- `relay` breadcrumbs: connect, drop (with close code, no URL) and reconnect attempt number.
- `fgs` breadcrumbs: start, stop, and a caught `ForegroundServiceStartNotAllowedException`.
- `memory` breadcrumb on `onTrimMemory(level)`, with heap and native MB.

### R8 mapping upload (`.github/workflows/release.yml`)

Add to the env of the existing "Build signed AAB + per-ABI APKs" step:

```yaml
          SENTRY_AUTH_TOKEN: ${{ secrets.SENTRY_AUTH_TOKEN }}   # org auth token, project:releases scope
          SENTRY_ORG: ${{ vars.SENTRY_ORG }}
          SENTRY_PROJECT: ${{ vars.SENTRY_PROJECT }}
```

Also add `-PsentryDsn="${{ vars.SENTRY_DSN }}"` to `ARGS`.

- Both Gradle runs, `bundleRelease` and `assembleRelease -PabiSplits`, upload their own mapping under their own UUID,
  so APK crashes and AAB crashes both deobfuscate.
- If the secret is missing, upload is skipped and the build still passes.
- The repo has no Actions secrets yet. Add `SENTRY_AUTH_TOKEN` as a secret, and `SENTRY_ORG`, `SENTRY_PROJECT` and
  `SENTRY_DSN` as variables. The DSN isn't secret, but keeping it out of the repo keeps F-Droid source builds
  report-free without needing a flavor.
- Play's mapping upload stays as it is.

### `PRIVACY.md` changes

- Replace "contains no analytics, advertising or tracking SDKs" with "contains no analytics or advertising SDKs. It
  includes an optional crash reporter (Sentry), off unless you turn it on".
- Add a **"Crash reports (optional)"** section with:
  - **What is sent:** stack traces, the reason the system closed the app, memory use, app and Android version, device
    model, and whether the payment listener and relay were connected.
  - **What is never sent:** keys, NWC connection strings, invoices, amounts, balances, Lightning addresses, public keys
    and screenshots. IP addresses aren't stored.
  - **Who processes it:** Functional Software Inc. (Sentry), EU data region.
  - **Retention:** the plan's retention period, 90 days at most.
  - **How to turn it off:** Settings → Send crash reports.
- Update the "Last updated" date.

### Play data safety (`PLAY_STORE.md`)

- **App info and performance → Crash logs; Diagnostics:** Collected. Not shared, because Sentry acts as a service
  provider. **Optional:** users can choose. Purpose: App functionality and Analytics. Encrypted in transit.
- **Device or other IDs:** not collected, as long as the scrubber nulls `user` and sessions stay off. If we ever turn
  sessions on, this has to change.

### F-Droid and Zapstore

- **Zapstore** ships the GitHub release APKs, which include the DSN and the opt-in toggle.
- **F-Droid** builds from source with no `sentryDsn`, so the SDK never initialises and there's nothing to flag. If the
  reviewers object to the library being present at all, add a `foss` flavor with `sentry-android-core` swapped for a
  no-op. Only do this if they ask.

## Phase 3 — performance (after Phase 2 has run for two weeks)

- Set `tracesSampleRate = 0.05` for consenting users. This gives app start, activity load and slow/frozen frames
  through the core SDK.
- Add manual spans with `op` only, never URLs or params:
  - `nwc.request` with tag `method=get_balance|pay_invoice|…`.
  - `relay.connect`.
  - `lnurl.resolve` with tag `kind` only.
- Leave out profiling and session replay.

## Phase 4 — review (four weeks after Phase 2)

- Look at the exit-reason breakdown and decide what to fix next.
- Decide whether to stay on sentry.io or move to a self-hosted GlitchTip. Before switching, send one test crash and one
  ANR from a release build to confirm mapping and ANR rendering there.

## Open questions for the reviewer

1. Consent default: off with a prompt after a crash (proposed), or on by default with disclosure at onboarding?
2. Backend: sentry.io EU free plan (proposed), or start straight on La Crypta–hosted GlitchTip?
3. Should Phase 1's findings 1–3 ship as a hotfix (1.0.1) ahead of the monitoring work?
