"""Dump methods of arbitrary host classes (by exact entry name) incl. composable lambdas."""
import zipfile, sys, struct, re

sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
APP = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"


def parse_class(data):
    n = struct.unpack_from(">H", data, 8)[0]
    cp = [None] * n
    i = 10
    idx = 1
    while idx < n:
        tag = data[i]; i += 1
        if tag == 1:
            ln = struct.unpack_from(">H", data, i)[0]; i += 2
            cp[idx] = data[i:i + ln].decode("utf-8", "replace"); i += ln
        elif tag == 7:
            cp[idx] = ("Class", struct.unpack_from(">H", data, i)[0]); i += 2
        elif tag == 8:
            cp[idx] = ("String", struct.unpack_from(">H", data, i)[0]); i += 2
        elif tag == 16:
            cp[idx] = ("MethodType", struct.unpack_from(">H", data, i)[0]); i += 2
        elif tag in (19, 20):
            i += 2
        elif tag == 15:
            i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 4
        elif tag in (5, 6):
            i += 8; idx += 1
        else:
            raise ValueError(tag)
        idx += 1
    p = i + 6
    ifc = struct.unpack_from(">H", data, p)[0]
    p += 2 + 2 * ifc
    def members(p):
        cnt = struct.unpack_from(">H", data, p)[0]; p += 2
        out = []
        for _ in range(cnt):
            acc, ni, di, ac = struct.unpack_from(">HHHH", data, p); p += 8
            for _ in range(ac):
                alen = struct.unpack_from(">I", data, p + 2)[0]; p += 6 + alen
            out.append((acc, cp[ni], cp[di]))
        return p, out
    p, _ = members(p)
    p, methods = members(p)
    return cp, methods


z = zipfile.ZipFile(APP)
args = sys.argv[1:]
if not args:
    args = ["MainKt.class"]
for target in args:
    target = target.replace(".", "/")
    if not target.endswith(".class"):
        target += ".class"
    if target not in z.namelist():
        print(f"## {target} MISSING")
        continue
    cp, methods = parse_class(z.read(target))
    print(f"\n## {target}")
    strings = [s for s in cp if isinstance(s, str) and 2 < len(s) < 70]
    for acc, name, desc in methods:
        print(f"   {name}{desc}")
    print("   strings:", [s for s in strings if not s.startswith(("java/", "kotlin/", "androidx/"))][:40])
