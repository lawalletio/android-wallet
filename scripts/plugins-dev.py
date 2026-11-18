#!/usr/bin/env python3
"""Local plugin development (docs/plugins.md "Developing plugins locally").

plugins-dev/ (gitignored) is a checkout of a plugins repo, laid out as plugins/<name>/{manifest.json,index.js}.
Debug builds list what this serves under Settings → Plugins → Local, with Install / Reload.

  scripts/plugins-dev.py                    serve plugins-dev/ on :8787 and `adb reverse` it to every device
  scripts/plugins-dev.py import <git url>   clone a plugins repo into plugins-dev/ (or pull, if it already is one)
  scripts/plugins-dev.py --port 9000        another port (build the app with -PpluginsDevUrl=http://localhost:9000)

Edit plugins-dev/plugins/<name>/index.js, tap Reload in the app, and commit from plugins-dev/ to the plugins repo.
"""
import argparse
import http.server
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "plugins-dev"


def manifests():
    """Relative paths of every plugin under ROOT: plugins/<name>/manifest.json, or <name>/manifest.json."""
    found = sorted(ROOT.glob("plugins/*/manifest.json")) + sorted(ROOT.glob("*/manifest.json"))
    return [p.relative_to(ROOT).as_posix() for p in found]


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(ROOT), **kw)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")  # every Reload gets the file as it is now
        super().end_headers()

    def do_GET(self):
        if self.path.split("?")[0] == "/index.json":
            body = json.dumps({"plugins": manifests()}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            super().do_GET()


def adb_reverse(port):
    """Every connected device (emulator or phone over adb) reaches this server at localhost:<port>."""
    try:
        out = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError):
        print("adb not found: point the app at this Mac's address with -PpluginsDevUrl instead")
        return
    for line in out.splitlines()[1:]:
        serial, _, state = line.partition("\t")
        if state.strip() == "device":
            ok = subprocess.run(["adb", "-s", serial, "reverse", f"tcp:{port}", f"tcp:{port}"], capture_output=True).returncode == 0
            print(f"  {'reversed' if ok else 'could not reverse'} on {serial}")


def serve(port):
    if not ROOT.is_dir():
        sys.exit(f"{ROOT} doesn't exist: `scripts/plugins-dev.py import <plugins repo>` or create plugins-dev/plugins/<name>/")
    print(f"Serving {ROOT} on http://localhost:{port} ({len(manifests())} plugins: {', '.join(manifests()) or 'none'})")
    adb_reverse(port)
    http.server.ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


def import_repo(url):
    if (ROOT / ".git").is_dir():
        subprocess.run(["git", "-C", str(ROOT), "pull", "--ff-only"], check=True)
    elif ROOT.exists() and any(ROOT.iterdir()):
        sys.exit(f"{ROOT} has files but isn't a git checkout: move them aside, or `git init` there and add {url} as its remote")
    else:
        subprocess.run(["git", "clone", url, str(ROOT)], check=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", nargs="?", choices=["serve", "import"], default="serve")
    ap.add_argument("url", nargs="?")
    ap.add_argument("--port", type=int, default=8787)
    args = ap.parse_args()
    if args.command == "import":
        if not args.url:
            sys.exit("usage: scripts/plugins-dev.py import <git url>")
        import_repo(args.url)
    else:
        serve(args.port)
