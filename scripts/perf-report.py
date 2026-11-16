#!/usr/bin/env python3
"""LaWallet performance log: pull it from a device, then analyse it (docs/performance.md).

  scripts/perf-report.py pull   [-s SERIAL] [--pkg io.lawallet.wallet] [--label NAME]
  scripts/perf-report.py report [FILE ...] [--route SUBSTR] [--since-min N]
  scripts/perf-report.py compare BEFORE AFTER
  scripts/perf-report.py clear  [-s SERIAL] [--pkg ...]

pull   saves perf-data/<time>-<device>[-label].jsonl: the app's own records (files/perf/*.jsonl through run-as on
       debuggable builds, else the LwPerf lines still in logcat) plus a `sys` record from dumpsys gfxinfo/meminfo.
report summarises one or more pulls (default: the newest): screens by jank, enter transitions, start, resources,
       NWC latency. Records are de-duplicated across files, so passing several pulls is safe.
compare per-route before/after: jank %, p90 and enter-transition jank.
clear  empties the app's log and resets gfxinfo counters, for a clean before/after run (debuggable builds).
Standard library only.
"""
import argparse, glob, json, os, re, subprocess, sys, time
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "perf-data")


def adb(serial, *args, check=False):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def parse_lines(text):
    out = []
    for line in text.splitlines():
        i = line.find("{")
        if i < 0:
            continue
        try:
            out.append(json.loads(line[i:]))
        except ValueError:
            pass
    return out


def gfxinfo(serial, pkg):
    g = adb(serial, "shell", "dumpsys", "gfxinfo", pkg)
    rec = {"t": "sys", "ts": int(time.time() * 1000)}
    for key, pat in [("total_frames", r"Total frames rendered: (\d+)"), ("janky", r"Janky frames: (\d+)"),
                     ("janky_legacy", r"Janky frames \(legacy\): (\d+)"), ("p50", r"50th percentile: (\d+)ms"),
                     ("p90", r"90th percentile: (\d+)ms"), ("p95", r"95th percentile: (\d+)ms"),
                     ("p99", r"99th percentile: (\d+)ms"), ("slow_ui", r"Number Slow UI thread: (\d+)"),
                     ("slow_draw", r"Number Slow issue draw commands: (\d+)"), ("high_input", r"Number High input latency: (\d+)")]:
        m = re.search(pat, g)
        if m:
            rec[key] = int(m.group(1))
    m = re.search(r"TOTAL PSS:\s+(\d+)", adb(serial, "shell", "dumpsys", "meminfo", pkg))
    if m:
        rec["pss_mb"] = int(m.group(1)) // 1024
    return rec


def cmd_pull(a):
    os.makedirs(DATA, exist_ok=True)
    recs, how = [], "run-as"
    for name in ("perf.1.jsonl", "perf.jsonl"):
        recs += parse_lines(adb(a.serial, "exec-out", "run-as", a.pkg, "cat", f"files/perf/{name}"))
    if not recs:  # release builds can't be run-as'd: what logcat still holds
        how = "logcat"
        recs = parse_lines(adb(a.serial, "logcat", "-d", "-s", "LwPerf:I"))
    model = adb(a.serial, "shell", "getprop", "ro.product.model").strip().replace(" ", "_") or "device"
    rate = re.search(r"renderFrameRate ([\d.]+)", adb(a.serial, "shell", "dumpsys", "display"))
    sysrec = gfxinfo(a.serial, a.pkg) | {"model": model, "refresh_hz": float(rate.group(1)) if rate else None, "source": how}
    path = os.path.join(DATA, time.strftime("%Y%m%d-%H%M%S") + f"-{model}" + (f"-{a.label}" if a.label else "") + ".jsonl")
    with open(path, "w") as f:
        for r in recs + [sysrec]:
            f.write(json.dumps(r) + "\n")
    print(f"{len(recs)} records ({how}) + gfxinfo → {os.path.relpath(path, ROOT)}")


def cmd_clear(a):
    adb(a.serial, "shell", f"run-as {a.pkg} rm -f files/perf/perf.jsonl files/perf/perf.1.jsonl")  # one string: adb shell re-splits args
    adb(a.serial, "shell", "dumpsys", "gfxinfo", a.pkg, "reset")
    adb(a.serial, "logcat", "-c")
    print("perf log, gfxinfo and logcat cleared")


def load(files):
    if not files:
        pulls = sorted(glob.glob(os.path.join(DATA, "*.jsonl")))
        if not pulls:
            sys.exit("no pulls in perf-data/: run `scripts/perf-report.py pull` first")
        files = pulls[-1:]
    seen, recs = set(), []
    for path in files:
        for r in parse_lines(open(path).read()):
            key = json.dumps(r, sort_keys=True)
            if key not in seen:
                seen.add(key)
                recs.append(r)
    return files, sorted(recs, key=lambda r: r.get("ts", 0))


