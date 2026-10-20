"""Print constant-pool Class/Methodref/Fieldref references of one or more classes."""
import zipfile, sys, struct

sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
APP = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"


def parse(data):
    n = struct.unpack_from(">H", data, 8)[0]
    cp = [None] * n
    i = 10
    idx = 1
    raw = {}
    while idx < n:
        tag = data[i]; i += 1
        if tag == 1:
            ln = struct.unpack_from(">H", data, i)[0]; i += 2
            cp[idx] = data[i:i + ln].decode("utf-8", "replace"); i += ln
        elif tag == 7:
            cp[idx] = ("C", struct.unpack_from(">H", data, i)[0]); i += 2
        elif tag == 8:
            cp[idx] = ("S", struct.unpack_from(">H", data, i)[0]); i += 2
        elif tag == 9:
            cp[idx] = ("F",) + struct.unpack_from(">HH", data, i); i += 4
        elif tag == 10:
            cp[idx] = ("M",) + struct.unpack_from(">HH", data, i); i += 4
        elif tag == 11:
            cp[idx] = ("I",) + struct.unpack_from(">HH", data, i); i += 4
        elif tag == 12:
            cp[idx] = ("NT",) + struct.unpack_from(">HH", data, i); i += 4
        elif tag in (16, 19, 20):
            cp[idx] = ("X",); i += 2
        elif tag == 15:
            i += 3
        elif tag in (3, 4):
            i += 4
        elif tag in (17, 18):
            i += 4
        elif tag in (5, 6):
            i += 8; idx += 1
        else:
            raise ValueError(tag)
        idx += 1
    return cp


def name(cp, k):
    v = cp[k]
    return v if isinstance(v, str) else "?"


def cname(cp, k):
    v = cp[k]
    if isinstance(v, tuple) and v[0] == "C":
        return name(cp, v[1])
    return "?"


z = zipfile.ZipFile(APP)
for target in sys.argv[1:]:
    t = target.replace(".", "/")
    if not t.endswith(".class"):
        t += ".class"
    if t not in z.namelist():
        print(f"## {t} MISSING"); continue
    cp = parse(z.read(t))
    print(f"\n## {t}")
    refs_c, refs_m, refs_f = set(), set(), set()
    for v in cp:
        if not isinstance(v, tuple):
            continue
        if v[0] == "C":
            refs_c.add(cname(cp, v[1]))
        elif v[0] == "M":
            ci, nti = v[1], v[2]
            if isinstance(cp[nti], tuple) and cp[nti][0] == "NT":
                nm, de = name(cp, cp[nti][1]), name(cp, cp[nti][2])
                refs_m.add(f"{cname(cp, ci)}.{nm}{de}")
        elif v[0] == "F":
            ci, nti = v[1], v[2]
            if isinstance(cp[nti], tuple) and cp[nti][0] == "NT":
                refs_f.add(f"{cname(cp, ci)}.{name(cp, cp[nti][1])}")
    print("  classes:")
    for c in sorted(refs_c):
        print("   ", c)
    print("  methods:")
    for m in sorted(refs_m):
        print("   ", m)
