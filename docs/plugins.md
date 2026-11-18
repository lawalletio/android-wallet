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

## manifest.json

```json
{
  "id": "io.example.wallet",
  "name": "Example",
  "description": "Your Example balance as a Lightning wallet",
  "version": "0.1.0",
  "api": 1,
  "main": "index.js",
  "icon": "icon.png",
  "provides": ["wallet"],
  "wallet": { "methods": ["get_balance", "pay_invoice", "make_invoice", "lookup_invoice", "list_transactions"] },
  "background": { "pollSec": 60 },
  "permissions": {
    "hosts": ["api.example.com"],
    "secrets": true,
    "webLogin": { "url": "https://login.example.com" }
  }
}
```

| Field | Rule |
|---|---|
| `id` | Reverse-DNS, lowercase (`^[a-z0-9]+(\.[a-z0-9-]+)+$`). It's the plugin's identity: storage, updates and a future native version all key on it. |
| `api` | `1`. A manifest with another value isn't installed. |
| `main`, `icon` | Paths inside the plugin's folder (no `..`, no URL). |
| `provides` | `"wallet"` for now. |
| `wallet.methods` | The NIP-47 methods it answers. Others fail with `NOT_IMPLEMENTED` before reaching the plugin. |
| `background.pollSec` | How often `poll()` runs while the app (or its payment listener) is alive. 0 or missing = never; values under 15 are raised to 15. |
| `permissions.hosts` | Exact hostnames `host.fetch` may call, over HTTPS. No wildcards, no subdomains. |
| `permissions.secrets` | Allows `host.secrets`. |
| `permissions.webLogin.url` | An HTTPS page the plugin may open for the user to sign in. |

Unknown fields are ignored, so new ones can be added without breaking old apps.

The install sheet shows the user every permission before anything runs.

## index.js

One ES module whose default export is an object. Every function receives the host API as its last argument (also on
`globalThis.host`).

```js
export default {
  // NIP-47: same method names, params and result JSON as a Nostr Wallet Connect wallet. Amounts in msat, times in
  // unix seconds. Throw { code, message } with a NIP-47 error code on failure.
  async request(method, params, host) {
    switch (method) {
      case 'get_balance': return { balance: 21000000 };
      case 'make_invoice': return { type: 'incoming', invoice: 'lnbc…', payment_hash: '…', amount: params.amount, created_at: 1700000000 };
      default: throw { code: 'NOT_IMPLEMENTED', message: method };
    }
  },

  // Background tick, every background.pollSec. Return NIP-47 notifications for payments since the last call.
  async poll(host) {
    return [{ notification_type: 'payment_received', notification: { type: 'incoming', payment_hash: '…', amount: 1000, created_at: 1700000000, settled_at: 1700000005 } }];
  },

  // Optional: who's signed in, shown in Settings → Plugins (email and a check). Read local state, don't call the network.
  async account(host) {
    const session = host.secrets.get('session');
    return session ? { signedIn: true, label: 'satoshi@example.com' } : { signedIn: false };
  },

  // Optional: Settings → Plugins → Sign out (shown instead of Sign in while account() says signedIn). Forget the
  // session. Without it, the app wipes everything the plugin stored.
  async logout(host) {
    host.secrets.set('session', null);
  },

  // Optional: Settings → Plugins → Sign in, and right after install when the manifest has webLogin.
  async login(host) {
    const session = await host.webLogin('https://login.example.com', `localStorage.TOKEN || null`);
    host.secrets.set('session', session);
  },
};
```

Methods the app calls through `request`: `get_balance`, `pay_invoice` (`invoice`, optional `amount`), `make_invoice`
(`amount`, optional `description`), `lookup_invoice` (`payment_hash`), `list_transactions` (`limit`, `from`, `until`,
`type`). Transactions use the NIP-47 shape:
`{ type, state, invoice, description, payment_hash, preimage, amount, fees_paid, created_at, settled_at, expires_at }`.

