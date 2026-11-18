# Plugins

A plugin adds a kind of wallet to LaWallet. It is one JavaScript file that runs in a sandbox (QuickJS on Android,
JavaScriptCore on iOS later). It can only reach the hosts its manifest lists, keep its own data, and ask the user to sign in
on its own website. The same contract works for a plugin rewritten as native code later.

> Status: Android, **debug builds only** (Settings → Plugins), or a release build made with `-Pplugins` to try them on a
> phone. Release builds will get the section for good once manifests are signed (see [Later](#later)).

Plugins live in their own repository: **[lawalletio/mobile-plugins](https://github.com/lawalletio/mobile-plugins)** (Fiwind
is there). This repo has only the host: the contract below, the sandbox, and the app's hooks.

## Repository layout

Settings → Plugins → **Add Plugin** takes a GitHub repository URL and lists every plugin in it:

```
plugins/
  example/
    manifest.json
    index.js        # one ES module, no imports (bundle TypeScript into it)
    icon.png        # optional
```

| Pasted URL | What the app reads |
|---|---|
| `https://github.com/<owner>/<repo>` | `plugins/*/manifest.json` on the default branch |
| `https://github.com/<owner>/<repo>/tree/<ref>` | the same, on a branch, tag or commit (refs with `/` work) |
| `https://github.com/<owner>/<repo>/tree/<ref>/<dir>` | `<dir>/*/manifest.json`, or `<dir>/manifest.json` |
| `https://<any host>/<path>/manifest.json` | that one plugin, `main` resolved next to it |

GitHub sources are pinned to the commit the ref pointed at when the user browsed: the manifest and bundle come from
`raw.githubusercontent.com/<owner>/<repo>/<sha>/…`. Browsing costs two GitHub API calls (unauthenticated limit: 60/h per
IP). Debug builds also accept `http://` URLs (see [Developing plugins locally](#developing-plugins-locally)).

On install the app downloads `main`, records its sha256, and refuses to run it if the file on disk ever stops matching.
**Update** re-reads the same source and installs it if the version or commit changed. Remove deletes the bundle and
everything the plugin stored.

## Developing plugins locally

Plugin code doesn't live in this repo. Debug builds read it from `plugins-dev/` (gitignored), a checkout of the plugins
repo with the same `plugins/<name>/` layout:

```bash
scripts/plugins-dev.py import https://github.com/lawalletio/mobile-plugins   # once: clone it into plugins-dev/
scripts/plugins-dev.py                                                       # serve it while you work
```

The script serves `plugins-dev/` on port 8787, with `/index.json` listing every plugin it finds and caching turned off. It
also runs `adb reverse tcp:8787 tcp:8787` on each connected device, so the emulator and phones on adb reach it at
`http://localhost:8787`, the debug build's default. For a device without adb, build with
`-PpluginsDevUrl=http://<mac-ip>:8787`.

In a debug build, Settings → Plugins → **Local (development)** lists those plugins:

- **Install** adds one with no permission sheet, since it's your own code, then signs in if it has a web login.
- **Reload** replaces the installed copy with the file as it is now. Its stored data and login stay, and the wallet
  reconnects to the new code.
- **Refresh** re-reads the list.

The loop:

1. Edit `plugins-dev/plugins/<name>/index.js`.
2. Tap Reload and try it.
3. Run the plugin's own test (e.g. `node plugins/<name>/test.mjs` in `plugins-dev/`).
4. Commit and push from `plugins-dev/` to the plugins repo.

Users then install the plugin from that repo's GitHub URL.

## Writing a plugin

A plugin is a `manifest.json` (id, version, the hosts it may call, whether it signs in on a website) and one
`index.js` whose default export answers NIP-47 wallet calls:

```js
export default {
  async request(method, params, host) {
    if (method === 'get_balance') {
      const r = await host.fetch('https://api.example.com/balance', { headers: { Authorization: host.secrets.get('token') } });
      return { balance: JSON.parse(r.body).sats * 1000 };
    }
    throw { code: 'NOT_IMPLEMENTED', message: method };
  },
  async login(host) {
    host.secrets.set('token', await host.webLogin('https://login.example.com', 'localStorage.TOKEN || null'));
  },
};
```

Every field, export, method, error code and host call is specified in the **[Plugin API reference](plugin-api.md)**.

Plugins never get the user's keys, NWC connections, Lightning address or LaWallet session. What they store is theirs
alone and is deleted with them.

## Isolation

A plugin only gets what the [host API](plugin-api.md#host-api) hands it:

- **Code:** one QuickJS context on its own thread, with a 32 MB heap. It has no file system, no `std`/`os` modules and no
  imports, and no access to the app's objects. A stuck plugin is stopped without affecting the app.
- **Network:** HTTPS to the hosts its manifest lists, and nowhere else. Redirects aren't followed. It runs on a client
  with no cookies and no LaWallet credentials.
- **Data:** its own key-value store and secrets, keyed by its id; it can't name another plugin's.
  - An update from the same source keeps them.
  - The same id from a different source starts clean, so a look-alike can't read another plugin's session.
  - Removing the plugin wipes them.
- **Wallets:** only its own. The account's NWC wallets, keys, nsec, Lightning address and LaWallet session are never
  exposed. It can't start a payment either: it's only asked to pay after the user confirms in Send.
- **UI:** a web login locked to its declared site, and a prompt that always says which plugin is asking.
- **Logs:** the app records the plugin id, method, outcome and duration only.

## How the app uses a wallet plugin

- **Adding**: each enabled wallet plugin with a web login, not signed in yet, is a choice in the Lightning wallet list's
  Add wallet sheet and in Settings → Remote wallets → Add ("Create <name> wallet", with the manifest's `icon`). Picking it
  runs `login()`.
- **Home**: each enabled wallet plugin shows as a card in the Lightning wallet list, with its icon and balance. One whose
  `account()` says it's signed out leaves the list until it signs in again; a plugin without `account()` always shows.
  A plugin wallet is **local**: it's listed, but never becomes the app's wallet (tapping its card opens Settings →
  Plugins). LaWallet routes Lightning addresses over NWC, so an address can't point at it either.
- **Moving sats**: a card's long-press menu has **Transfer amount** (from the wallet in use into this one; for a
  plugin, its `make_invoice` makes the invoice) and **Withdraw to active wallet** (from this one into the wallet in
  use, on every card but that one; for a plugin, its `pay_invoice` pays). Rename and Sweep aren't offered for plugins.
- **Paying from it**: the Send preview has a **Pay from** choice while a wallet plugin that answers `pay_invoice` is
  signed in. It defaults to the account's wallet; picking the plugin pays that one payment through its `pay_invoice`.
  A wall on the way never leaves the user stuck: a security code is asked with `host.prompt` and the payment goes on,
  and an `UNAUTHORIZED` (session expired) offers **Sign in to <name>** right there, after which they swipe again.
- **Background**: not yet. Only the active wallet's payments reach the payment listener, and a plugin wallet is never
  active, so `poll()` isn't scheduled today (see [Notifications](plugin-api.md#notifications-poll)).
- **Diagnostics**: breadcrumbs and performance records hold the plugin id, method, outcome and duration. Never params,
  results, URLs or anything the plugin returns.

## Native plugins

Inside the app, a plugin is a `Wallet` (`core/wallet/Wallet.kt`): one `request(method, params)` in NIP-47 terms plus a
`notifications()` flow. `NwcClient` and `JsWallet` both implement it. To move a plugin to Kotlin, implement `Wallet` and
register it in `PluginRegistry.wallet()` under the **same id**. It keeps the user's stored session, since storage is keyed
by id. The iOS app will mirror this with a Swift protocol and run the same `index.js` on JavaScriptCore.

## Later

- Suggest `github.com/lawalletio/mobile-plugins` in Add Plugin.
- Schedule `poll()` for plugin wallets, so their payments notify in the background.
- Signed manifests (a Nostr event by the author's npub) with updates pinned to that key, before release builds get
  plugins. Nostr/Blossom as a second source.
- A `scan` capability, so a plugin can take payloads the scanner doesn't know (e.g. Mercado Pago or PIX QRs).
