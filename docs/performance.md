# Performance tracking

The app measures itself on every build, all the time, on the device. The goal is to have data for every change:
measure, change, measure again, and keep the numbers.

## What is recorded

`core/diag/Perf.kt` writes one JSON object per line to `files/perf/perf.jsonl` on the device (rotated at 1 MB to
`perf.1.jsonl`) and logs the same line under the `LwPerf` logcat tag.

| `t` | When | Fields |
|---|---|---|
| `screen` | each visit of a route, cut on the next navigation or on going to background | `route` (pattern), `ms`, `heap_mb`; frames after the transition: `frames`, `janky`, `jank_pct`, `frozen`, `p50`/`p90`/`p99`/`max`, `ui_p90` (main thread), `gpu_p90` (API 31+); the same with `enter_` for the enter transition (until the navigation settles) |
| `start` | first frame of the process | `ui_ms`: activity created → first frame; `cold_ms`: process start → first frame, only on a true cold start (not when the payment listener had the process running) |
| `res` | every minute in foreground | `heap_mb`, `native_mb`, `pss_mb`, `threads`, `pools` (threads by pool, largest first: `DefaultDispatch:7,OkHttp TaskRunn:4,…`; URL-named threads count as `other`), `cpu_pct` (share of one core since the last sample), `route` |
| `nwc` | each NIP-47 request | `method`, `ms`, `outcome` (`ok`, `cancelled`, or the error code) |
| `plugin` | each wallet-plugin request | `plugin` (id), `method`, `ms`, `outcome` |
| `api` | each LaWallet REST call | `method`, `path` (ids and usernames replaced by `:id`), `ms`, `outcome` (HTTP status or the exception class) |

Every record also has `ts`, `build` (debug/release), `vc` (versionCode), `pid`, `api`.

Frames come from the platform's `FrameMetrics` (no library). A frame is janky when it misses its own deadline (one
refresh interval: 8.3 ms at 120 Hz), frozen over 700 ms. `ui` vs `gpu` says which side to work on: a high `ui_p90`
is Compose work (recomposition, layout, state read in composition); a high `gpu_p90` is what's drawn (blurs, layers,
overdraw, large bitmaps).

**Privacy** (AGENTS.md): only route patterns (`activity/{hash}`, never the arguments), NWC method names, counts and
timings. Never keys, amounts, addresses, invoices or URLs. The log stays on the device until someone pulls it.

## Sentry

With reports on and a DSN, each screen's `ui.load` transaction carries the SDK's slow/frozen frames plus our
`enter_*` measurements, and NWC requests are `nwc.request` spans. Release builds sample 20% of screens. Debug builds
trace every screen and profile each one (`ProfileLifecycle.TRACE`), so a dev build with a DSN gives flame graphs per
screen:

```bash
./gradlew installDebug -PsentryDsn=<dsn>   # then Settings → Diagnostics → crash reports on
```

Debug events go to the `debug` environment, so they don't mix with production.

## Workflow

```bash
scripts/perf-report.py clear -s <serial>                 # clean slate (debuggable builds)
# … use the flow you're measuring, a few times …
scripts/perf-report.py pull -s <serial> --label before   # → perf-data/<time>-<device>-before.jsonl
# change the code, install, clear, repeat the same flow
scripts/perf-report.py pull -s <serial> --label after
scripts/perf-report.py compare perf-data/*-before.jsonl perf-data/*-after.jsonl
scripts/perf-report.py report                            # newest pull: screens, start, resources, NWC
scripts/perf-report.py report perf-data/*.jsonl --route forward   # everything so far, one screen
```

`pull` reads the app's files through `run-as` on debuggable builds; on release builds it falls back to the `LwPerf`
lines still in logcat. It also saves a `sys` record from `dumpsys gfxinfo` and `meminfo` (the system's own count), and
on debug builds a `leak` record per leak LeakCanary found (its `leaks.db`: class and field names only).

`report` marks NWC, plugin and API calls made while the app wasn't visible (`bg`): background work should be close to
zero apart from the payment listener's notification stream.
`perf-data/` is gitignored: it's the local history to compare against.

**Judge smoothness on a release build.** Debug builds have no R8 and no baseline profile, and Compose runs a lot
slower without them. Use them to compare before/after on the same build type, not to decide whether something is
smooth enough. To check a release build on a phone without losing its data, sign it with the installed app's key
(see AGENTS.md → Signing and devices).

For controlled, repeatable runs (cold start, onboarding frames) there is also the Macrobenchmark module:
`./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`.

## Targets

- Enter transitions: `enter_jank_pct` under 5%, `enter_max` under 2 frames (17 ms at 120 Hz).
- Screens at rest: `jank_pct` under 1%, no frozen frames.
- Cold start: under 1 s on a release build.
- Memory: `heap_mb` and `pss_mb` flat over a long session (growth means a leak); `threads` back near its start value
  after leaving a screen (`pools` says which pool kept them). No LeakCanary leaks.
- Background: no `bg` balance, plugin or API polling.

## Rules that keep transitions smooth

- Nothing heavy starts until the screen has landed: the keyboard (`Modifier.autoFocus()` waits for it), the camera
  (`QrCamera`), network lookups that repaint. Use `nav.awaitSettled()` / `rememberSettled()` from `ui/AppNav.kt`.
- Read animated values in draw or layout lambdas (`graphicsLayer { }`, `offset { }`, `drawBehind { }`), not in
  composition, so a frame doesn't recompose.
- CPU work (crypto, JSON, parsing, QR generation) goes to `Dispatchers.Default`, never the main thread.
- Infinite animations stop when not visible.
