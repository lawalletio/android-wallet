# Fiwind plugin

This plugin shows your [Fiwind](https://fiwind.io) BTC balance in LaWallet as a Lightning wallet. LaWallet's plugin
host calls it with NIP-47 methods (`request`, `poll`, `login`), and the plugin answers them with Fiwind's API.

> **Risk: this uses Fiwind's private, unofficial API.** Fiwind doesn't publish it. Its endpoints were read from Fiwind's
> own web client (panel.fiwind.io) and from [gorilla-api](https://github.com/agustinkassis/gorilla-api), so Fiwind can
> change them at any time. Its WAF also rejects non-browser clients, and using the API this way may conflict with
> Fiwind's terms. Ask Fiwind for official API access before shipping this outside debug builds.

## How it works

- **Login:** Fiwind's login needs reCAPTCHA, so the user logs in on Fiwind's own page inside `host.webLogin`. After that,
  a short script reads `REFRESH_TOKEN`, `JWT_TOKEN` and `deviceId` from that page's `localStorage` and then clears it,
  which drops the browser's copy without logging out. LaWallet never sees the password. The session is kept in
  `host.secrets`.
- **Session:** the session logic is ported from gorilla-api `src/fiwind.ts`:
  - Every request sends `Authorization: Bearer`, `x-device-id`, a desktop browser `User-Agent`, and `Origin`/`Referer`
    set to `https://panel.fiwind.io`.
  - On a 401 the plugin refreshes once via `POST /auth/refresh-token` (with `x-user-id` = the JWT `sub`) and retries.
  - The refresh token rotates, so the new one is saved before anything else happens, and only one refresh can be in flight.
  - When a refresh fails with 400 or 401, the session is marked invalid and the user has to log in again.
- **Throttle:** requests run one at a time. There's a cap of 600 requests per hour. After a 429, the plugin pauses for the
  time in `Retry-After`.
- **Errors:** user-facing errors use Fiwind's `viewMessage`, which is in Spanish, and are thrown as NIP-47
  `{code, message}`.

| NIP-47 | Fiwind | Status |
|---|---|---|
| `make_invoice` | `POST /transactions/deposit-requests/crypto/BTC/invoices` `{amount}` (BTC; omitted = amountless) → `{id, invoice, expiration}` | Verified in gorilla-api (used in production) |
| `lookup_invoice` | `GET …/invoices/:id` → `{status: "paid" \| …}`. The plugin stores the `id` ↔ `payment_hash` mapping in `host.store` | Verified in gorilla-api |
| `poll()` | The same lookup for each pending invoice. Emits `payment_received` once, then drops the invoice | Same as above |
| `get_balance` | `GET /transactions/balance?base=ARS` → `{currencies: [{currency, available}]}` | **UNVERIFIED** (read from the panel bundle) |
| `pay_invoice` | `POST withdraw-requests/crypto/BTC/invoices/decode` `{invoice}` → `POST …/crypto/preview` `{currency:"BTC", toAddress, net:"LN", amount}` → `POST …/crypto/:id/confirm` | **UNVERIFIED** (requests read from the panel bundle) |
| `list_transactions` | `GET /transactions/movement-details?offset&limit&from&to&currencies[]=BTC&types[]=deposit\|withdrawal` | **UNVERIFIED** (read from the panel bundle) |

## Still to check against a real account

Search `UNVERIFIED` in `index.js` for each of these.

1. **`pay_invoice` security codes.** The withdraw preview returns `security: "OTP" | "2FA"` when Fiwind wants a code
   before confirming. The panel then emails an OTP (`authService.sendOtp()`) or asks for the 2FA code, and sends it as
   `X-Auth-Type`/`X-Auth-Token` headers on `confirm`. The plugin host has no way to ask the user for input yet, so
   `pay_invoice` fails with `RESTRICTED` before confirming. Nothing is spent. Find out how often Lightning withdrawals ask
   for a code. If they always do, the host needs an input API (for example `host.prompt`) before `pay_invoice` is usable.
2. **`pay_invoice` result.** `confirm` returns `{amount, date, state}`. The plugin treats `APPROVED` as settled, treats
   `PROCESSING` and `PENDING` as accepted but still pending, and treats any other state as failed. It hasn't been
   checked whether Lightning withdrawals ever come back `PROCESSING`. Fiwind exposes no preimage, so `preimage` is
   always `""`.
3. **`pay_invoice` units.** `decode` returns `{amount}` in BTC. The plugin assumes that, plus `preview.networkFee` in
   BTC, which it reports as `fees_paid`.
4. **`get_balance`:** whether `available` is in BTC (assumed), and whether `base` is required.
5. **`list_transactions`:**
   - The shape of a movement is taken from the panel: `{id, date, type, currency, amount, side: "D"|"W", description,
     label, data: {net, address, hash, networkFee, state}}`.
   - For LN withdrawals, `data.address` is the bolt11, so `payment_hash` comes from it.
   - For LN deposits, it's unknown what `data.hash` holds. It's used as `payment_hash` only when it's 64 hex characters.
   - The panel sends `to` as a date, not an ISO timestamp.
   - The state values (`APPROVED`, `PENDING`, `PROCESSING`, `REJECTED`) only cover deposits.
6. **Invoice status:** only `"paid"` is known. Any other status counts as unpaid, and after `expiration` the invoice
   counts as expired.
7. **Amountless invoices:** the plugin assumes that a paid lookup returns `amount` in BTC.

## Known limits

- `make_invoice` ignores `description`, `description_hash` and `expiry`, because Fiwind doesn't take them. The invoice's
  own description is reported.
- The plugin only notices payments to invoices it created. Deposits from elsewhere show up in `list_transactions` but
  don't trigger `payment_received`. `payment_sent` isn't emitted, because the host already knows when it pays.
- Amounts are rounded down to whole sats, since Fiwind works in BTC with 8 decimals.
- Because the refresh token rotates, only one holder can keep the session. Logging in here ends any other copy of the same
  Fiwind session, such as gorilla-api.

## Test

```bash
node plugins/fiwind/test.mjs
```

The test stubs `globalThis.host` with a fake Fiwind API, so it never calls the real one. It checks:
- login through the extract script
- one rotated refresh for concurrent 401s
- `make_invoice` → `lookup_invoice`
- `poll` emitting `payment_received` exactly once
- `pay_invoice` going decode → preview → confirm, and not paying twice
- a withdrawal that needs a security code being refused before confirm
- a dead refresh token ending the session