Error codes are NIP-47's (`RATE_LIMITED`, `INSUFFICIENT_BALANCE`, `NOT_FOUND`, `UNAUTHORIZED`, `INTERNAL`, …) plus the
host's own: `NETWORK` (offline: the app retries and catches up later), `FORBIDDEN` (a permission the manifest doesn't
grant), `TIMEOUT`, `CANCELLED` (the user closed the web login).

The app never lets a plugin confirm a payment: Send's own confirm screen is the only gate before `pay_invoice`.

## Host API

| Call | Returns | Notes |
|---|---|---|
| `host.fetch(url, { method, headers, body })` | `Promise<{ status, headers, body }>` | Only `permissions.hosts`, HTTPS. Redirects are **not** followed (you get the 3xx). `body` is a string (send JSON yourself); responses over 2 MB are cut. Headers are yours to set, User-Agent included. Rejects with `FORBIDDEN` or `NETWORK`. |
| `host.store.get(key)` / `.set(key, value)` | string or `null` | Plain storage for this plugin. `set(key, null)` deletes. |
| `host.secrets.get(key)` / `.set(key, value)` | string or `null` | Encrypted with the device's Keystore key; needs `permissions.secrets`. Written to disk before `set` returns, so a rotated refresh token survives a crash. |
| `host.webLogin(url, extractJs)` | `Promise<string>` | Opens `url` (its host must match `permissions.webLogin.url`) full screen. The user signs in there; the app never sees the password. `extractJs` is an expression evaluated in that page about once a second; the first string it returns resolves the promise and closes the page. Navigating the page to another host is blocked. Cookies and web storage are wiped when it closes. Rejects with `CANCELLED` if the user backs out. |
| `host.prompt({ title, message, input, error, actions })` | `Promise<{ value } \| { action: "resend" }>` | Asks the user for something the plugin needs to go on, like a security code before a payment. Shown as a dialog over whatever is on screen, with the plugin's icon and "Requested by <name>". `input: "code"` gives a number keyboard and keeps letters and digits only; `error` shows under the field (a wrong code); `actions: ["resend"]` adds a Resend button, answered as `{ action: "resend" }`. Rejects with `CANCELLED` if the user cancels. While it's open, the call's timeout is paused. |
| `host.uuid()` | string | Random UUID. |
| `host.now()` | number | `Date.now()`. |

There are no timers, sockets, `console` or other globals. Each plugin runs on its own thread with a 32 MB heap. A call
that never answers fails with `TIMEOUT` (20 s, 60 s for `pay_invoice`, 15 min for `login`). A plugin that blocks its thread
(a busy loop) is stopped; the app starts a fresh copy the next time it connects to the wallet (before any send or
receive).

Plugins never get the user's keys, NWC connections, Lightning address or LaWallet session. What they store is theirs
alone and is deleted with them.

## Isolation

A plugin only gets what the host API above hands it:

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
- **Background**: with the payment listener on (Settings → Background notifications), `poll()` keeps running while the app
  is closed, and its notifications show like any other payment.
- **Diagnostics**: breadcrumbs and performance records hold the plugin id, method, outcome and duration. Never params,
  results, URLs or anything the plugin returns.

## Native plugins

Inside the app, a plugin is a `Wallet` (`core/wallet/Wallet.kt`): one `request(method, params)` in NIP-47 terms plus a
`notifications()` flow. `NwcClient` and `JsWallet` both implement it. To move a plugin to Kotlin, implement `Wallet` and
register it in `PluginRegistry.wallet()` under the **same id**. It keeps the user's stored session, since storage is keyed
by id. The iOS app will mirror this with a Swift protocol and run the same `index.js` on JavaScriptCore.

## Later

- Create `github.com/lawalletio/mobile-plugins` (today `plugins-dev/` is a local repo with no remote) and suggest it in Add Plugin.
- Signed manifests (a Nostr event by the author's npub) with updates pinned to that key, before release builds get
  plugins. Nostr/Blossom as a second source.
- A `scan` capability, so a plugin can take payloads the scanner doesn't know (e.g. Mercado Pago or PIX QRs).
