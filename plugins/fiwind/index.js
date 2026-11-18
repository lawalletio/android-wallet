// Fiwind wallet plugin for LaWallet: NIP-47 in, Fiwind's private (unofficial) API out.
// Session logic is ported from github.com/agustinkassis/gorilla-api (src/fiwind.ts).
// Request/response shapes marked UNVERIFIED were read from panel.fiwind.io's JS bundle or guessed;
// none of them has been exercised against the real API. See README.md.
//
// Plain ES2020, no imports. The only way out is `globalThis.host` (fetch, store, secrets, webLogin, now).

const API = "https://api.fiwind.io/v1.0";
const PANEL = "https://panel.fiwind.io";
// Fiwind's WAF rejects non-browser clients (same headers gorilla-api uses).
const BROWSER = {
  "user-agent":
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
  origin: PANEL,
  referer: PANEL + "/",
  accept: "application/json",
  "content-type": "application/json",
};
const INVOICES = "/transactions/deposit-requests/crypto/BTC/invoices";
const WITHDRAW = "/transactions/withdraw-requests";
const MSAT_PER_BTC = 1e11;
const MAX_PER_HOUR = 600; // ponytail: per-device hourly cap; gorilla uses 200 for a whole server
const EXPIRED_GRACE_SEC = 600; // keep polling an expired invoice this long, in case it was paid at the edge

// Runs inside panel.fiwind.io after the user logs in there. Returns null until the session exists, then hands it
// over and forgets it locally without logging out (logging out would kill the refresh token).
const EXTRACT_JS =
  "(function(){var r=localStorage.getItem('REFRESH_TOKEN'),a=localStorage.getItem('JWT_TOKEN'),d=localStorage.getItem('deviceId');" +
  "if(!r||!a||!d)return null;localStorage.clear();return JSON.stringify({refreshToken:r,accessToken:a,deviceId:d});})()";

const host = () => globalThis.host;
const nowSec = () => Math.floor(host().now() / 1000);
const fail = (code, message, extra) => {
  throw Object.assign({ code, message }, extra);
};
/** Fiwind puts a user-facing (Spanish) message in `viewMessage`. */
const why = (b, status) => (b && (b.viewMessage || b.message || b.errorMessage)) || `HTTP ${status}`;
const ok = (r, code = "INTERNAL") => (r.ok ? r : fail(r.status >= 500 ? "INTERNAL" : code, why(r.body, r.status)));
const toBtc = (msat) => Number((Math.floor(msat / 1000) / 1e8).toFixed(8)); // Fiwind works in whole sats
const toMsat = (btc) => Math.round(Number(btc || 0) * MSAT_PER_BTC);

async function getJson(key, fallback) {
  const v = await host().store.get(key);
  return v ? JSON.parse(v) : fallback;
}
const setJson = (key, v) => host().store.set(key, JSON.stringify(v));

// ---- encoding (no atob/TextDecoder in the sandbox) ----

const B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
const BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

function regroup(values, from) {
  const out = [];
  let acc = 0;
  let bits = 0;
  for (const v of values) {
    acc = ((acc << from) | v) & 0xffff;
    bits += from;
    if (bits >= 8) {
      bits -= 8;
      out.push((acc >> bits) & 255);
    }
  }
  return out; // leftover padding bits are dropped
}
const hex = (bytes) => bytes.map((b) => (b < 16 ? "0" : "") + b.toString(16)).join("");
function utf8(bytes) {
  try {
    return decodeURIComponent(bytes.map((b) => "%" + (b < 16 ? "0" : "") + b.toString(16)).join(""));
  } catch (e) {
    return String.fromCharCode(...bytes);
  }
}
const bare = (t) => String(t || "").replace(/^Bearer\s+/i, "");
function claims(token) {
  try {
    const part = bare(token).split(".")[1].replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    return JSON.parse(utf8(regroup([...part].map((c) => B64URL.indexOf(c)), 6)));
  } catch (e) {
    return {};
  }
}

