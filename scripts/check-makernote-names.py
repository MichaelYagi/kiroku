#!/usr/bin/env python3
"""Usage: scripts/check-makernote-names.py <kiroku-cli> <exiftool> <files...>

Directory-aware MakerNote name check: ExifTool (directory, tag ID) -> name vs Kiroku (sourcePath leaf, tag ID) -> name."""
import json, re, subprocess, sys, collections
B, E = sys.argv[1], sys.argv[2]
def norm_dir(d):
    d = re.sub(r"^MakerNote(?!s$)", "", d)
    d = re.sub(r"IFD$", "", d)
    for p in ("Olympus", "Nikon", "Canon", "Sony", "Panasonic", "Pentax", "Samsung", "Casio", "FujiFilm"):
        if d.startswith(p) and d != p: d = d[len(p):]
    return d or "MakerNotes"
bad = {}; ok = 0
for f in sys.argv[3:]:
    v = subprocess.run([E, "-v3", "-u", f], capture_output=True, text=True, errors="replace").stdout.splitlines()
    et = {}; stack = []; pending = None
    for i, line in enumerate(v):
        depth = line.count("|")
        m = re.match(r"^[\s|]*\+ \[(\w+) directory", line)
        if m:
            name = m.group(1)
            if name == "MakerNotes" and pending and depth > 2: name = pending
            pending = None
            stack = [s for s in stack if s[0] < depth] + [(depth, name if name.startswith("MakerNote") or not stack else "MakerNote" + name)]; continue
        sd = re.match(r"^[\s|]*\d+\)\s+(\w+) \(SubDirectory\)", line)
        if sd: pending = sd.group(1)
        m = re.match(r"^[\s|]*\d+\)\s+(\w+)\s*(?:=|\(SubDirectory\))", line)
        if m and i + 1 < len(v):
            stack = [s for s in stack if s[0] < depth]
            m2 = re.search(r"- Tag (0x[0-9a-f]{4})", v[i + 1])
            names = [s[1] for s in stack]
            if m2 and any(n.startswith("MakerNote") for n in names) and "BinaryData" not in names[-1]:
                et.setdefault((norm_dir(names[-1]), int(m2.group(1), 16)), m.group(1))
    kk = json.loads(subprocess.run([B, "--full", "--no-bytes", "--no-external", f], capture_output=True, text=True).stdout or "{}")
    mine = {}
    for o in kk.get("full", {}).get("observations", []):
        if o["groupPath"][0] != "MakerNotes" or o.get("tagId") is None: continue
        leaf = (o.get("sourcePath") or "").split("/")
        # Kiroku paths end .../MakerNote/<Vendor>[/<SubIfd>]; the vendor IFD itself maps to ExifTool's "MakerNotes*" dir.
        d = leaf[-1] if len(leaf) >= 2 and leaf[-2] not in ("MakerNote",) else "MakerNotes"
        mine.setdefault((norm_dir(d), o["tagId"]), set()).add(o["tagName"])
    for (d, tid), name in et.items():
        dd = d if not d.startswith("MakerNote") else "MakerNotes"
        names = mine.get((dd, tid))
        if not names: continue
        if name in names: ok += 1
        elif all(n.startswith(("Tag0x", "0x")) for n in names): continue
        else: bad.setdefault((dd, tid, name, "/".join(sorted(names))), f.split("/")[-1])
print(f"agree {ok}, disagree {len(bad)}")
for (d, tid, name, names), ex in sorted(bad.items()):
    print(f"  {d:20} 0x{tid:04X} exiftool={name:30} kiroku={names:34} ({ex})")
