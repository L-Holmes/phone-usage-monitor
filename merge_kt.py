#!/usr/bin/env python3
"""
merge_kt.py  -  Consolidate the Web Traffic Monitor main sources into one Main.kt.

Run from the repo root:  python3 merge_kt.py
Flags:  --keep   (don't move the originals out; just write Main.kt)
        --dry    (show what would happen, write/delete nothing)

What it does, mechanically (so the code is never retyped):
  * Merges every main-source .kt into app/.../webtrafficmonitor/Main.kt
  * Drops each file's `package` line and every SELF-referential import
    (anything under com.example.webtrafficmonitor.*) -- those classes now live
    in the same package, so importing them would break the build.
  * Hoists, de-dupes and sorts the remaining (framework) imports.
  * Lays out major sections (former sub-folders) and subsections (former files)
    with your banner style, using // because # is not a Kotlin comment.
  * Rewrites AndroidManifest.xml so flattened services lose their sub-package
    segment (e.g. .monitor.ScreenCaptureService -> .ScreenCaptureService).
  * Warns about any duplicate top-level declarations that would now collide.
  * Leaves the androidTest source set completely alone.
"""
import argparse
import pathlib
import re
import shutil
import sys
from datetime import datetime

BASE_PKG = "com.example.webtrafficmonitor"
MAIN_SRC = pathlib.Path("app/src/main/java/com/example/webtrafficmonitor")
MANIFEST = pathlib.Path("app/src/main/AndroidManifest.xml")
OUT = MAIN_SRC / "Main.kt"

# (sub-folder relative to MAIN_SRC, section banner).  "" = files in the root package.
# Reorder freely; declaration order across the file is cosmetic in Kotlin.
SECTIONS = [
    ("",        "APP"),
    ("data",    "DATA"),
    ("monitor", "MONITOR"),
    ("block",   "BLOCK"),
    ("ui",      "UI"),
]

EQ = "=" * 85
DASH = "-" * 62

# top-level declaration (column 0, not indented) -> capture its name
DECL = re.compile(
    r"^(?:public |internal |private |abstract |open |sealed |data |enum )*"
    r"(?:class|object|interface|fun|val|const val|typealias)\s+([A-Za-z_]\w*)"
)


def major(name):  return f"// {EQ}\n// {name}\n// {EQ}\n"
def minor(name):  return f"// {DASH}\n// {name}\n// {DASH}\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="leave originals in place")
    ap.add_argument("--dry", action="store_true", help="don't write or delete anything")
    args = ap.parse_args()

    if not MAIN_SRC.is_dir():
        sys.exit(f"Run me from the repo root. Not found: {MAIN_SRC}")

    imports = set()
    sections_out = []
    consumed = []                 # originals we'll move aside
    decls = {}                    # name -> [files] for collision check
    renames = {}                  # ".sub.Class" -> ".Class" for the manifest

    for subdir, section in SECTIONS:
        folder = MAIN_SRC if subdir == "" else MAIN_SRC / subdir
        if not folder.is_dir():
            continue
        files = sorted(p for p in folder.glob("*.kt") if p.resolve() != OUT.resolve())
        if not files:
            continue

        parts = [major(section)]
        for f in files:
            consumed.append(f)
            if subdir:                                  # this class is being flattened
                stem = f.stem
                renames[f".{subdir}.{stem}"] = f".{stem}"
                renames[f"{BASE_PKG}.{subdir}.{stem}"] = f"{BASE_PKG}.{stem}"

            kept = []
            for ln in f.read_text().splitlines():
                s = ln.strip()
                if s.startswith("package "):
                    continue
                if s.startswith("import "):
                    target = s[len("import "):].strip()
                    if not target.startswith(BASE_PKG + "."):   # keep only framework imports
                        imports.add(s)
                    continue
                kept.append(ln)
                m = DECL.match(ln)
                if m:
                    decls.setdefault(m.group(1), []).append(f.name)
            while kept and not kept[0].strip(): kept.pop(0)
            while kept and not kept[-1].strip(): kept.pop()
            parts.append(minor(f.stem))
            parts.append("\n".join(kept))
        sections_out.append("\n\n".join(parts))

    header = (
        "// NOTE: This whole module is intentionally kept in ONE file.\n"
        "// These classes would normally live in separate files / sub-packages;\n"
        "// they are consolidated here on purpose to make development easier.\n"
        "// Major sections (// ===) mark what used to be sub-folders;\n"
        "// subsections (// ---) mark what used to be separate files.\n"
        "// Regenerate with merge_kt.py -- do not re-split by hand.\n"
    )
    doc = "\n".join([
        f"package {BASE_PKG}", "",
        "\n".join(sorted(imports)), "",
        header,
        "\n\n".join(sections_out), "",
    ])

    # ---- collision warning -------------------------------------------------
    clashes = {n: fs for n, fs in decls.items() if len(fs) > 1}
    if clashes:
        print("!! Duplicate top-level names -- these will collide in one file:")
        for n, fs in sorted(clashes.items()):
            print(f"     {n}  <-  {', '.join(fs)}")
        print("   (rename or move them into a companion object before building)\n")

    # ---- manifest rewrite --------------------------------------------------
    manifest_changes = []
    if MANIFEST.is_file():
        text = MANIFEST.read_text()
        new = text
        for old, repl in sorted(renames.items(), key=lambda kv: -len(kv[0])):
            if old in new:
                new = new.replace(old, repl)
                manifest_changes.append(f"{old}  ->  {repl}")
    else:
        new = None

    # ---- act ---------------------------------------------------------------
    if args.dry:
        print(f"[dry] would write {OUT} ({len(doc.splitlines())} lines)")
        for c in manifest_changes: print(f"[dry] manifest: {c}")
        for f in consumed:         print(f"[dry] would move aside: {f}")
        return

    OUT.write_text(doc)
    print(f"Wrote {OUT}  ({len(doc.splitlines())} lines)")

    if new is not None and new != MANIFEST.read_text():
        MANIFEST.write_text(new)
        for c in manifest_changes: print(f"Manifest: {c}")
    elif MANIFEST.is_file():
        print("Manifest: no sub-package names found to change.")

    if not args.keep:
        backup = pathlib.Path(f"/tmp/wtm-merge-backup-{datetime.now():%Y%m%d-%H%M%S}")
        for f in consumed:
            if f.resolve() == OUT.resolve():
                continue
            dest = backup / f.relative_to(MAIN_SRC)
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(f), str(dest))
        print(f"Moved {len(consumed)} originals to {backup} (recoverable).")
        print("If it builds, you can delete that backup. git users: just `git status`.")
    else:
        print("--keep: originals left in place (you'll have duplicate class defs; "
              "delete them once Main.kt looks right).")


if __name__ == "__main__":
    main()