/** The fields of a BOLT11 invoice we need. Doesn't verify the checksum or signature: Fiwind does. */
function decodeBolt11(invoice) {
  const s = String(invoice || "").trim().toLowerCase().replace(/^lightning:/, "");
  const sep = s.lastIndexOf("1");
  const words = [...s.slice(sep + 1, -6)].map((c) => BECH32.indexOf(c));
  if (!s.startsWith("ln") || sep < 0 || words.length < 7 + 104 || words.includes(-1))
    fail("OTHER", "Invalid Lightning invoice");
  const m = s.slice(0, sep).match(/(\d+)([munp]?)$/);
  const out = {
    amount: m ? Math.round(Number(m[1]) * { "": 1e11, m: 1e8, u: 1e5, n: 100, p: 0.1 }[m[2]]) : 0,
    timestamp: words.slice(0, 7).reduce((a, w) => a * 32 + w, 0),
    expiry: 3600,
    payment_hash: "",
    description: "",
    description_hash: "",
  };
  for (let i = 7, end = words.length - 104; i + 3 <= end; ) {
    const tag = words[i];
    const len = words[i + 1] * 32 + words[i + 2];
    const data = words.slice(i + 3, i + 3 + len);
    i += 3 + len;
    if (tag === 1) out.payment_hash = hex(regroup(data, 5));
    else if (tag === 13) out.description = utf8(regroup(data, 5));
    else if (tag === 23) out.description_hash = hex(regroup(data, 5));
    else if (tag === 6) out.expiry = data.reduce((a, w) => a * 32 + w, 0);
  }
  if (out.payment_hash.length !== 64) fail("OTHER", "Invalid Lightning invoice");
  return out;
}

// ---- transport: serial queue + hourly cap + 429 back-off ----

let queue = Promise.resolve();
function throttled(fn) {
  const run = queue.then(async () => {
    const h = host();
    if (Number(await h.store.get("fw:paused")) > h.now()) fail("RATE_LIMITED", "Fiwind is limiting requests, try again later");
    const hour = Math.floor(h.now() / 3600000);
    const [at, n] = String((await h.store.get("fw:hour")) || "0:0").split(":").map(Number);
    const count = at === hour ? n + 1 : 1;
    if (count > MAX_PER_HOUR) fail("RATE_LIMITED", "Too many Fiwind requests this hour, try again later");
    await h.store.set("fw:hour", `${hour}:${count}`);
    return fn();
  });
  queue = run.catch(() => {});
  return run;
}

async function call(path, init = {}) {
  let res;
  try {
    res = await host().fetch(API + path, {
      method: init.method || "GET",
      headers: Object.assign({}, BROWSER, init.headers),
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
    });
  } catch (e) {
    fail("INTERNAL", "Can't reach Fiwind", { network: true });
  }
  if (res.status === 429) {
    const h = res.headers || {};
    const after = Number(h["retry-after"] || h["Retry-After"]) || 60;
    await host().store.set("fw:paused", String(host().now() + after * 1000));
  }
  let body = {};
  try {
    body = JSON.parse(res.body || "{}");
  } catch (e) {}
  return { status: res.status, ok: res.status >= 200 && res.status < 300, body };
}

// ---- session: rotating refresh token, one refresh in flight ----

async function session() {
  const raw = await host().secrets.get("session");
  return raw ? JSON.parse(raw) : null;
}
const saveSession = (s) => host().secrets.set("session", JSON.stringify(s));

async function validAccess(rejected) {
  const s = await session();
  const t = s && !s.invalid && s.accessToken;
  return t && t !== rejected && (claims(t).exp || 0) * 1000 - 30000 > host().now() ? t : null;
}

async function refresh() {
  const s = await session();
  if (!s) fail("UNAUTHORIZED", "Fiwind isn't connected. Log in again.");
  if (s.invalid) fail("UNAUTHORIZED", "Your Fiwind session expired. Log in again.");
  const r = await throttled(() =>
    call("/auth/refresh-token", {
      method: "POST",
      headers: { "x-user-id": s.userId || "0", "x-device-id": s.deviceId },
      body: { refreshToken: s.refreshToken },
    }),
  );
  if (!r.ok || !r.body.accessToken) {
    if (r.status === 400 || r.status === 401) {
      await saveSession(Object.assign({}, s, { invalid: `refresh ${r.status}` }));
      fail("UNAUTHORIZED", "Your Fiwind session expired. Log in again.");
    }
    fail("INTERNAL", `Fiwind session refresh failed: ${why(r.body, r.status)}`);
  }
  // The old refresh token is spent: persist the rotated one before anything else can fail.
  const accessToken = bare(r.body.accessToken);
  await saveSession(
    Object.assign({}, s, {
      refreshToken: r.body.refreshToken || s.refreshToken,
      accessToken,
      userId: String(claims(accessToken).sub || s.userId),
    }),
  );
  return accessToken;
}

