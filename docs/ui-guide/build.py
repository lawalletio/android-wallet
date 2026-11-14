"""
Builds docs/ui-guide/LaWallet-UI-Guide.pdf from guide.yaml + shots/.

    docs/ui-guide/.venv/bin/python docs/ui-guide/build.py

For every screen in guide.yaml it finds each component on the saved view hierarchy (shots/<id>.xml),
draws a numbered marker on the screenshot (shots/<id>.webp), and lays both out as one PDF page.
Selectors that no longer match are reported, so a UI change shows up as a build warning.
"""
import html, json, re, shutil, subprocess, sys
import xml.etree.ElementTree as ET
from pathlib import Path

import yaml
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).parent
SHOTS, OUT = ROOT / "shots", ROOT / "build"
PALETTE = ["#ff3b6b", "#2f80ff", "#00b386", "#ff9f1a", "#a259ff", "#00b8d9", "#e5484d", "#7cb518"]
GESTURE = "#ff2bd6"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
FONT = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
warnings = []


def nodes(screen_id):
    out = []
    for n in ET.parse(SHOTS / f"{screen_id}.xml").iter("node"):
        out.append({"tag": n.get("resource-id", "").split("/")[-1], "text": n.get("text", ""),
                    "desc": n.get("content-desc", ""), "b": [int(v) for v in re.findall(r"\d+", n.get("bounds"))]})
    return out


def resolve(screen_id, ns, sel):
    """tag:x | tag:x#2 | text:Foo | desc:Foo | box:x1,y1,x2,y2 — or a list of them (union)."""
    if isinstance(sel, list):
        boxes = [b for s in sel if (b := resolve(screen_id, ns, s))]
        return [min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes)] if boxes else None
    kind, _, q = sel.partition(":")
    if kind == "box": return [int(v) for v in q.split(",")]
    q, _, nth = q.partition("#")
    hits = [n for n in ns if n[kind] == q] or [n for n in ns if kind != "tag" and q.lower() in n[kind].lower()]
    idx = int(nth or 1) - 1
    if idx >= len(hits):
        warnings.append(f"{screen_id}: no match for {sel!r}")
        return None
    return hits[idx]["b"]


def arrow(d, a, b, color, w=10):
    import math
    d.line([tuple(a), tuple(b)], fill=color, width=w)
    ang = math.atan2(b[1] - a[1], b[0] - a[0])
    for s in (2.6, -2.6):
        d.line([tuple(b), (b[0] + 42 * math.cos(ang + s), b[1] + 42 * math.sin(ang + s))], fill=color, width=w)


