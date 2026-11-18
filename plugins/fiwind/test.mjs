// Self-check for the Fiwind plugin against a fake Fiwind API. Run: node plugins/fiwind/test.mjs
// Never talks to the real Fiwind API.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
const b64url = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
const jwt = (sub, exp) => `${b64url({ alg: "HS256" })}.${b64url({ sub, exp })}.sig`;
const toWords = (bytes) => {
  const out = [];
  let acc = 0, bits = 0;
  for (const b of bytes) {
    acc = (acc << 8) | b;
    bits += 8;
    while (bits >= 5) out.push((acc >> (bits -= 5)) & 31);
  }
  if (bits) out.push((acc << (5 - bits)) & 31);
  return out;
};
const tag = (t, words) => [t, words.length >> 5, words.length & 31, ...words];
/** A structurally valid BOLT11 (fake signature and checksum). */
function bolt11(hashHex, sats, description = "test") {
  const ts = 1_800_000_000;
  const words = [
    ...Array.from({ length: 7 }, (_, i) => Math.floor(ts / 32 ** (6 - i)) % 32),
    ...tag(1, toWords(Buffer.from(hashHex, "hex"))),
    ...tag(13, toWords(Buffer.from(description))),
    ...new Array(104).fill(0),
  ];
  return `lnbc${sats ? sats * 10 + "n" : ""}1` + words.map((w) => CHARSET[w]).join("") + "qqqqqq";
}

// ---- fake Fiwind ----
const fw = {
  access: "server-only", // the token the server accepts right now
  refresh: "R1",
  refreshCalls: [],
  invoices: new Map(),
  calls: [],
  security: null,
};
let clock = 1_800_000_000_000;
const json = (status, body) => ({ status, headers: {}, body: JSON.stringify(body) });

async function fakeFetch(url, init) {
  const u = new URL(url);
  assert.equal(u.origin, "https://api.fiwind.io");
  assert.equal(init.headers.origin, "https://panel.fiwind.io");
  assert.match(init.headers["user-agent"], /Mozilla/);
  const path = u.pathname.replace("/v1.0", "");
  const body = init.body ? JSON.parse(init.body) : undefined;
  fw.calls.push(`${init.method} ${path}`);
  await new Promise((r) => setTimeout(r, 1)); // let concurrent requests interleave

  if (path === "/auth/refresh-token") {
    fw.refreshCalls.push({ userId: init.headers["x-user-id"], token: body.refreshToken });
    if (body.refreshToken !== fw.refresh) return json(401, { viewMessage: "Sesión vencida" });
    const n = fw.refreshCalls.length + 1;
    fw.access = jwt("42", clock / 1000 + 3600);
    fw.refresh = `R${n}`;
    return json(200, { accessToken: fw.access, refreshToken: fw.refresh });
  }
  if (init.headers.authorization !== `Bearer ${fw.access}`) return json(401, { code: "ACCESS_TOKEN_ERROR" });
  assert.equal(init.headers["x-device-id"], "dev-1");

  if (path === "/transactions/balance") return json(200, { currencies: [{ currency: "ARS", available: 10 }, { currency: "BTC", available: 0.0005 }] });
  if (path === "/transactions/deposit-requests/crypto/BTC/invoices" && init.method === "POST") {
    const id = String(fw.invoices.size + 1);
    const hash = id.padStart(64, "a");
    fw.invoices.set(id, { amount: body.amount, paid: false });
    return json(200, { id, invoice: bolt11(hash, Math.round(body.amount * 1e8)), expiration: new Date(clock + 600_000).toISOString() });
  }
  const im = path.match(/^\/transactions\/deposit-requests\/crypto\/BTC\/invoices\/(\w+)$/);
  if (im) {
    const inv = fw.invoices.get(im[1]);
    return inv ? json(200, { id: im[1], status: inv.paid ? "paid" : "pending", amount: inv.amount }) : json(404, { viewMessage: "No existe" });
  }
  if (path === "/transactions/withdraw-requests/crypto/BTC/invoices/decode") return json(200, { amount: 0.00001 });
  if (path === "/transactions/withdraw-requests/crypto/preview") {
    assert.deepEqual(body, { currency: "BTC", toAddress: body.toAddress, net: "LN", amount: 0.00001 });
    return json(200, { id: 77, net: "LN", address: body.toAddress, amount: 0.00001, outputAmount: 0.00001, networkFee: 0.00000002, security: fw.security });
  }
  if (path === "/transactions/withdraw-requests/crypto/77/confirm") return json(200, { amount: 0.00001, date: new Date(clock).toISOString(), state: "APPROVED" });
  return json(404, { message: `no route ${path}` });
}

const store = new Map();
const secrets = new Map();
const localStorage = new Map(Object.entries({ REFRESH_TOKEN: "R1", JWT_TOKEN: jwt("42", clock / 1000 + 3600), deviceId: "dev-1" }));
globalThis.host = {
  fetch: fakeFetch,
  store: { get: (k) => store.get(k) ?? null, set: (k, v) => void store.set(k, v) },
  secrets: { get: (k) => secrets.get(k) ?? null, set: (k, v) => void secrets.set(k, v) },
  async webLogin(url, extractJs) {
    assert.equal(url, "https://panel.fiwind.io");
    const ls = { getItem: (k) => localStorage.get(k) ?? null, clear: () => localStorage.clear() };
    return new Function("localStorage", `return ${extractJs}`)(ls);
  },
  uuid: () => "uuid",
  now: () => clock,
};

