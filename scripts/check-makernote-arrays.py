#!/usr/bin/env python3
"""Usage: scripts/check-makernote-arrays.py <kiroku-cli> <exiftool> <files...>

Checks MakerNote binary arrays (Canon CameraSettings, ShotInfo, ...) element by element: for every element ExifTool's
verbose output lists (array, index, raw value), Kiroku must have group MakerNotes:<array> with that index as its tag ID,
the same name and the same stored value. Elements Kiroku lists that ExifTool does not are reported too."""
import json, re, subprocess, sys, collections
B, E = sys.argv[1], sys.argv[2]
ok = 0; problems = collections.Counter(); examples = {}
def note(key, f):
    problems[key] += 1; examples.setdefault(key, f)
for f in sys.argv[3:]:
    lines = subprocess.run([E, "-v3", "-u", f], capture_output=True, text=True, errors="replace").stdout.splitlines()
    et = {}; current = None; pending = None
    for i, line in enumerate(lines):
        sd = re.match(r"^[\s|]*\d+\)\s+(\w+) \(SubDirectory\)", line)
        if sd: pending = sd.group(1); continue
        if re.match(r"^[\s|]*\+ \[BinaryData directory", line): current = pending; continue
        if re.match(r"^[\s|]*\+ \[", line): current = None; continue
        m = re.match(r"^[\s|]*([A-Za-z][\w-]*) = (.*)$", line)
        if current and m and i + 1 < len(lines):
            t = re.search(r"- Tag (0x[0-9a-f]{4})", lines[i + 1])
            if t: et[(current, int(t.group(1), 16))] = (m.group(1), m.group(2).strip())
    out = json.loads(subprocess.run([B, "--full", "--no-bytes", "--no-external", f], capture_output=True, text=True).stdout or "{}")
    mine = {}
    for o in out.get("full", {}).get("observations", []):
        g = o["groupPath"]
        if g[0] == "MakerNotes" and o.get("tagId") is not None and "/" + g[1] in (o.get("sourcePath") or ""):
            mine[(g[1], o["tagId"])] = (o["tagName"], str(o["value"].get("value")))
    arrays = {a for a, _ in mine}
    for (a, idx), (name, val) in et.items():
        if a not in arrays: continue
        m = mine.get((a, idx))
        if m is None: note(f"{a}[{idx}] {name}: ExifTool has it, Kiroku does not", f); continue
        if m[0] != name: note(f"{a}[{idx}] name: exiftool={name} kiroku={m[0]}", f); continue
        if m[1] != val.split()[0] if val else m[1] != val: note(f"{a}[{idx}] {name} value: exiftool={val[:30]} kiroku={m[1]}", f); continue
        ok += 1
    for (a, idx), (name, val) in mine.items():
        if (a, idx) not in et and any(k[0] == a for k in et): note(f"{a}[{idx}] {name}: Kiroku has it, ExifTool does not", f)
print(f"elements agree: {ok}; problems: {sum(problems.values())} ({len(problems)} distinct)")
for k, n in sorted(problems.items()): print(f"  {k}   x{n}  e.g. {examples[k].split('/')[-1]}")
