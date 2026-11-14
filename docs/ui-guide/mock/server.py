"""
Mock LaWallet instance for the UI guide: REST API + Nostr relay + NIP-47 wallet service, one port.

    docs/ui-guide/.venv/bin/python docs/ui-guide/mock/server.py        # listens on :8787

The emulator reaches it at http://10.0.2.2:8787 (debug builds allow cleartext). Every fixture below
is fake; edit WALLETS / TXS / ADDRESSES / CARDS to change what the screenshots show.
"""
import asyncio, base64, hashlib, json, os, random, time
from pathlib import Path

from aiohttp import web, WSMsgType
from coincurve import PrivateKey, PublicKey
from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

HOST = os.environ.get("MOCK_HOST", "http://10.0.2.2:8787")
RELAY = HOST.replace("http", "ws") + "/relay"
IMG = Path(__file__).parent / "img"
SETTLE_AFTER = int(os.environ.get("MOCK_SETTLE_AFTER", "12"))  # seconds until a made invoice is "paid"
rng = random.Random(21)
now = int(time.time())
DAY = 86400


def h(n=32): return bytes(rng.getrandbits(8) for _ in range(n)).hex()


# ---------------------------------------------------------------- nostr crypto
def xonly(sk: str) -> str: return PrivateKey(bytes.fromhex(sk)).public_key.format()[1:].hex()


def sign(sk: str, kind: int, content: str, tags: list) -> dict:
    pk = xonly(sk)
    ev = {"pubkey": pk, "created_at": int(time.time()), "kind": kind, "tags": tags, "content": content}
    ser = json.dumps([0, pk, ev["created_at"], kind, tags, content], separators=(",", ":"), ensure_ascii=False)
    ev["id"] = hashlib.sha256(ser.encode()).hexdigest()
    ev["sig"] = PrivateKey(bytes.fromhex(sk)).sign_schnorr(bytes.fromhex(ev["id"])).hex()
    return ev


def shared(sk: str, pk: str) -> bytes:
    return PublicKey(b"\x02" + bytes.fromhex(pk)).multiply(bytes.fromhex(sk)).format()[1:]


def nip04_enc(sk, pk, text):
    iv = os.urandom(16)
    p = padding.PKCS7(128).padder(); data = p.update(text.encode()) + p.finalize()
    e = Cipher(algorithms.AES(shared(sk, pk)), modes.CBC(iv)).encryptor()
    return base64.b64encode(e.update(data) + e.finalize()).decode() + "?iv=" + base64.b64encode(iv).decode()


def nip04_dec(sk, pk, content):
    ct, iv = content.split("?iv=")
    d = Cipher(algorithms.AES(shared(sk, pk)), modes.CBC(base64.b64decode(iv))).decryptor()
    raw = d.update(base64.b64decode(ct)) + d.finalize()
    u = padding.PKCS7(128).unpadder()
    return (u.update(raw) + u.finalize()).decode()


# ---------------------------------------------------------------- bech32 / bolt11 (fake signature)
CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"


def _polymod(values):
    g = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]; chk = 1
    for v in values:
        b = chk >> 25; chk = (chk & 0x1ffffff) << 5 ^ v
        for i in range(5): chk ^= g[i] if (b >> i) & 1 else 0
    return chk


def bech32(hrp, words):
    pre = [ord(x) >> 5 for x in hrp] + [0] + [ord(x) & 31 for x in hrp]
    pm = _polymod(pre + words + [0] * 6) ^ 1
    return hrp + "1" + "".join(CHARSET[d] for d in words + [(pm >> 5 * (5 - i)) & 31 for i in range(6)])


def to_words(data: bytes):
    acc = bits = 0; out = []
    for b in data:
        acc = acc << 8 | b; bits += 8
        while bits >= 5: bits -= 5; out.append(acc >> bits & 31)
    if bits: out.append(acc << (5 - bits) & 31)
    return out


def int_words(n, size):
    return [(n >> 5 * (size - 1 - i)) & 31 for i in range(size)]


def bolt11(msat, payment_hash, desc, ts=None):
    hrp = "lnbc" + (f"{msat // 100}n" if msat else "")
    w = int_words(ts or int(time.time()), 7)
    for t, field in ((1, to_words(bytes.fromhex(payment_hash))), (13, to_words(desc.encode())), (6, int_words(86400, 4))):
        w += [t] + int_words(len(field), 2) + field
    return bech32(hrp, w + [0] * 104)  # 104 zero words: the app decodes but never checks the signature


