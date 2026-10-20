"""Resolve which screen composable each nav-entry lambda in ComposableSingletons$AppNavigation_desktopKt invokes.

Scans the Code attribute of every lambda_* method for invokestatic/invokevirtual/invokespecial
and reports the resolved owner class + method for the FIRST call whose owner is not
androidx/compose/runtime internals.
"""
import zipfile, struct, sys

sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
APP = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
TARGET = "com/xuncorp/voxzen/ui/navigation/ComposableSingletons$AppNavigation_desktopKt.class"


def parse_cp(data):
    n = struct.unpack_from(">H", data, 8)[0]
    cp = {}
    i = 10
    idx = 1
    while idx < n:
        tag = data[i]; i += 1
        if tag == 1:
            ln = struct.unpack_from(">H", data, i)[0]; i += 2
            cp[idx] = ("utf8", data[i:i + ln].decode("utf-8", "replace")); i += ln
        elif tag in (7, 8, 16, 19, 20):
            cp[idx] = ("ref1", struct.unpack_from(">H", data, i)[0]); i += 2
        elif tag == 15:
            cp[idx] = ("ref3", None, struct.unpack_from(">H", data, i)[0]); i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            cp[idx] = ("ref2", struct.unpack_from(">HH", data, i)); i += 4
        elif tag in (5, 6):
            i += 8; idx += 1
        else:
            raise SystemExit(f"bad tag {tag} at {i}")
        idx += 1
    return cp


def class_name(cp, idx):
    kind, v = cp[idx][0], cp[idx][1]
    if kind == "ref1":
        u = cp[v][1]
        return cp[u][1]
    return "?"


def member(cp, idx):
    tag, a, b = cp[idx]
    owner = class_name(cp, a)
    nat = cp[b][1]
    name = cp[nat[0]][1]
    desc = cp[nat[1]][1]
    return owner, name, desc


data = zipfile.ZipFile(APP).read(TARGET)
cp = parse_cp(data)

# class attributes
pos = 10
n = struct.unpack_from(">H", data, 8)[0]
i = pos
idx = 1
while idx < n:
    tag = data[i]; i += 1
    if tag == 1:
        ln = struct.unpack_from(">H", data, i)[0]; i += 2 + ln
    elif tag in (7, 8, 16, 19, 20):
        i += 2
    elif tag == 15:
        i += 3
    elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
        i += 4
    elif tag in (5, 6):
        i += 8; idx += 1
    idx += 1

i += 6  # access, this, super
ifc = struct.unpack_from(">H", data, i)[0]; i += 2 + 2 * ifc
nf = struct.unpack_from(">H", data, i)[0]; i += 2
for _ in range(nf):
    i += 6
    ac = struct.unpack_from(">H", data, i)[0]; i += 2
    for _ in range(ac):
        an = struct.unpack_from(">H", data, i)[0]
        ln = struct.unpack_from(">I", data, i + 2)[0]
        i += 6 + ln
nm = struct.unpack_from(">H", data, i)[0]; i += 2
methods = []
for _ in range(nm):
    acc, name_i, desc_i = struct.unpack_from(">HHH", data, i); i += 6
    name = cp[name_i][1]; desc = cp[desc_i][1]
    ac = struct.unpack_from(">H", data, i)[0]; i += 2
    code = None
    for _ in range(ac):
        an = struct.unpack_from(">H", data, i)[0]
        ln = struct.unpack_from(">I", data, i + 2)[0]
        body = data[i + 6:i + 6 + ln]
        if cp[an][1] == "Code":
            clen = struct.unpack_from(">I", body, 4)[0]
            code = body[8:8 + clen]
        i += 6 + ln
    methods.append((name, desc, code))

out = []
for name, desc, code in methods:
    if not name.startswith("lambda_") or code is None:
        continue
    route = ""
    hits = []
    j = 0
    while j < len(code):
        op = code[j]
        if op in (0xB8, 0xB6, 0xB7, 0xB9):  # invokestatic/virtual/special/interface
            tgt = struct.unpack_from(">H", code, j + 1)[0]
            try:
                owner, mname, mdesc = member(cp, tgt)
            except Exception:
                owner, mname = "?", "?"
            if "com/xuncorp" in owner or ("androidx/compose/ui/" in owner and owner.count("/") == 3):
                hits.append(f"{owner}.{mname}")
            j += 3
        elif op == 0x12:
            j += 2
        elif op == 0x13 or op == 0x14:
            j += 3
        elif op == 0x11:  # sipush
            j += 3
        elif op in (0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC):
            j += 2
        elif op in (0x10, 0x11, 0x12, 0x13, 0x14):
            j += 2
        elif 0x99 <= op <= 0xA8:
            j += 3
        elif op in (0xC6, 0xC7):
            j += 3
        elif op in (0xC4,):
            j += 4
        else:
            j += 1
    out.append((name, hits))

import re
for name, hits in out:
    m = re.search(r"lambda_?_?(\d+)", name)
    route_hint = ""
    print(f"{name}")
    for h in hits[:4]:
        print("     ->", h)