let refreshing = null;
/** A valid access token. `rejected` = a token Fiwind just refused, so it must not be reused. */
async function getAccess(rejected) {
  const t = await validAccess(rejected);
  if (t) return t;
  if (!refreshing) {
    refreshing = (async () => (await validAccess(rejected)) || refresh())().finally(() => {
      refreshing = null;
    });
  }
  return refreshing;
}

/** Authenticated call. A 401 means Fiwind didn't act on it, so refreshing and retrying once is safe. */
async function authed(path, init = {}) {
  let rejected;
  for (let attempt = 0; ; attempt++) {
    const token = await getAccess(rejected);
    const s = await session();
    const r = await throttled(() =>
      call(path, Object.assign({}, init, {
        headers: Object.assign({}, init.headers, { authorization: `Bearer ${token}`, "x-device-id": s.deviceId }),
      })),
    );
    if (r.status === 401 && attempt === 0) {
      rejected = token;
      continue;
    }
    if (r.status === 401) fail("UNAUTHORIZED", why(r.body, r.status));
    if (r.status === 429) fail("RATE_LIMITED", "Fiwind is limiting requests, try again later");
    return r;
  }
}

// ---- invoices we issued (store: "inv:<hash>" record, "pending" list of hashes to poll) ----

const publicTx = (rec) => {
  const tx = Object.assign({}, rec);
  delete tx.fiwindId;
  return tx;
};

async function refreshInvoice(rec) {
  if (rec.state === "settled") return rec;
  const r = await authed(`${INVOICES}/${encodeURIComponent(rec.fiwindId)}`);
  if (r.status === 404) fail("NOT_FOUND", why(r.body, r.status));
  ok(r);
  if (r.body.status === "paid") {
    rec.state = "settled";
    rec.settled_at = nowSec(); // Fiwind doesn't say when; first time we saw it paid
    if (!rec.amount) rec.amount = toMsat(r.body.amount); // UNVERIFIED: amount (BTC) on amountless invoices
  } else if (rec.expires_at && nowSec() > rec.expires_at) {
    rec.state = "expired"; // UNVERIFIED: only "paid" is known; other statuses are treated as not paid yet
  }
  await setJson("inv:" + rec.payment_hash, rec);
  return rec;
}

// ---- NIP-47 methods ----

