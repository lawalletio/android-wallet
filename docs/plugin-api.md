# Plugin API reference

The exact contract between LaWallet and a plugin, at API version **1**. For what plugins are, how to develop one locally
and how the app presents them, start with [plugins.md](plugins.md). Example plugins: [lawalletio/mobile-plugins](https://github.com/lawalletio/mobile-plugins).

- [Lifecycle](#lifecycle)
- [manifest.json](#manifestjson)
- [The plugin module](#the-plugin-module)
- [Wallet methods](#wallet-methods)
- [Notifications: `poll()`](#notifications-poll)
- [Errors](#errors)
- [Host API](#host-api)
- [Runtime limits](#runtime-limits)
- [App side (Kotlin)](#app-side-kotlin)

## Lifecycle

```
Add Plugin URL ──▶ PluginResolver ──▶ PluginCandidate ──▶ install sheet (permissions) ──▶ PluginRegistry.install
                   (GitHub tree or                         user confirms                  download main, sha256,
                    one manifest URL)                                                      icon, record it
                                                                                                 │
app needs the wallet ──▶ PluginRegistry.wallet(id) ──▶ hash check ──▶ JsPlugin (QuickJS, own thread) ──▶ JsWallet
                                                                       built on first call                  : Wallet
```

1. **Resolve.** A pasted URL becomes one or more `PluginCandidate`s: the parsed manifest, the source URL, and the
   bundle and icon URLs, pinned to a commit for GitHub (URL forms: [plugins.md](plugins.md#repository-layout)).
2. **Install.** The user sees every permission first. The app then downloads `main` (2 MB max) and the icon (512 KB max,
   best effort). It records the bundle's sha256 and stores the files in `files/plugins/<id>/`.
3. **Load.** On first use the bundle is hashed again and refused if it no longer matches. A QuickJS context is created
   on the plugin's own thread, the host API is set up, and the module is imported.
4. **Call.** The app calls the module's functions (below) and waits for their promises. One context serves every call
   until the plugin is updated, disabled, removed or stopped for being stuck.
5. **Update.** **Check for updates** resolves the same source again and reinstalls when the version or commit changed.
   The plugin's stored data is kept. The same `id` from a *different* source wipes it first.
6. **Remove.** Deletes the bundle, the icon, everything in `store` and `secrets`, and the cached account.

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

| Field | Type | Default | Rule |
|---|---|---|---|
| `id` | string | required | Reverse-DNS, lowercase: `^[a-z0-9]+(\.[a-z0-9-]+)+$`. The plugin's identity: storage, updates and a future native version all key on it. |
| `name` | string | required | Not blank. Shown on cards, dialogs and the login screen. |
| `description` | string | `""` | Shown in the install sheet. |
| `version` | string | required | Not blank. Compared as text: any change counts as an update. |
| `api` | number | `1` | Must be `1`, or the plugin isn't offered. |
| `main` | string | `"index.js"` | A path inside the plugin's folder: no scheme, no leading `/`, no `..`. |
| `icon` | string | none | Same path rule. Kept on the device at install. |
| `provides` | string[] | `[]` | `"wallet"` is the only capability today. |
| `wallet.methods` | string[] | `[]` | The [wallet methods](#wallet-methods) the plugin answers. A plugin is a wallet only with both `provides: ["wallet"]` and this object. |
| `background.pollSec` | number | `0` | Seconds between [`poll()`](#notifications-poll) calls. `0` means never; values from 1 to 14 are raised to 15. |
| `permissions.hosts` | string[] | `[]` | Exact hostnames for `host.fetch`. No scheme, path, port or wildcard: `a.example.com` doesn't allow `b.example.com`. |
| `permissions.secrets` | boolean | `false` | Allows `host.secrets`. |
| `permissions.webLogin.url` | string | none | An `https://` page for `host.webLogin`. The plugin's sign-in also depends on it (see [`login`](#the-plugin-module)). |

Unknown fields are ignored, so a newer manifest still loads in an older app. Any rule broken means the manifest is
rejected as a whole: the source lists only valid plugins.

## The plugin module

`main` is one ES module with **no imports** (bundle any dependencies and TypeScript into it). Its default export is an
object whose functions the app calls. Every function gets the [host API](#host-api) as its **last** argument; it's
also available as `globalThis.host`. Each function may be `async`. Its return value must be JSON-serialisable, and
`undefined` becomes `null`.

| Export | Called with | When | Timeout | Return |
|---|---|---|---|---|
| `request` | `(method, params, host)` | Every [wallet method](#wallet-methods) listed in `wallet.methods` | 20 s (`pay_invoice`: 60 s) | The method's result object |
| `poll` | `(host)` | Every `pollSec` (see [Notifications](#notifications-poll)) | 30 s | An array of notifications |
| `account` | `(host)` | Opening Home's wallet deck or Settings → Plugins, and after a sign-in or sign-out | 10 s | `{ signedIn: boolean, label?: string }` |
| `login` | `(host)` | **Sign in** (Settings → Plugins, the add-wallet choices, **Sign in to `<name>`** in Send) | 15 min | anything (ignored) |
| `logout` | `(host)` | **Sign out** in Settings → Plugins | 20 s | anything (ignored) |

All exports are optional. A call to a missing one fails with `NOT_IMPLEMENTED`, and the app reacts as follows:

- **`account` missing:** the app doesn't know the sign-in state. The plugin's card always shows, and Settings shows
  **Sign in**.
- **`logout` missing:** **Sign out** wipes everything the plugin stored instead (`store` and `secrets`).
- **`login` missing:** **Sign in** fails with a "couldn't sign in" message.

`account` should read local state only (no network): it runs often and its answer is cached on disk, so Home knows
who's signed in before the plugin loads. `label` is shown next to a check mark, typically an email.

A wallet plugin is offered as **Create `<name>` wallet** only when it has `permissions.webLogin` and `account` doesn't
say it's signed in. Its card leaves the wallet deck while `account` returns `signedIn: false`.

## Wallet methods

`request(method, params)` uses [NIP-47](https://github.com/nostr-protocol/nips/blob/master/47.md) names, params and
results. That's the same JSON a Nostr Wallet Connect wallet returns, so the app treats both kinds alike. Amounts are in
**millisats**, times in **unix seconds**. Methods not listed in `wallet.methods` fail with `NOT_IMPLEMENTED` before
reaching the plugin.

| Method | `params` | Result | The app uses it for |
|---|---|---|---|
| `get_balance` | `{}` | `{ balance }` | The card balance and Send's amount limit. |
| `make_invoice` | `{ amount, description? }` | a transaction | **Transfer amount** into this wallet. |
| `pay_invoice` | `{ invoice, amount? }` | `{ preimage, fees_paid? }` | **Pay from** in Send, and **Withdraw to active wallet**. Without it, both are hidden for this plugin. |
| `lookup_invoice` | `{ payment_hash }` | a transaction | Not called on plugin wallets yet (only on the active wallet). |
| `list_transactions` | `{ limit?, from?, until?, type? }` | `{ transactions: [transaction] }` | Not called on plugin wallets yet (Activity shows the active wallet). |

A transaction is:

```js
{
  type: 'incoming' | 'outgoing',
  state: 'pending' | 'settled' | 'expired' | 'failed',  // optional
  invoice, description, payment_hash, preimage,         // strings, optional except payment_hash
  amount, fees_paid,                                    // msat
  created_at, settled_at, expires_at                    // unix seconds
}
```

`pay_invoice` is only ever called after the user confirms the payment on the app's own Send screen. A plugin can't
start a payment, and must not add a confirmation of its own. If the provider needs a code, ask with
[`host.prompt`](#hostpromptoptions).

## Notifications: `poll()`

```js
async poll(host) {
  return [
    { notification_type: 'payment_received', notification: { /* transaction */ } },
    { notification_type: 'payment_sent',     notification: { /* transaction */ } },
  ];
}
```

Return the payments since the last call (keep a cursor in `host.store`). Other `notification_type` values are dropped.
`JsWallet.notifications()` calls `poll()` every `pollSec` and emits each payment.

> **Not scheduled today.** Plugin wallets are local-only, so they're never the app's active wallet. Only the active
> wallet's notifications reach the payment listener, so nothing calls `poll()` yet. Implement it anyway: it's part of
> API 1 and the listener will use it.

## Errors

Throw (or reject with) an object `{ code, message }`. The app reads `code`, so use one of these:

| Code | Raised by | Meaning / what the app does |
|---|---|---|
| `UNAUTHORIZED` | plugin | Session missing or expired. In Send, the app shows **Sign in to `<name>`**, then the user retries. |
| `INSUFFICIENT_BALANCE` | plugin | Not enough funds. |
| `RATE_LIMITED` | plugin | Too many requests. |
| `NOT_FOUND` | plugin | No such invoice or payment. |
| `PAYMENT_FAILED` | plugin | The payment was attempted and failed. |
| `OTHER` | plugin | Anything else the provider reported. |
| `NOT_IMPLEMENTED` | host or plugin | The method or export doesn't exist. |
| `INTERNAL` | host | A plain `Error`, a string, or an object without `code`. The message is `String(e.message ?? e)`. |
| `NETWORK` | host | `host.fetch` couldn't reach the server. The wallet is marked disconnected until a call succeeds. |
| `FORBIDDEN` | host | A host, secret or login origin the manifest doesn't grant. |
| `TIMEOUT` | host | The call ran past its [timeout](#runtime-limits). |
| `CANCELLED` | host | The user closed a web login or prompt. |

`message` is shown to the user in some places (a failed payment), so write it for people. It isn't logged.

## Host API

Every host function takes and returns plain JSON values. Async calls return promises; a failure rejects the promise
with `{ code, message }`.

### `host.fetch(url, init?)`

```ts
fetch(url: string, init?: { method?: string, headers?: Record<string, string>, body?: string })
  : Promise<{ status: number, headers: Record<string, string>, body: string }>
```

- `url` must be `https://` with its host in `permissions.hosts`. Debug builds also accept `http://`, so you can test
  against a LAN server. Anything else rejects with `FORBIDDEN`.
- `method` defaults to `GET`. `POST`, `PUT` and `PATCH` without a `body` send an empty one.
- `body` is sent as is. Encode JSON yourself, and set `Content-Type` in `headers` (it also sets the body's media type).
- You set every header, User-Agent included. The client sends no cookies and no LaWallet credentials.
- Redirects are **not** followed: a 3xx comes back as is, with `location` in `headers`.
- Every status resolves, 4xx and 5xx included. Only a connection failure rejects, with `NETWORK`.
- Response header names are lowercased (one value per name). `body` is text, cut at 2 MB.

### `host.store`

```ts
store.get(key: string): string | null
store.set(key: string, value: string | null): void   // null deletes
```

Synchronous, plain-text, and visible only to this plugin. Values are converted with `String(value)`, so store objects
as `JSON.stringify(…)`. Use it for cursors, caches and non-secret settings.

### `host.secrets`

```ts
secrets.get(key: string): string | null
secrets.set(key: string, value: string | null): void   // null deletes
```

Like `store`, but each value is encrypted with a key held in the Android Keystore. A `set` is on disk before it returns,
so a rotated refresh token survives a crash right after. Calling it without `permissions.secrets` throws `FORBIDDEN`.
Use it for tokens, device ids and anything else that grants access to the user's account.

### `host.webLogin(url, extractJs)`

```ts
webLogin(url: string, extractJs: string): Promise<string>
```

- **Opening the page:** opens `url` full screen in a WebView titled with the plugin's name and the site's host, and the
  user signs in there. `url`'s host must equal the host of `permissions.webLogin.url`, or the call rejects with
  `FORBIDDEN`.
- **Extracting the session:** about once a second, the app evaluates `extractJs` as an expression in the page. The first
  time it returns a **string**, the promise resolves with it and the page closes. Errors and other values count as
  "not yet", so an expression like `localStorage.TOKEN || null` waits through 2FA and new-device steps until the site
  stores the session.
- **Navigation:** the main frame may only load `https://` pages on the same site, meaning the login host or its
  registrable domain and subdomains. Other navigations are blocked. Frames inside the page (a captcha) may load from
  anywhere.
- **Closing:** cookies and web storage are wiped when the page closes. Return everything you need from `extractJs`.
- **Cancel:** rejects with `CANCELLED` when the user backs out.

Only one web login or prompt shows at a time, across all plugins: later calls wait their turn. The app never sees the
password.

### `host.prompt(options)`

```ts
prompt(options: {
  title?: string,          // default: the plugin's name
  message?: string,
  input?: 'code' | 'text', // default 'text'
  error?: string,          // shown under the field, e.g. "Wrong code, try again"
  actions?: ['resend'],
}): Promise<{ value: string } | { action: 'resend' }>
```

Shows a dialog over whatever screen is open, with the plugin's icon and "Requested by `<name>`". Use it for something
the plugin needs to go on, like an emailed or 2FA code before a payment.

- **`input: 'code'`:** gives a number keyboard and keeps only letters and digits, so a pasted `12 34-56` becomes
  `123456`.
- **`actions: ['resend']`:** adds a **Resend** button, answered as `{ action: 'resend' }`. Send a new code and prompt
  again.
- **Text length:** each text field is cut at 300 characters.
- **Cancel:** rejects with `CANCELLED`.

While a prompt or web login is open, the timeout of the call that opened it is paused. A payment waiting for an emailed
code doesn't time out under the user.

A typical code loop:

```js
for (let error = null, i = 0; i < 5; i++) {
  const r = await host.prompt({ title: 'Enter your code', input: 'code', error, actions: ['resend'] });
  if (r.action === 'resend') { await sendCode(); continue; }
  const res = await confirm(r.value);
  if (res.ok) return res;
  error = 'That code didn\'t work';
}
throw { code: 'UNAUTHORIZED', message: 'Too many wrong codes' };
```

### `host.uuid()`, `host.now()`

`uuid()` returns a random UUID string. `now()` returns `Date.now()` in milliseconds.

## Runtime limits

| | |
|---|---|
| Engine | QuickJS (modern ES: `async`/`await`, classes, `BigInt`). No `std`/`os` modules, no `import` of other files. |
| Globals | The language built-ins plus `host`. No `setTimeout`, `fetch`, `console`, `XMLHttpRequest`, `WebSocket` or file access. |
| Heap | 32 MB per plugin. |
| Stack | 512 KB. |
| Thread | One per plugin; calls to the same plugin run one after another, interleaving at `await`. |
| Bundle | 2 MB. Icon: 512 KB. |
| Timeouts | `request` 20 s (`pay_invoice` 60 s), `poll` 30 s, `account` 10 s, `logout` 20 s, `login` 15 min. Paused while a prompt or web login is open. |
| Stuck plugin | A call that times out while the plugin's thread is still busy (a busy loop) marks it dead. The next use starts a fresh context; the stuck thread is abandoned. A call merely waiting on the network isn't stuck. |

## App side (Kotlin)

The integration surface inside `io.lawallet.wallet`, for app code that hooks plugins into a screen. Plugins never see any
of this.

### `core/wallet/Wallet.kt`

```kotlin
interface Wallet {
    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = defaultTimeout(method)): JsonElement
    fun notifications(): Flow<NwcNotification>
    val connected: StateFlow<Boolean>
    val lastError: StateFlow<String?>
    // typed helpers on top of request():
    suspend fun getBalanceMsat(): Long
    suspend fun payInvoice(invoice: String, amountMsat: Long? = null): PayResult
    suspend fun makeInvoice(amountMsat: Long, description: String?): NwcTransaction
    suspend fun lookupInvoice(paymentHash: String): NwcTransaction
    suspend fun listTransactions(limit: Int? = null, from: Long? = null, until: Long? = null, type: String? = null): List<NwcTransaction>
}
```

`NwcClient` and `JsWallet` both implement it. Errors are `NwcException(code, message)` with the codes from
[Errors](#errors). A native plugin implements `Wallet` too, under the same id (see [plugins.md](plugins.md#native-plugins)).

### `PluginRegistry` (`AppContainer.plugins`)

| Member | |
|---|---|
| `installed: StateFlow<List<InstalledPlugin>>` | Installed plugins with `candidate` (manifest, source, pinned URLs, ref), `sha256`, `enabled`. |
| `accounts: StateFlow<Map<String, PluginAccount>>` | The last `account()` answer per id, persisted. A missing entry means unknown. |
| `get(id)` | One installed plugin, or null. |
| `wallet(id): JsWallet?` | The plugin's wallet if it's installed, enabled, a wallet and its bundle hash matches. Returns the same instance until it's reloaded. |
| `install(candidate)` | Download, hash, store, keep or wipe its data (see [Lifecycle](#lifecycle)). Throws `PluginSourceException`. |
| `remove(id)`, `setEnabled(id, on)` | Uninstall and wipe; enable or disable (disabling unloads the context). |
| `login(id)`, `logout(id)` | Run the export; `true` on success. Both refresh `accounts`. |
| `refreshAccount(id)` | Ask `account()` again. |
| `icon(id): File?` | The stored icon. |
| `resolver: PluginResolver` | `resolve(PluginSource)` for Add Plugin; `resolveDev(baseUrl)` for the local dev server. |
| `loginRequest`, `promptRequest` | The web login or prompt waiting on the user. `AppNav` shows them over every screen; nothing else should. |

### UI conventions (`ui/screens/Plugins.kt`, `Send.kt`)

- A wallet plugin appears in the Lightning deck as a `RemoteWallet` with `type = "PLUGIN"`, `id = "plugin:<id>"` and
  `provider = <id>`. Use `withPlugins(serverWallets, installed, accounts)` to build the list, and
  `pluginOf(walletId)` / `RemoteWallet.isPlugin` to tell the two kinds apart.
- `walletById(c, id)` returns a `Wallet` for either kind (a plugin or a LaWallet NWC wallet). `sourceBalance(c, id)` is
  its balance in sats.
- A plugin wallet is **never** the primary or active wallet, since LaWallet routes Lightning addresses over NWC. Code that
  sets the active wallet must skip `isPlugin` cards.
- Gate spending UI on `"pay_invoice" in manifest.wallet.methods`, the same check **Pay from** and **Withdraw** use.
- `signIn(c, context, manifest)` runs `login()` with the success or failure toast. Use it for any new Sign in entry
  point.
- Record plugin activity with the plugin id, method, outcome and duration only, as `JsWallet` does. Never log params,
  results, URLs or anything a plugin returns (privacy rule in [AGENTS.md](../AGENTS.md)).
