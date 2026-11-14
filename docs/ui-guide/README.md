# UI guide

[`LaWallet-UI-Guide.pdf`](LaWallet-UI-Guide.pdf) is a labelled map of the app: every captured screen and state,
with numbered markers on each component, a name for each one, and what it does. It exists so changes can be asked
for in plain words, for example "Home[Lightning·Docked] › SwipeHint: hide it after the first open". The naming
grammar is on page 3 of the PDF.

| File | Role |
|---|---|
| `guide.yaml` | **The source.** Sections, screens, states, component names, selectors, gestures, prose. |
| `shots/<id>.webp` + `shots/<id>.xml` | Screenshot plus the uiautomator view hierarchy it was taken with (marker positions come from it). |
| `build.py` | Annotates the shots and renders `build/guide.html`, then prints `LaWallet-UI-Guide.pdf` with headless Chrome. |
| `ui.py` | Small adb driver: open routes, tap by tag or text, swipe, capture. |
| `mock/server.py` | Fake LaWallet instance on `:8787`. It serves the REST API, a Nostr relay and NIP-47 wallets, all with dummy data. |

## One-time setup

```bash
python3 -m venv docs/ui-guide/.venv
docs/ui-guide/.venv/bin/pip install -r docs/ui-guide/requirements.txt
```

You also need Google Chrome (to print the PDF), an emulator (the shots use `LaWallet_API_36`, 1080×2400), and the
`JAVA_HOME` from AGENTS.md.

## Rebuild the PDF only (no app changes)

Edit `guide.yaml`, then run:

```bash
docs/ui-guide/.venv/bin/python docs/ui-guide/build.py
```

A `WARN <id>: no match for '<selector>'` line means a component can't be found in that shot any more. Its row is
struck through in red in the PDF. Pass `--strict` to make warnings fail the build.

## Re-capture after the UI changes

1. **Start the mock instance.** It prints the dummy login `nsec`. That key is derived from a fixed seed, is public
   and holds nothing.
   ```bash
   docs/ui-guide/.venv/bin/python docs/ui-guide/mock/server.py
   ```
   The dummy data is the `WALLETS`, `TXS`, `ADDRESSES` and `CARDS` lists at the top of the file. Invoices made by
   Receive are marked paid after `MOCK_SETTLE_AFTER` seconds (default 12), so Receive's summary can be captured.
2. **Install a separate copy of the app.** `io.lawallet.wallet.guide` installs next to any real install and never
   touches it. Point it at the mock *before its first launch*:
   ```bash
   ./gradlew assembleDebug -PidSuffix=.guide
   adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s emulator-5554 shell "run-as io.lawallet.wallet.guide mkdir -p shared_prefs && run-as io.lawallet.wallet.guide sh -c 'cat > shared_prefs/lawallet.xml'" <<'EOF'
   <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
   <map><string name="instance">http://10.0.2.2:8787</string></map>
   EOF
   adb -s emulator-5554 shell pm grant io.lawallet.wallet.guide android.permission.CAMERA
   ```
3. **Log in.** Open the app and go Login › Continue with Nostr, then paste the nsec. After that,
   **force-stop and relaunch once**. `route` intents are ignored for the rest of a process that started logged out.
4. **Drive and capture.** Use the `ui.py` commands below (alias: `u(){ docs/ui-guide/.venv/bin/python docs/ui-guide/ui.py "$@"; }`):
   ```bash
   u route settings/cards        # any R0 route from ui/AppNav.kt
   u shot 32-settings-cards      # refuses if another app is in front
   u nodes 32-settings-cards     # tags / text / bounds, to write selectors
   u tap balance-toggle          # by testTag, text or content-description
   u swipe 540 650 540 1600 400  # Home: open the wallet deck
   ```
   Some states have no route of their own:
   - Home gestures: see the coordinates in the `gestures:` entries.
   - Send amount: `adb shell am start -a android.intent.action.VIEW -d lightning:<bolt11> -n io.lawallet.wallet.guide/io.lawallet.wallet.MainActivity`.
     Make the invoice with `cd mock && ../.venv/bin/python -c "import server,os; print(server.bolt11(0, os.urandom(32).hex(), 'Lunch'))"`.
   - Payment notice: `--es devPayment incoming --el devSats 5000` on the same `am start`.
   - Saved contacts on Send: add a `contacts` JSON string to `shared_prefs/lawallet.xml` while the app is stopped.
5. **Update `guide.yaml`** and rebuild. Keep the ids stable, because they are the citation handles people use.

### Writing entries

```yaml
- id: 32-settings-cards          # = shots/<id>.*; the number orders it and groups it with its area
  name: Cards                    # PascalCase screen name; states of one screen share the name
  ref: "Cards[Disabled]"         # how to cite it: Name[State·State]
  route: settings/cards          # R0 value
  source: ui/screens/Settings.kt
  state: Two cards, one disabled
  reach: Settings › CardsRow
  summary: One or two sentences.
  gestures:                      # optional; arrow is drawn in magenta, in 1080×2400 screenshot pixels
    - {label: "Swipe ↓ wallet deck", arrow: [150, 470, 150, 1000], at: [40, 1060], does: "Opens …"}
  components:                    # numbered in this order
    - {name: TapPaymentsToggle, sel: "tag:card-toggle", does: "What it shows / what tapping it does"}
```

Selectors, in order of preference:

- `tag:<testTag>` (`#2` picks the second match). These are stable and greppable in `app/src`.
- `text:<copy>`, which falls back to a substring match.
- `desc:<content description>`.
- `box:x1,y1,x2,y2`, as a last resort.
- A list of selectors, which marks the union of their boxes.

If a component has no testTag and its text keeps changing, add a `testTag` in the code instead of using `box:`.