const methods = {
  // UNVERIFIED (shape read from the panel bundle): GET /transactions/balance?base=ARS
  //   → { currencies: [{ currency: "BTC", available, availableBase, … }] }, amounts in BTC.
  async get_balance() {
    const r = ok(await authed("/transactions/balance?base=ARS"));
    const btc = (r.body.currencies || []).find((c) => c.currency === "BTC");
    return { balance: toMsat(btc && btc.available) };
  },

  async make_invoice(p) {
    const body = p.amount ? { amount: toBtc(p.amount) } : {}; // omitted = amountless
    if (p.amount && !(body.amount > 0)) fail("OTHER", "The amount must be at least 1 sat");
    const r = ok(await authed(INVOICES, { method: "POST", body }));
    if (!r.body.invoice || r.body.id == null) fail("INTERNAL", "Fiwind didn't return an invoice");
    const d = decodeBolt11(r.body.invoice);
    const rec = {
      type: "incoming",
      invoice: r.body.invoice,
      description: d.description || p.description || "", // Fiwind doesn't take a description
      description_hash: d.description_hash,
      preimage: "",
      payment_hash: d.payment_hash,
      amount: d.amount,
      fees_paid: 0,
      created_at: d.timestamp || nowSec(),
      expires_at: r.body.expiration ? Math.floor(Date.parse(r.body.expiration) / 1000) : d.timestamp + d.expiry,
      settled_at: null,
      state: "pending",
      fiwindId: String(r.body.id),
    };
    await setJson("inv:" + d.payment_hash, rec);
    const pending = await getJson("pending", []);
    pending.push(d.payment_hash);
    await setJson("pending", pending);
    return publicTx(rec);
  },

  async lookup_invoice(p) {
    const hash = p.payment_hash || decodeBolt11(p.invoice).payment_hash;
    const paid = await getJson("pay:" + hash);
    if (paid) return paid;
    const rec = await getJson("inv:" + hash);
    if (!rec) fail("NOT_FOUND", "Invoice not found");
    return publicTx(await refreshInvoice(rec));
  },

  // decode → preview → confirm, as panel.fiwind.io does it (endpoints and request bodies read from its bundle).
  async pay_invoice(p) {
    const d = decodeBolt11(p.invoice);
    const prior = await getJson("pay:" + d.payment_hash);
    if (prior && prior.state === "settled") return { preimage: prior.preimage, fees_paid: prior.fees_paid };
    if (prior && prior.state === "pending")
      fail("PAYMENT_FAILED", "This invoice may already be paid. Check your Fiwind movements before trying again.");
    const msat = d.amount || Number(p.amount) || 0;
    if (!msat) fail("OTHER", "This invoice has no amount");

    // Returns { amount } in BTC, null/0 for an amountless invoice.
    const dec = ok(await authed(`${WITHDRAW}/crypto/BTC/invoices/decode`, { method: "POST", body: { invoice: p.invoice } }), "OTHER");
    const btc = Number(dec.body.amount) || toBtc(msat);
    // Returns { id, net, address, amount (debited), outputAmount, networkFee, security, … }.
    const pv = ok(
      await authed(`${WITHDRAW}/crypto/preview`, {
        method: "POST",
        body: { currency: "BTC", toAddress: p.invoice, net: "LN", amount: btc },
      }),
      "PAYMENT_FAILED",
    );
    // The panel asks for an emailed code (OTP) or a 2FA code here; the plugin host has no way to ask the user yet.
    if (pv.body.security)
      fail("RESTRICTED", `Fiwind asks for a security code (${pv.body.security}) for this payment. Pay it from the Fiwind app.`);
    if (pv.body.id == null) fail("INTERNAL", "Fiwind didn't return a withdrawal to confirm");

    const rec = {
      type: "outgoing",
      invoice: p.invoice,
      description: d.description,
      description_hash: d.description_hash,
      preimage: "", // Fiwind doesn't expose one
      payment_hash: d.payment_hash,
      amount: msat,
      fees_paid: toMsat(pv.body.networkFee),
      created_at: nowSec(),
      expires_at: d.timestamp + d.expiry,
      settled_at: null,
      state: "pending",
    };
    // Persisted before confirming: from here on this invoice is never paid again automatically.
    await setJson("pay:" + d.payment_hash, rec);
    let r;
    try {
      r = await authed(`${WITHDRAW}/crypto/${encodeURIComponent(pv.body.id)}/confirm`, { method: "POST" });
    } catch (e) {
      if (e.network) fail("PAYMENT_FAILED", "Lost contact with Fiwind while paying. Check your Fiwind movements before trying again.");
      rec.state = "failed"; // refused before reaching Fiwind (rate limit, expired session): nothing was sent
      await setJson("pay:" + d.payment_hash, rec);
      throw e;
    }
    if (r.status >= 500) fail("PAYMENT_FAILED", "Fiwind failed while paying. Check your Fiwind movements before trying again.");
    // Returns { amount, date, state }: the panel shows APPROVED as done and PROCESSING/PENDING as in progress.
    const state = r.ok && String(r.body.state || "APPROVED").toUpperCase(); // UNVERIFIED: state for LN withdrawals
    if (!r.ok || !["APPROVED", "PROCESSING", "PENDING"].includes(state)) {
      rec.state = "failed";
      await setJson("pay:" + d.payment_hash, rec);
      fail("PAYMENT_FAILED", r.ok ? `Fiwind rejected the payment (${state})` : why(r.body, r.status));
    }
    if (state === "APPROVED") {
      rec.state = "settled";
      rec.settled_at = nowSec();
    }
    await setJson("pay:" + d.payment_hash, rec);
    return { preimage: rec.preimage, fees_paid: rec.fees_paid };
  },

  // UNVERIFIED (shape read from the panel bundle): GET /transactions/movement-details?offset&limit&from&to
  //   &currencies[]&types[] → [{ id, date (ISO), type, currency, amount (BTC), side: "D"|"W", description, label,
  //   data: { net, address (the bolt11 for LN withdrawals), hash, networkFee, state } }]
  async list_transactions(p) {
    const q = [`offset=${Number(p.offset) || 0}`, `limit=${Math.min(Number(p.limit) || 50, 100)}`, "currencies%5B%5D=BTC"];
    if (p.type !== "outgoing") q.push("types%5B%5D=deposit");
    if (p.type !== "incoming") q.push("types%5B%5D=withdrawal");
    if (p.from) q.push("from=" + encodeURIComponent(new Date(p.from * 1000).toISOString()));
    if (p.until) q.push("to=" + encodeURIComponent(new Date(p.until * 1000).toISOString())); // UNVERIFIED: the panel sends a date
    const r = ok(await authed("/transactions/movement-details?" + q.join("&")));
    return { transactions: (Array.isArray(r.body) ? r.body : []).map(movementTx) };
  },
};