# ---------------------------------------------------------------- fixtures
USER_SK = os.environ.get("MOCK_USER_SK", "7f" * 32)  # login with `nsec` printed at startup
DOMAIN = "lawallet.test"


def tx(kind, sats, desc, ago, state="settled", fee=0):
    ph = h(); t = now - ago
    d = {"type": kind, "state": state, "amount": sats * 1000, "fees_paid": fee * 1000, "description": desc,
         "payment_hash": ph, "invoice": bolt11(sats * 1000, ph, desc or "", t), "created_at": t}
    if state == "settled": d["settled_at"] = t + 3; d["preimage"] = h()
    return d


TXS = [
    tx("incoming", 21000, "Salary advance 🧡", 6 * 60),
    tx("outgoing", 3500, "alice@lawallet.test", 2 * 3600, fee=2),
    tx("incoming", 1000, "Zap from bob ⚡", 5 * 3600),
    tx("outgoing", 12800, "Pizza night split 🍕", 9 * 3600, fee=6),
    tx("incoming", 50000, "Invoice #1042 — design work", 1 * DAY + 3600),
    tx("outgoing", 2100, "coffee@lacrypta.ar", 1 * DAY + 5 * 3600, fee=1),
    tx("incoming", 777, "", 1 * DAY + 8 * 3600),
    tx("outgoing", 9900, "Bus card top-up", 2 * DAY, state="pending"),
    tx("outgoing", 4200, "satoshi.store order", 3 * DAY, state="failed"),
    tx("incoming", 15000, "Refund: concert tickets", 4 * DAY),
    tx("outgoing", 650, "carol@lawallet.test", 5 * DAY, fee=1),
    tx("incoming", 100000, "Savings top-up", 9 * DAY),
    tx("outgoing", 31000, "Groceries", 12 * DAY, fee=12),
    tx("incoming", 2500, "Tip at the meetup", 20 * DAY),
    tx("outgoing", 8000, "dave@lawallet.test", 34 * DAY, fee=4),
    tx("incoming", 42000, "Freelance — logo", 41 * DAY),
]

WALLETS = [  # remote wallets; the default one is the app's main connection
    {"id": "w-main", "name": "LaWallet", "balance": 125_430, "isDefault": True, "status": "ACTIVE", "provider": "lncurl", "txs": TXS},
    {"id": "w-hub", "name": "Alby Hub", "balance": 1_250_000, "isDefault": False, "status": "ACTIVE", "provider": "alby", "txs": TXS[4:10]},
    {"id": "w-coffee", "name": "Coffee money", "balance": 4_200, "isDefault": False, "status": "ACTIVE", "provider": None, "txs": TXS[1:3]},
    {"id": "w-old", "name": "Old phone wallet", "balance": 0, "isDefault": False, "status": "DISABLED", "provider": None, "txs": []},
]
for i, w in enumerate(WALLETS):
    w["sk"] = hashlib.sha256(f"wallet{i}".encode()).hexdigest()
    w["client_sk"] = hashlib.sha256(f"client{i}".encode()).hexdigest()
    w["pk"] = xonly(w["sk"])
    w["nwc"] = f"nostr+walletconnect://{w['pk']}?relay={RELAY}&secret={w['client_sk']}&lud16=satoshi%40{DOMAIN}"

ADDRESSES = [
    {"username": "satoshi", "mode": "CUSTOM_NWC", "remoteWalletId": "w-main", "remoteWalletName": "LaWallet", "isPrimary": True, "nwcMode": "SEND_RECEIVE", "createdAt": "2026-01-03T10:00:00Z"},
    {"username": "shop", "mode": "CUSTOM_NWC", "remoteWalletId": "w-hub", "remoteWalletName": "Alby Hub", "isPrimary": False, "nwcMode": "RECEIVE", "createdAt": "2026-03-12T10:00:00Z"},
    {"username": "tips", "mode": "ALIAS", "redirect": "satoshi@getalby.com", "isPrimary": False, "createdAt": "2026-05-20T10:00:00Z"},
    {"username": "old", "mode": "IDLE", "isPrimary": False, "createdAt": "2025-11-02T10:00:00Z"},
]