def pct(vals, p):
    if not vals:
        return 0
    s = sorted(vals)
    return s[max(0, min(len(s) - 1, int(-(-p * len(s) // 1)) - 1))]


def screens(recs):
    by = defaultdict(lambda: defaultdict(list))
    for r in recs:
        if r.get("t") != "screen":
            continue
        d = by[r["route"]]
        d["visits"].append(1)
        for k in ("frames", "janky", "frozen", "enter_frames", "enter_janky"):
            d[k].append(r.get(k, 0))
        for k in ("p90", "p99", "max", "ui_p90", "gpu_p90", "enter_p90", "enter_max", "enter_ui_p90", "enter_gpu_p90"):
            if k in r:
                d[k].append(r[k])
    out = {}
    for route, d in by.items():
        fr, ef = sum(d["frames"]), sum(d["enter_frames"])
        out[route] = {
            "visits": len(d["visits"]), "frames": fr + ef,
            "jank": 100 * sum(d["janky"]) / fr if fr else 0, "frozen": sum(d["frozen"]),
            "p90": pct(d["p90"], .5), "p99": pct(d["p99"], .9), "max": max(d["max"] or [0]),
            "ui90": pct(d["ui_p90"], .5), "gpu90": pct(d["gpu_p90"], .5),
            "enter_jank": 100 * sum(d["enter_janky"]) / ef if ef else 0, "enter_p90": pct(d["enter_p90"], .5),
            "enter_max": max(d["enter_max"] or [0]), "enter_ui90": pct(d["enter_ui_p90"], .5), "enter_gpu90": pct(d["enter_gpu_p90"], .5),
        }
    return out


def cmd_report(a):
    files, recs = load(a.files)
    if a.since_min:
        cut = (recs[-1]["ts"] if recs else 0) - a.since_min * 60_000
        recs = [r for r in recs if r.get("ts", 0) >= cut]
    if a.route:
        recs = [r for r in recs if r.get("t") != "screen" or a.route in r.get("route", "")]
    builds = sorted({f"{r.get('build')} vc{r.get('vc')}" for r in recs if r.get("build")})
    print(f"files: {', '.join(os.path.relpath(f, ROOT) for f in files)}")
    print(f"builds: {', '.join(builds) or '?'}   records: {len(recs)}")
    for s in [r for r in recs if r.get("t") == "sys"][-1:]:
        print(f"device: {s.get('model')} @ {s.get('refresh_hz')} Hz · gfxinfo: {s.get('janky')}/{s.get('total_frames')} janky, "
              f"p50 {s.get('p50')} p90 {s.get('p90')} p99 {s.get('p99')} ms · slow UI thread {s.get('slow_ui')} · PSS {s.get('pss_mb')} MB")

    sc = screens(recs)
    print("\nSCREENS (worst first; frame ms; ui = main thread, gpu = GPU; enter = the navigation transition)")
    print(f"{'route':38} {'vis':>4} {'frames':>7} {'jank%':>6} {'p90':>5} {'p99':>6} {'max':>6} {'ui90':>5} {'gpu90':>5} | {'ent jank%':>9} {'ent p90':>7} {'ent max':>7} {'ent ui90':>8}")
    for route, s in sorted(sc.items(), key=lambda kv: -(kv[1]["jank"] + kv[1]["enter_jank"])):
        print(f"{route[:38]:38} {s['visits']:>4} {s['frames']:>7} {s['jank']:>6.1f} {s['p90']:>5} {s['p99']:>6} {s['max']:>6} {s['ui90']:>5} {s['gpu90']:>5} | "
              f"{s['enter_jank']:>9.1f} {s['enter_p90']:>7} {s['enter_max']:>7} {s['enter_ui90']:>8}")

    for key, label in (("cold_ms", "COLD START (process → first frame)"), ("ui_ms", "UI START (activity → first frame)")):
        v = [r[key] for r in recs if r.get("t") == "start" and r.get(key) is not None]
        if v:
            print(f"\n{label}  n={len(v)}  p50 {pct(v, .5)} ms  p90 {pct(v, .9)} ms  max {max(v)} ms")

    res = [r for r in recs if r.get("t") == "res"]
    if res:
        print(f"\nRESOURCES  samples={len(res)}")
        for k in ("heap_mb", "native_mb", "pss_mb", "threads", "cpu_pct"):
            v = [r[k] for r in res if r.get(k) is not None]
            if v:
                print(f"  {k:10} first {v[0]:>7}  last {v[-1]:>7}  p50 {pct(v, .5):>7}  max {max(v):>7}")

    nwc = defaultdict(list)
    fails = defaultdict(int)
    for r in recs:
        if r.get("t") == "nwc":
            nwc[r["method"]].append(r["ms"])
            if r.get("outcome") not in ("ok", "cancelled"):
                fails[r["method"]] += 1
    if nwc:
        print("\nNWC REQUESTS (ms)")
        for m, v in sorted(nwc.items(), key=lambda kv: -len(kv[1])):
            print(f"  {m:22} n={len(v):<5} p50 {pct(v, .5):>6}  p90 {pct(v, .9):>6}  max {max(v):>6}  failed {fails[m]}")


def cmd_compare(a):
    _, before = load([a.before])
    _, after = load([a.after])
    b, c = screens(before), screens(after)
    print(f"{'route':38} {'jank% before→after':>20} {'p90 before→after':>18} {'enter jank% before→after':>26}")
    for route in sorted(set(b) | set(c)):
        x, y = b.get(route), c.get(route)
        f = lambda s, k, d=1: "—" if not s else f"{s[k]:.{d}f}"
        print(f"{route[:38]:38} {f(x, 'jank') + ' → ' + f(y, 'jank'):>20} {f(x, 'p90') + ' → ' + f(y, 'p90'):>18} {f(x, 'enter_jank') + ' → ' + f(y, 'enter_jank'):>26}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    for name in ("pull", "clear"):
        s = sub.add_parser(name)
        s.add_argument("-s", "--serial")
        s.add_argument("--pkg", default="io.lawallet.wallet")
        if name == "pull":
            s.add_argument("--label", help="tag for the file name, e.g. before-fix")
    r = sub.add_parser("report")
    r.add_argument("files", nargs="*")
    r.add_argument("--route", help="only screens whose route contains this")
    r.add_argument("--since-min", type=int, help="only the last N minutes of records")
    c = sub.add_parser("compare")
    c.add_argument("before")
    c.add_argument("after")
    a = p.parse_args()
    {"pull": cmd_pull, "clear": cmd_clear, "report": cmd_report, "compare": cmd_compare}[a.cmd](a)


if __name__ == "__main__":
    main()