const MOVEMENT_STATE = { APPROVED: "settled", PENDING: "pending", PROCESSING: "pending", REJECTED: "failed", CANCELED: "failed", CANCELLED: "failed", ERROR: "failed" };

function movementTx(m) {
  const data = m.data || {};
  const invoice = data.net === "LN" && /^ln/i.test(data.address || "") ? data.address : "";
  let hash = "";
  try {
    if (invoice) hash = decodeBolt11(invoice).payment_hash;
  } catch (e) {}
  if (!hash && /^[0-9a-f]{64}$/i.test(data.hash || "")) hash = data.hash.toLowerCase(); // UNVERIFIED: what `hash` holds on LN
  const t = Math.floor(Date.parse(m.date) / 1000) || 0;
  const state = MOVEMENT_STATE[String(data.state || "APPROVED").toUpperCase()] || "pending";
  return {
    type: m.side === "W" || m.type === "withdrawal" ? "outgoing" : "incoming",
    invoice,
    description: m.description || m.label || "",
    description_hash: "",
    preimage: "",
    payment_hash: hash,
    amount: toMsat(Math.abs(Number(m.amount))),
    fees_paid: toMsat(data.networkFee),
    created_at: t,
    settled_at: state === "settled" ? t : null,
    state,
  };
}

let polling = false;

export default {
  async request(method, params) {
    const fn = methods[method];
    if (!fn) fail("NOT_IMPLEMENTED", `Fiwind doesn't support ${method}`);
    return fn(params || {});
  },

  /** Checks the invoices we issued; emits payment_received once per invoice that got paid. */
  async poll() {
    if (polling || !(await session())) return [];
    polling = true;
    const out = [];
    try {
      const pending = await getJson("pending", []);
      const keep = [];
      for (let i = 0; i < pending.length; i++) {
        const rec = await getJson("inv:" + pending[i]);
        if (!rec) continue;
        try {
          await refreshInvoice(rec);
        } catch (e) {
          keep.push(...pending.slice(i)); // offline, rate limited or logged out: try the rest next tick
          break;
        }
        // Leaving `pending` is what makes this fire once.
        if (rec.state === "settled") {
          out.push({ notification_type: "payment_received", notification: publicTx(rec) });
        } else if (rec.state === "pending" || (rec.state === "expired" && nowSec() < rec.expires_at + EXPIRED_GRACE_SEC)) {
          keep.push(pending[i]);
        }
      }
      await setJson("pending", keep);
    } finally {
      polling = false;
    }
    return out;
  },

  /** Login happens on Fiwind's own page (it needs reCAPTCHA); we only take the session it leaves in localStorage. */
  async login(h) {
    const raw = await (h || host()).webLogin(PANEL, EXTRACT_JS);
    if (!raw) fail("UNAUTHORIZED", "Fiwind login was cancelled");
    let s = {};
    try {
      s = JSON.parse(raw);
    } catch (e) {}
    const accessToken = bare(s.accessToken);
    const userId = claims(accessToken).sub; // refresh needs it as x-user-id
    if (!s.refreshToken || !s.deviceId || !userId) fail("UNAUTHORIZED", "Couldn't read the Fiwind session. Log in again.");
    await saveSession({ refreshToken: s.refreshToken, deviceId: s.deviceId, accessToken, userId: String(userId) });
    await getAccess(); // refreshes now if the token is already stale, so a dead session fails here, not later
    return { connected: true };
  },
};