CARDS = [
    {"id": "card-1", "title": "La Crypta card", "design": {"id": "d1", "imageUrl": "/img/card1.png", "description": "Orange"},
     "ntag424": {"cid": "04A1B2C3D4E5F6", "ctr": 42}, "createdAt": "2026-02-01T12:00:00Z", "lastUsedAt": "2026-11-13T18:20:00Z",
     "username": "satoshi", "remoteWalletId": "w-main", "kind": "SIMPLE"},
    {"id": "card-2", "title": "Backup card", "design": {"id": "d2", "imageUrl": "/img/card2.png", "description": "Night"},
     "ntag424": {"cid": "04FFEE11223344", "ctr": 3}, "createdAt": "2026-06-15T12:00:00Z", "kind": "SIMPLE", "disabled": True},
]


def public_wallet(w):
    return {k: w[k] for k in ("id", "name", "isDefault", "status", "provider")} | {
        "type": "NWC", "createdAt": "2026-01-03T10:00:00Z", "isOwner": True,
        "receiveCapabilities": {"lud21": True, "nip57": w["id"] == "w-hub"}}


def default_wallet(): return next(w for w in WALLETS if w["isDefault"])


# ---------------------------------------------------------------- REST API
def b64u(d: dict) -> str: return base64.urlsafe_b64encode(json.dumps(d).encode()).decode().rstrip("=")


async def jwt(req):
    ev = json.loads(base64.b64decode(req.headers["Authorization"].split(" ", 1)[1]))
    pk = ev["pubkey"]
    tok = b64u({"alg": "none"}) + "." + b64u({"pubkey": pk, "authPubkey": pk, "role": "ADMIN", "exp": int(time.time()) + DAY}) + ".mock"
    return web.json_response({"token": tok, "expiresIn": "24h"})


def J(x): return lambda req: web.json_response(x() if callable(x) else x)


async def me(req):
    return web.json_response({"userId": "u-1", "lightningAddress": f"satoshi@{DOMAIN}", "nwcString": default_wallet()["nwc"],
                              "primaryAddressMode": "CUSTOM_NWC", "primaryUsername": "satoshi",
                              "currencyPrefs": {"active": ["SAT", "USD", "ARS"], "selected": "SAT"}, "cardRecoveryEnabled": True})


async def profiles(req):
    body = await req.json()
    names = {xonly(USER_SK): ("Satoshi", "me.png")}
    return web.json_response({"profiles": [{"pubkey": p, "name": names.get(p, ("Friend", ""))[0],
                                            "picture": f"{HOST}/img/{names[p][1]}" if p in names else None} for p in body["pubkeys"]]})


def wallet_by_id(req): return next(w for w in WALLETS if w["id"] == req.match_info["id"])


async def patch_wallet(req):
    w = wallet_by_id(req); b = await req.json()
    if b.get("isDefault"):
        for o in WALLETS: o["isDefault"] = o is w
    if "name" in b: w["name"] = b["name"]
    if "status" in b: w["status"] = b["status"]
    return web.json_response(public_wallet(w))


async def echo_ok(req): return web.json_response({"ok": True})