def annotate(screen):
    sid = screen["id"]
    im = Image.open(SHOTS / f"{sid}.webp").convert("RGBA")
    ns = nodes(sid)
    over = Image.new("RGBA", im.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    font, small = ImageFont.truetype(FONT, 48), ImageFont.truetype(FONT, 38)
    placed = []
    for i, c in enumerate(screen.get("components", []), 1):
        c["n"], c["color"] = i, PALETTE[(i - 1) % len(PALETTE)]
        b = resolve(sid, ns, c["sel"])
        if not b: c["missing"] = True; continue
        d.rounded_rectangle(b, radius=14, outline=c["color"], width=6)
        x, y = max(b[0] - 8, 40), max(b[1] - 8, 40)
        while any(abs(x - px) < 80 and abs(y - py) < 80 for px, py in placed): x += 84
        x = min(x, im.width - 40)
        placed.append((x, y))
        d.ellipse([x - 38, y - 38, x + 38, y + 38], fill=c["color"], outline="white", width=4)
        d.text((x, y), str(i), font=font, fill="white", anchor="mm")
    for g in screen.get("gestures", []):
        if "arrow" not in g: continue
        a, b = g["arrow"][:2], g["arrow"][2:]
        arrow(d, a, b, GESTURE)
        tx, ty = g.get("at", [(a[0] + b[0]) // 2 + 24, (a[1] + b[1]) // 2])
        tb = d.textbbox((tx, ty), g["label"], font=small, anchor="lm")
        d.rounded_rectangle([tb[0] - 12, tb[1] - 8, tb[2] + 12, tb[3] + 8], radius=10, fill=GESTURE)
        d.text((tx, ty), g["label"], font=small, fill="white", anchor="lm")
    im = Image.alpha_composite(im, over).convert("RGB")
    im = im.resize((im.width // 2, im.height // 2), Image.LANCZOS)
    path = OUT / "img" / f"{sid}.jpg"
    im.save(path, quality=85)
    return path.relative_to(OUT)


def esc(s): return html.escape(str(s or ""))


def md(s):  # **bold** and `code` in YAML prose
    s = esc(s)
    s = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", s)
    return re.sub(r"`(.+?)`", r"<code>\1</code>", s)


def screen_page(sec, s, img):
    comps = "".join(
        f"<tr class='{'missing' if c.get('missing') else ''}'><td><span class='badge' style='background:{c['color']}'>{c['n']}</span></td>"
        f"<td><b>{esc(c['name'])}</b><div class='tag'>{esc(c['sel'] if isinstance(c['sel'], str) else ' + '.join(c['sel']))}</div></td>"
        f"<td>{md(c.get('does'))}</td></tr>" for c in s.get("components", []))
    gest = "".join(f"<li><b>{esc(g['label'])}</b> — {md(g['does'])}</li>" for g in s.get("gestures", []))
    meta = "".join(f"<tr><th>{k}</th><td>{md(s[f])}</td></tr>" for k, f in
                   [("Ref", "ref"), ("Route", "route"), ("Source", "source"), ("State", "state"), ("Reach", "reach")] if s.get(f))
    return f"""<section class='page screen' id='{s['id']}'>
      <div class='crumb'>{esc(sec['title'])} › <b>{esc(s['name'])}</b><span class='sid'>{esc(s['id'])}</span></div>
      <div class='cols'>
        <div class='shot'><img src='{img}'></div>
        <div class='info'>
          <table class='meta'>{meta}</table>
          <p class='summary'>{md(s.get('summary'))}</p>
          {f"<h4>Gestures & behaviour</h4><ul class='gest'>{gest}</ul>" if gest else ""}
          {f"<h4>Components</h4><table class='comps'>{comps}</table>" if comps else ""}
          {f"<p class='note'>{md(s['notes'])}</p>" if s.get('notes') else ""}
        </div>
      </div></section>"""


def prose_page(p):
    body = "".join(f"<h3>{md(b['h'])}</h3>" if "h" in b else f"<p>{md(b['p'])}</p>" if "p" in b else
                   "<ul>" + "".join(f"<li>{md(x)}</li>" for x in b["ul"]) + "</ul>" if "ul" in b else
                   "<table class='grid'>" + "".join("<tr>" + "".join(f"<{'th' if r == 0 else 'td'}>{md(c)}</{'th' if r == 0 else 'td'}>" for c in row) + "</tr>" for r, row in enumerate(b["table"])) + "</table>" if "table" in b else
                   f"<pre>{esc(b['pre'])}</pre>" if "pre" in b else
                   home_map() if "home_map" in b else "" for b in p["body"])
    return f"<section class='page prose'><h2>{md(p['title'])}</h2>{body}</section>"


def home_map():
    """3×3 grid of Home: pages across (horizontal swipe), vertical layers down (swipe down / up)."""
    grid = [["21-home-onchain-wallets", "11-home-wallets", "23-home-usdt-wallets"],
            ["20-home-onchain", "10-home-lightning", "22-home-usdt"],
            ["27-home-onchain-raised", "14-home-activity-raised", "28-home-usdt-raised"]]
    rows = ["Wallet deck<br><small>swipe ↓ on balance</small>", "Docked<br><small>default</small>", "Activity raised<br><small>swipe ↑ on activity</small>"]
    cells = "".join(f"<tr><th>{rows[r]}</th>" + "".join(
        f"<td><img src='img/thumb-{sid}.jpg'><div>{sid}</div></td>" for sid in line) + "</tr>" for r, line in enumerate(grid))
    for line in grid:
        for sid in line:
            Image.open(SHOTS / f"{sid}.webp").convert("RGB").resize((216, 480), Image.LANCZOS).save(OUT / "img" / f"thumb-{sid}.jpg", quality=85)
    return f"<table class='map'><tr><th></th><th>Onchain<br><small>← swipe right</small></th><th>Lightning<br><small>opens here</small></th><th>USDT<br><small>swipe left →</small></th></tr>{cells}</table>"


CSS = """
@page { size: A4; margin: 12mm; }
* { box-sizing: border-box; }
body { font: 9.5pt/1.4 -apple-system, Helvetica, Arial, sans-serif; color: #15171c; margin: 0; }
.page { page-break-after: always; }
h1 { font-size: 30pt; margin: 60mm 0 4mm; } h2 { font-size: 17pt; margin: 0 0 4mm; border-bottom: 2px solid #15171c; padding-bottom: 2mm; }
h3 { font-size: 11.5pt; margin: 5mm 0 1.5mm; } h4 { margin: 3mm 0 1mm; font-size: 10pt; }
code { font: 8.5pt Menlo, monospace; background: #f0f1f4; padding: 0 3px; border-radius: 3px; }
pre { font: 8pt Menlo, monospace; background: #f4f5f7; padding: 3mm; border-radius: 4px; white-space: pre-wrap; }
.crumb { font-size: 12pt; padding-bottom: 2mm; border-bottom: 2px solid #15171c; margin-bottom: 3mm; }
.sid { float: right; font: 9pt Menlo, monospace; background: #15171c; color: #fff; padding: 1px 6px; border-radius: 4px; }
.cols { display: flex; gap: 5mm; }
.shot img { width: 72mm; border-radius: 4mm; border: 1px solid #ccd; }
.info { flex: 1; min-width: 0; }
table { border-collapse: collapse; width: 100%; }
.meta th { text-align: left; width: 15mm; color: #667; font-weight: 600; vertical-align: top; padding: 0.6mm 2mm 0.6mm 0; }
.meta td { padding: 0.6mm 0; }
.summary { margin: 2mm 0; }
.comps td { border-top: 1px solid #e3e5ea; padding: 1mm 1.5mm; vertical-align: top; font-size: 8.6pt; }
.comps td:first-child { width: 8mm; padding-left: 0; }
.comps td:nth-child(2) { width: 38mm; }
.comps tr.missing td { color: #b00; text-decoration: line-through; }
.badge { display: inline-block; width: 6mm; height: 6mm; line-height: 6mm; border-radius: 50%; color: #fff; text-align: center; font-weight: 700; font-size: 8pt; }
.tag { font: 7.5pt Menlo, monospace; color: #667; word-break: break-all; }
.gest { padding-left: 4mm; margin: 1mm 0; } .gest li { margin-bottom: 1mm; }
.note { color: #555; font-style: italic; border-left: 3px solid #ccd; padding-left: 2mm; }
.grid th, .grid td { border: 1px solid #dde; padding: 1mm 2mm; text-align: left; vertical-align: top; font-size: 8.6pt; }
.grid th { background: #f0f1f4; }
.map th { font-size: 8.5pt; padding: 1mm; } .map td { text-align: center; padding: 1mm; font: 7pt Menlo, monospace; color: #667; }
.map img { width: 25mm; border-radius: 2mm; border: 1px solid #ccd; display: block; margin: 0 auto 1mm; }
.toc > ul { columns: 2; column-gap: 8mm; } .toc > ul > li { break-inside: avoid; } .toc a { color: inherit; text-decoration: none; } .toc li { margin: 0.5mm 0; }
.toc .sid { float: none; font-size: 7.5pt; margin-left: 2mm; }
"""


WEB_CSS = """
/* Screen version: each PDF page becomes a sheet on a tinted desk. */
:root { --bg: #e9ecf2; --sheet: #ffffff; --fg: #15171c; --muted: #5d6573; --line: #dde1e8; --chip: #15171c; --chip-fg: #ffffff;
        --code: #eef0f4; --accent: #2f6bff; --head: 'Space Grotesk', Inter, system-ui, sans-serif; --body: Inter, system-ui, sans-serif; }
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { --bg: #0d0f14; --sheet: #171a21; --fg: #e8eaf0; --muted: #9aa3b2;
        --line: #2a2f3a; --chip: #e8eaf0; --chip-fg: #15171c; --code: #232733; --accent: #7aa2ff; color-scheme: dark } }
:root[data-theme="dark"] { --bg: #0d0f14; --sheet: #171a21; --fg: #e8eaf0; --muted: #9aa3b2; --line: #2a2f3a; --chip: #e8eaf0;
        --chip-fg: #15171c; --code: #232733; --accent: #7aa2ff; color-scheme: dark }
body { background: var(--bg); color: var(--fg); font: 15px/1.5 var(--body);
       padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); padding-block: 24px; }
main { max-width: 980px; margin: 0 auto; display: flex; flex-direction: column; gap: 20px; }
.page { background: var(--sheet); border: 1px solid var(--line); border-radius: 10px; padding: clamp(16px, 4vw, 36px); page-break-after: auto; min-width: 0; }
h1, h2, .crumb b { font-family: var(--head); text-wrap: balance; } h1 { margin: 8px 0 12px; font-size: clamp(28px, 6vw, 44px); }
h2, .crumb { border-color: var(--fg) !important; }
code, pre { background: var(--code); color: var(--fg); } pre { overflow-x: auto; }
.sid { background: var(--chip); color: var(--chip-fg); }
.cols { flex-wrap: wrap; } .shot { flex: 0 0 auto; max-width: 100%; } .shot img { width: 300px; max-width: 100%; border-color: var(--line); }
.info { min-width: min(100%, 320px); }
.meta th, .tag, .map td { color: var(--muted); } .comps td, .grid th, .grid td { border-color: var(--line); }
.grid { display: block; overflow-x: auto; } .grid th { background: var(--code); }
.map { display: block; overflow-x: auto; } .map img { width: 150px; border-color: var(--line); }
.note { color: var(--muted); border-color: var(--line); }
a { color: var(--accent); } .toc a { color: var(--fg); } .toc a:hover { color: var(--accent); }
.toc > ul { columns: 2 260px; }
a:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
"""


def main():
    spec = yaml.safe_load((ROOT / "guide.yaml").read_text())
    shutil.rmtree(OUT, ignore_errors=True); (OUT / "img").mkdir(parents=True)
    pages = [f"<section class='page'><h1>{esc(spec['title'])}</h1><p style='font-size:13pt'>{md(spec['subtitle'])}</p>"
             f"<p>{md(spec['generated'])}</p></section>"]
    toc = "".join(f"<li><b>{esc(sec['title'])}</b><ul>" + "".join(
        f"<li><a href='#{s['id']}'>{esc(s['name'])}</a><span class='sid'>{esc(s['id'])}</span></li>" for s in sec.get("screens", [])) + "</ul></li>"
                  for sec in spec["sections"] if sec.get("screens"))
    pages.append(f"<section class='page toc'><h2>Contents</h2><ul>{toc}</ul></section>")
    for p in spec.get("intro", []): pages.append(prose_page(p))
    for sec in spec["sections"]:
        for p in sec.get("pages", []): pages.append(prose_page(p))
        for s in sec.get("screens", []):
            if not (SHOTS / f"{s['id']}.webp").exists(): warnings.append(f"{s['id']}: screenshot missing"); continue
            pages.append(screen_page(sec, s, annotate(s)))
    for p in spec.get("appendix", []): pages.append(prose_page(p))
    out_html = OUT / "guide.html"
    out_html.write_text(f"<!doctype html><meta charset='utf-8'><title>{esc(spec['title'])}</title><style>{CSS}</style>{''.join(pages)}")
    # Same pages for screens (Claude artifact / any browser): build/web.html + build/img/.
    (OUT / "web.html").write_text(f"<title>LaWallet UI Guide</title><meta name='claude:viewport-fit-sides' content='cover'>"
                                  f"<link rel='stylesheet' href='https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&family=Space+Grotesk:wght@600;700&display=swap'>"
                                  f"<style>{CSS.split('@page')[0]}{CSS[CSS.index('}', CSS.index('@page')) + 1:]}{WEB_CSS}</style><main>{''.join(pages)}</main>")
    pdf = ROOT / "LaWallet-UI-Guide.pdf"
    subprocess.run([CHROME, "--headless", "--disable-gpu", "--no-pdf-header-footer", f"--print-to-pdf={pdf}", out_html.as_uri()],
                   check=True, capture_output=True)
    print(f"{pdf}  ({pdf.stat().st_size // 1024} KB)")
    for w in warnings: print("WARN", w)
    return 1 if warnings and "--strict" in sys.argv else 0


if __name__ == "__main__":
    sys.exit(main())
