"""
Drive the emulator and capture screens for the UI guide.

    ui.py shot <id>             screenshot + view hierarchy → shots/<id>.webp, shots/<id>.xml
    ui.py nodes [<id>]          list tagged / text / clickable nodes (live screen, or a saved shot)
    ui.py tap <tag|text>        tap the first node whose test tag, text or description matches
    ui.py route <route>         open an app route (R0 constant value), e.g. settings/cards
    ui.py swipe x1 y1 x2 y2 [ms]
    ui.py key <keycode>         e.g. BACK
    ui.py type <text>

Env: SERIAL (default emulator-5554), PKG (default io.lawallet.wallet.guide).
"""
import os, re, subprocess, sys, time
import xml.etree.ElementTree as ET
from pathlib import Path

SERIAL = os.environ.get("SERIAL", "emulator-5554")
PKG = os.environ.get("PKG", "io.lawallet.wallet.guide")
SHOTS = Path(__file__).parent / "shots"


def adb(*a, out=False):
    r = subprocess.run(["adb", "-s", SERIAL, *a], capture_output=True)
    return r.stdout if out else None


def dump() -> bytes:
    for _ in range(3):  # uiautomator fails while the UI animates; retry
        x = adb("exec-out", "uiautomator dump /dev/tty", out=True)
        x = x[x.find(b"<?xml"): x.rfind(b"</hierarchy>") + 12]
        if x.startswith(b"<?xml"): return x
        time.sleep(1)
    raise SystemExit("uiautomator dump failed")


def bounds(n): return [int(v) for v in re.findall(r"\d+", n.get("bounds"))]


def nodes(xml: bytes):
    out = []
    for n in ET.fromstring(xml).iter("node"):
        tag = n.get("resource-id", "").split("/")[-1]
        text, desc = n.get("text", ""), n.get("content-desc", "")
        if tag or text or desc or n.get("clickable") == "true" or n.get("scrollable") == "true":
            out.append({"tag": tag, "text": text, "desc": desc, "click": n.get("clickable") == "true",
                        "scroll": n.get("scrollable") == "true", "b": bounds(n)})
    return out


def find(xml, q):
    for n in nodes(xml):
        if q in (n["tag"], n["text"], n["desc"]): return n
    for n in nodes(xml):
        if q.lower() in (n["text"] + " " + n["desc"]).lower(): return n
    raise SystemExit(f"no node matches {q!r}")


def main(cmd, *a):
    if cmd == "shot":
        SHOTS.mkdir(exist_ok=True)
        time.sleep(float(os.environ.get("SETTLE", "1.2")))
        top = adb("shell", "dumpsys activity activities | grep topResumedActivity", out=True).decode()
        if f" {PKG}/" not in top and "permissioncontroller" not in top:
            raise SystemExit(f"{PKG} is not in front: {top.strip()}")
        (SHOTS / f"{a[0]}.xml").write_bytes(dump())
        png = adb("exec-out", "screencap -p", out=True)
        from io import BytesIO
        from PIL import Image
        Image.open(BytesIO(png)).convert("RGB").save(SHOTS / f"{a[0]}.webp", quality=90, method=6)  # ~10× smaller than PNG
        print(SHOTS / f"{a[0]}.webp")
    elif cmd == "nodes":
        xml = (SHOTS / f"{a[0]}.xml").read_bytes() if a else dump()
        for n in nodes(xml):
            flags = ("C" if n["click"] else "-") + ("S" if n["scroll"] else "-")
            print(flags, n["b"], n["tag"] or "·", "|", n["text"][:50], "|", n["desc"][:40])
    elif cmd == "tap":
        x1, y1, x2, y2 = find(dump(), a[0])["b"]
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    elif cmd == "route":
        adb("shell", "am", "start", "-n", f"{PKG}/io.lawallet.wallet.MainActivity", "--es", "route", a[0])
    elif cmd == "swipe":
        adb("shell", "input", "swipe", *a[:4], a[4] if len(a) > 4 else "350")
    elif cmd == "key":
        adb("shell", "input", "keyevent", a[0])
    elif cmd == "type":
        adb("shell", "input", "text", a[0].replace(" ", "%s"))
    else:
        print(__doc__)


if __name__ == "__main__":
    main(*(sys.argv[1:] or ["help"]))
