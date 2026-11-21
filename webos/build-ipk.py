#!/usr/bin/env python3
"""Build the CanvasFrame webOS package (an ares-package style .ipk) without the webOS SDK.

    python webos/build-ipk.py            -> webos/build/com.canvasframe.home_<version>_all.ipk

The package holds the wrapper app (webos/app), the server (webos/server) and the shared dashboard page
(launcher/assets/dashboard.html, so both platforms serve the same file).
"""
import io
import json
import os
import tarfile
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(ROOT)
BUILD_TMP = os.path.join(ROOT, "build", "tmp")   # generated files that go into the package
APP_ID = "com.canvasframe.home"


def add_bytes(tar, name, data, mode=0o644):
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = mode
    info.mtime = int(time.time())
    info.uname = info.gname = "root"
    tar.addfile(info, io.BytesIO(data))


def add_dir(tar, name):
    info = tarfile.TarInfo(name)
    info.type = tarfile.DIRTYPE
    info.mode = 0o755
    info.mtime = int(time.time())
    tar.addfile(info)


def files():
    """(path inside the app dir, local file) pairs."""
    out = []
    for base, prefix in ((os.path.join(ROOT, "app"), ""), (os.path.join(ROOT, "server"), "server/")):
        for d, _, names in os.walk(base):
            if "test" in os.path.relpath(d, base).split(os.sep):
                continue
            for n in sorted(names):
                full = os.path.join(d, n)
                rel = os.path.relpath(full, base).replace(os.sep, "/")
                out.append((prefix + rel, full))
    out.append(("server/dashboard.html", os.path.join(REPO, "launcher", "assets", "dashboard.html")))
    # CanvasFrame Link for Windows (pc/), offered as a download by the dashboard; build it first with pc/build.ps1.
    exe = os.path.join(REPO, "pc", "dist", "CanvasFrameLink.exe")
    if os.path.exists(exe):
        out.append(("server/pcapp/CanvasFrameLink.exe", exe))
        meta = os.path.join(BUILD_TMP, "CanvasFrameLink.json")
        os.makedirs(BUILD_TMP, exist_ok=True)
        with open(meta, "w") as f:
            json.dump({"version": pc_version(), "name": "CanvasFrame Link"}, f)
        out.append(("server/pcapp/CanvasFrameLink.json", meta))
    else:
        print("note: pc/dist/CanvasFrameLink.exe not found: the dashboard won't offer CanvasFrame Link")
    return out


def pc_version():
    """The app's version from its project file (same number as the exe)."""
    import re
    with open(os.path.join(REPO, "pc", "CanvasFrameLink.csproj"), encoding="utf-8") as f:
        m = re.search(r"<Version>([^<]+)</Version>", f.read())
    return m.group(1) if m else ""


def tgz(build):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz", format=tarfile.GNU_FORMAT) as tar:
        build(tar)
    return buf.getvalue()


def ar(members):
    out = io.BytesIO()
    out.write(b"!<arch>\n")
    for name, data in members:
        head = "%-16s%-12d%-6d%-6d%-8s%-10d`\n" % (name, int(time.time()), 0, 0, "100644", len(data))
        out.write(head.encode("ascii"))
        out.write(data)
        if len(data) % 2:
            out.write(b"\n")
    return out.getvalue()


def main():
    info = json.load(open(os.path.join(ROOT, "app", "appinfo.json"), encoding="utf-8"))
    package(APP_ID, info, files(), "CanvasFrame home screen and dashboard")
    # The doorbell card over other apps and inputs: a second, tiny app with an overlay window (webos/door).
    door = json.load(open(os.path.join(ROOT, "door", "appinfo.json"), encoding="utf-8"))
    door["version"] = info["version"]
    meta = os.path.join(BUILD_TMP, "door-appinfo.json")
    os.makedirs(BUILD_TMP, exist_ok=True)
    with open(meta, "w", encoding="utf-8") as f:
        json.dump(door, f, indent=2)
    pairs = [("appinfo.json", meta)] + [(n, os.path.join(ROOT, "door", n)) for n in sorted(os.listdir(os.path.join(ROOT, "door")))
                                        if n != "appinfo.json"]
    package(door["id"], door, pairs, "CanvasFrame doorbell card over other apps")


def package(APP_ID, info, pairs, description):
    version = info["version"]
    app_dir = "./usr/palm/applications/%s/" % APP_ID
    size = 0

    def data(tar):
        nonlocal size
        made = set()
        for d in ("./usr/", "./usr/palm/", "./usr/palm/applications/", app_dir, "./usr/palm/packages/",
                  "./usr/palm/packages/%s/" % APP_ID):
            add_dir(tar, d)
            made.add(d)
        for rel, full in pairs:
            parts = rel.split("/")[:-1]
            for i in range(1, len(parts) + 1):
                d = app_dir + "/".join(parts[:i]) + "/"
                if d not in made:
                    add_dir(tar, d)
                    made.add(d)
            blob = open(full, "rb").read()
            size += len(blob)
            add_bytes(tar, app_dir + rel, blob, 0o755 if rel.endswith(".sh") else 0o644)
        pkg = {"app": APP_ID, "id": APP_ID, "loc_name": info["title"], "package_format_version": 2,
               "vendor": info.get("vendor", ""), "version": version}
        add_bytes(tar, "./usr/palm/packages/%s/packageinfo.json" % APP_ID, json.dumps(pkg, indent=2).encode())

    data_tgz = tgz(data)
    control = ("Package: %s\nVersion: %s\nSection: misc\nPriority: optional\nArchitecture: all\n"
               "Installed-Size: %d\nMaintainer: CanvasFrame\nDescription: %s\n"
               "webOS-Package-Format-Version: 2\nwebOS-Packager-Version: 1.0.0\n") % (
        APP_ID, version, size, description)
    control_tgz = tgz(lambda tar: add_bytes(tar, "./control", control.encode()))
    out_dir = os.path.join(ROOT, "build")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "%s_%s_all.ipk" % (APP_ID, version))
    with open(out, "wb") as f:
        f.write(ar([("debian-binary", b"2.0\n"), ("control.tar.gz", control_tgz), ("data.tar.gz", data_tgz)]))
    print("Built %s (%d files, %d KB)" % (out, len(pairs), os.path.getsize(out) // 1024))


if __name__ == "__main__":
    main()