const src = readFileSync(new URL("./index.js", import.meta.url), "utf8");
const plugin = (await import("data:text/javascript;base64," + Buffer.from(src).toString("base64"))).default;
const req = (m, p = {}) => plugin.request(m, p);
const session = () => JSON.parse(secrets.get("session"));

// login: the extract script hands over the session and clears Fiwind's localStorage
assert.deepEqual(await plugin.login(globalThis.host), { connected: true });
assert.equal(localStorage.size, 0);
assert.equal(session().userId, "42");
assert.equal(fw.calls.length, 0, "a fresh access token needs no refresh at login");

// refresh-on-401: two concurrent calls with a token the server rejects → exactly one refresh, rotated token persisted
const [b1, b2] = await Promise.all([req("get_balance"), req("get_balance")]);
assert.deepEqual(b1, { balance: 50_000_000 });
assert.deepEqual(b2, { balance: 50_000_000 });
assert.deepEqual(fw.refreshCalls, [{ userId: "42", token: "R1" }]);
assert.equal(session().refreshToken, "R2");
assert.equal(session().accessToken, fw.access);
// an expired access token refreshes up front with the rotated refresh token
clock += 3600_000;
await req("get_balance");
assert.deepEqual(fw.refreshCalls.at(-1), { userId: "42", token: "R2" });
assert.equal(session().refreshToken, "R3");

// make_invoice → lookup_invoice
const inv = await req("make_invoice", { amount: 21_000, description: "coffee" });
assert.equal(fw.invoices.get("1").amount, 0.00000021, "Fiwind gets BTC");
assert.equal(inv.type, "incoming");
assert.equal(inv.amount, 21_000);
assert.equal(inv.payment_hash, "1".padStart(64, "a"));
assert.equal(inv.state, "pending");
assert.equal((await req("lookup_invoice", { payment_hash: inv.payment_hash })).state, "pending");
assert.equal((await req("lookup_invoice", { invoice: inv.invoice })).state, "pending");
fw.invoices.get("1").paid = true;
const paid = await req("lookup_invoice", { payment_hash: inv.payment_hash });
assert.equal(paid.state, "settled");
assert.ok(paid.settled_at);
await assert.rejects(req("lookup_invoice", { payment_hash: "b".repeat(64) }), { code: "NOT_FOUND" });

// poll: payment_received exactly once per paid invoice
const inv2 = await req("make_invoice", { amount: 5_000 });
let notes = await plugin.poll();
assert.deepEqual(notes.map((n) => n.notification.payment_hash), [inv.payment_hash], "already-settled invoice still notified once");
assert.equal(notes[0].notification_type, "payment_received");
assert.equal(notes[0].notification.fiwindId, undefined);
assert.deepEqual(await plugin.poll(), [], "inv2 not paid yet");
fw.invoices.get("2").paid = true;
notes = await plugin.poll();
assert.equal(notes.length, 1);
assert.equal(notes[0].notification.payment_hash, inv2.payment_hash);
assert.equal(notes[0].notification.amount, 5_000);
assert.deepEqual(await plugin.poll(), []);
assert.deepEqual(JSON.parse(store.get("pending")), []);

// pay_invoice: decode → preview → confirm; a second call doesn't pay again
const out = bolt11("c".repeat(64), 1000);
let n = fw.calls.length;
assert.deepEqual(await req("pay_invoice", { invoice: out }), { preimage: "", fees_paid: 2_000 });
assert.deepEqual(fw.calls.slice(n), [
  "POST /transactions/withdraw-requests/crypto/BTC/invoices/decode",
  "POST /transactions/withdraw-requests/crypto/preview",
  "POST /transactions/withdraw-requests/crypto/77/confirm",
]);
n = fw.calls.length;
await req("pay_invoice", { invoice: out });
assert.equal(fw.calls.length, n);
assert.equal((await req("lookup_invoice", { invoice: out })).state, "settled");
// a withdrawal that needs a security code is refused before confirming
fw.security = "OTP";
n = fw.calls.length;
await assert.rejects(req("pay_invoice", { invoice: bolt11("d".repeat(64), 1000) }), { code: "RESTRICTED" });
assert.ok(!fw.calls.slice(n).some((c) => c.endsWith("/confirm")));

await assert.rejects(req("pay_keysend", {}), { code: "NOT_IMPLEMENTED" });

// a dead refresh token marks the session invalid instead of retrying forever
fw.access = "revoked";
fw.refresh = "someone-else";
clock += 3600_000;
await assert.rejects(req("get_balance"), { code: "UNAUTHORIZED" });
assert.ok(session().invalid);
const before = fw.refreshCalls.length;
await assert.rejects(req("get_balance"), { code: "UNAUTHORIZED" });
assert.equal(fw.refreshCalls.length, before, "no more refresh attempts once invalid");

console.log("fiwind plugin: all checks passed");