api = [
    web.get("/api/settings", J({"domain": DOMAIN, "endpoint": HOST, "community_name": "LaWallet Demo", "lncurl_enabled": "true",
                                 "lncurl_auto_create": "true", "email_login_enabled": "true"})),
    web.get("/api/version", J({"currentVersion": "1.0.0"})),
    web.post("/api/jwt", jwt),
    web.get("/api/jwt", J({"valid": True, "role": "ADMIN"})),
    web.get("/api/users/me", me),
    web.put("/api/users/me/currency-prefs", echo_ok),
    web.put("/api/users/me/card-recovery", J({"cardRecoveryEnabled": True})),
    web.post("/api/nostr/profiles", profiles),
    web.get("/api/lightning-addresses/check", lambda r: web.json_response({"available": r.query.get("username") not in {a["username"] for a in ADDRESSES}, "username": r.query.get("username", "")})),
    web.get("/api/wallet/addresses", J(lambda: ADDRESSES)),
    web.post("/api/wallet/addresses/{u}/primary", echo_ok),
    web.put("/api/wallet/addresses/{u}", echo_ok),
    web.get("/api/remote-wallets", J(lambda: [public_wallet(w) for w in WALLETS])),
    web.get("/api/remote-wallets/{id}", lambda r: web.json_response(public_wallet(wallet_by_id(r)))),
    web.patch("/api/remote-wallets/{id}", patch_wallet),
    web.get("/api/remote-wallets/{id}/balance", lambda r: web.json_response({"balanceSats": wallet_by_id(r)["balance"]})),
    web.get("/api/remote-wallets/{id}/connection-string", lambda r: web.json_response({"connectionString": wallet_by_id(r)["nwc"]})),
    web.get("/api/remote-wallets/{id}/notifications", J([{"id": "n1", "name": "Shop webhook", "channel": "WEBHOOK", "action": "RECEIVED",
                                                          "enabled": True, "webhookUrl": "https://shop.example/hooks/ln"}])),
    web.get("/api/remote-wallets/{id}/receive-action", J({"eligible": True, "configured": True, "enabled": True, "pendingReceipts": 0,
                                                          "revision": {"number": 2, "feeBps": 50, "baseFeeSats": 1,
                                                                       "destinations": [{"address": "savings@lawallet.test", "allocationBps": 7000},
                                                                                        {"address": "charity@lawallet.test", "allocationBps": 3000}]}})),
    web.get("/api/wallet/cards", J(CARDS)),
    web.patch("/api/wallet/cards/{id}", lambda r: web.json_response(next(c for c in CARDS if c["id"] == r.match_info["id"]))),
    web.get("/api/auth/passkey/credentials", J({"credentials": [{"id": "pk1", "label": "Pixel 9 Pro", "deviceType": "multiDevice", "backedUp": True,
                                                                 "createdAt": "2026-04-02T10:00:00Z", "lastUsedAt": "2026-11-12T09:00:00Z"}]})),
    web.get("/api/oauth/grants", J({"grants": [{"id": "g1", "clientName": "Nostr POS", "scopes": ["pay", "balance"], "spendLimitSats": 50000,
                                                "createdAt": "2026-09-01T10:00:00Z", "lastUsedAt": "2026-11-10T10:00:00Z"}]})),
]


# ---------------------------------------------------------------- relay + NIP-47 wallet service
events: list = []
subs: dict = {}  # (ws, subid) -> filters


def matches(ev, f):
    if "ids" in f and ev["id"] not in f["ids"]: return False
    if "authors" in f and ev["pubkey"] not in f["authors"]: return False
    if "kinds" in f and ev["kind"] not in f["kinds"]: return False
    if "since" in f and ev["created_at"] < f["since"]: return False
    if "until" in f and ev["created_at"] > f["until"]: return False
    for k, vals in f.items():
        if k.startswith("#") and not any(t[0] == k[1:] and t[1] in vals for t in ev["tags"] if len(t) > 1): return False
    return True


async def publish(ev):
    events.append(ev)
    for (ws, sid), fs in list(subs.items()):
        if any(matches(ev, f) for f in fs):
            try: await ws.send_str(json.dumps(["EVENT", sid, ev]))
            except Exception: subs.pop((ws, sid), None)


async def notify(w, t, kind):
    body = json.dumps({"notification_type": kind, "notification": t})
    await publish(sign(w["sk"], 23196, nip04_enc(w["sk"], xonly(w["client_sk"]), body), [["p", xonly(w["client_sk"])]]))


async def settle_later(w, t):
    await asyncio.sleep(SETTLE_AFTER)
    t.update(state="settled", settled_at=int(time.time()), preimage=h())
    w["balance"] += t["amount"] // 1000
    await notify(w, t, "payment_received")


def wallet_call(w, method, p):
    if method == "get_balance": return {"balance": w["balance"] * 1000}
    if method == "get_info": return {"alias": w["name"], "network": "mainnet", "methods": ["get_balance", "pay_invoice", "make_invoice", "lookup_invoice", "list_transactions"]}
    if method == "list_transactions":
        rows = sorted(w["txs"], key=lambda t: -t["created_at"])
        rows = [t for t in rows if t["created_at"] >= p.get("from", 0) and t["created_at"] <= p.get("until", 1 << 40)]
        return {"transactions": rows[: p.get("limit", 50)]}
    if method == "lookup_invoice":
        return next((t for t in w["txs"] if t["payment_hash"] == p.get("payment_hash")), None)
    if method == "make_invoice":
        ph = h(); desc = p.get("description", "")
        t = {"type": "incoming", "state": "pending", "amount": p["amount"], "description": desc, "payment_hash": ph,
             "invoice": bolt11(p["amount"], ph, desc), "created_at": int(time.time()), "expires_at": int(time.time()) + 86400}
        w["txs"].insert(0, t); asyncio.get_event_loop().create_task(settle_later(w, t))
        return t
    if method == "pay_invoice":
        inv = p["invoice"]; msat = p.get("amount") or 0
        if not msat:  # amount from the hrp
            hrp = inv[4:inv.index("1", 4)] if "1" in inv[4:] else ""
            msat = int(hrp[:-1]) * 100 if hrp.endswith("n") else 0
        sats = msat // 1000
        if sats > w["balance"]: return None
        w["balance"] -= sats + 1
        t = {"type": "outgoing", "state": "settled", "amount": msat, "fees_paid": 1000, "description": "Lightning payment",
             "payment_hash": h(), "invoice": inv, "created_at": int(time.time()), "settled_at": int(time.time()), "preimage": h()}
        w["txs"].insert(0, t)
        # A payment between two of our wallets lands on the other one too.
        for o in WALLETS:
            for it in o["txs"]:
                if it.get("invoice") == inv and it["state"] == "pending": it.update(state="settled", settled_at=int(time.time()), preimage=t["preimage"])
        return {"preimage": t["preimage"], "fees_paid": 1000}
    return False


async def handle_request(ev):
    w = next((w for w in WALLETS if any(t[:2] == ["p", w["pk"]] for t in ev["tags"])), None)
    if not w: return
    req = json.loads(nip04_dec(w["sk"], ev["pubkey"], ev["content"]))
    res = wallet_call(w, req["method"], req.get("params", {}))
    if res is False: body = {"result_type": req["method"], "error": {"code": "NOT_IMPLEMENTED", "message": "not in the mock"}}
    elif res is None: body = {"result_type": req["method"], "error": {"code": "INSUFFICIENT_BALANCE", "message": "Not enough sats"}}
    else: body = {"result_type": req["method"], "result": res}
    await publish(sign(w["sk"], 23195, nip04_enc(w["sk"], ev["pubkey"], json.dumps(body)), [["p", ev["pubkey"]], ["e", ev["id"]]]))
    if req["method"] == "pay_invoice" and res: await notify(w, w["txs"][0], "payment_sent")


async def relay(req):
    ws = web.WebSocketResponse(heartbeat=30); await ws.prepare(req)
    async for msg in ws:
        if msg.type != WSMsgType.TEXT: continue
        m = json.loads(msg.data)
        if m[0] == "REQ":
            subs[(ws, m[1])] = m[2:]
            hits = [e for e in events if any(matches(e, f) for f in m[2:])]
            for e in hits[-(m[2].get("limit") or 500):]: await ws.send_str(json.dumps(["EVENT", m[1], e]))
            await ws.send_str(json.dumps(["EOSE", m[1]]))
        elif m[0] == "CLOSE": subs.pop((ws, m[1]), None)
        elif m[0] == "EVENT":
            ev = m[1]; await ws.send_str(json.dumps(["OK", ev["id"], True, ""]))
            if ev["kind"] == 23194: asyncio.get_event_loop().create_task(handle_request(ev))
            else: await publish(ev)
    for k in [k for k in subs if k[0] is ws]: subs.pop(k)
    return ws


def nsec(sk): return bech32("nsec", to_words(bytes.fromhex(sk)))


if __name__ == "__main__":
    for w in WALLETS:
        events.append(sign(w["sk"], 13194, "get_balance pay_invoice make_invoice lookup_invoice list_transactions get_info notifications",
                           [["notifications", "payment_received payment_sent"]]))
    app = web.Application()
    app.add_routes(api + [web.get("/relay", relay), web.static("/img", IMG)])
    print(f"mock instance {HOST}  relay {RELAY}\nlogin nsec: {nsec(USER_SK)}", flush=True)
    web.run_app(app, port=int(os.environ.get("MOCK_PORT", "8787")), print=None)
