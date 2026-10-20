import zipfile, struct, sys, re
sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
APP = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
TARGET = "com/xuncorp/voxzen/ui/navigation/ComposableSingletons$AppNavigation_desktopKt.class"

data = zipfile.ZipFile(APP).read(TARGET)

def parse_cp(data):
    n = struct.unpack_from(">H", data, 8)[0]
    cp = {}; i = 10; idx = 1
    while idx < n:
        tag = data[i]; i += 1
        if tag == 1:
            ln = struct.unpack_from(">H", data, i)[0]; i += 2
            cp[idx] = ("utf8", data[i:i+ln].decode("utf-8","replace")); i += ln
        elif tag in (7,8,16,19,20): cp[idx] = ("c", struct.unpack_from(">H", data, i)[0]); i += 2
        elif tag == 15: cp[idx] = ("m15", struct.unpack_from(">H", data, i)[0]); i += 3
        elif tag in (3,4,9,10,11,12,17,18): cp[idx] = ("p", struct.unpack_from(">HH", data, i)); i += 4
        elif tag in (5,6): i += 8; idx += 1
        else: break
        idx += 1
    return cp

cp = parse_cp(data)

def cname(i):
    e = cp.get(i)
    if not e: return "?"
    if e[0] == "c":
        u = cp.get(e[1])
        return u[1] if u else "?"
    return "?"

def meth(i):
    e = cp.get(i)
    if not e or e[0] != "p": return ("?","?","?")
    owner = cname(e[1][0])
    nat = cp.get(e[1][1])
    if not nat: return (owner,"?","?")
    return (owner, cp.get(nat[1][0],("","?"))[1], cp.get(nat[1][1],("","?"))[1])

# walk class structure
i = 10; n = struct.unpack_from(">H", data, 8)[0]; idx = 1
while idx < n:
    tag = data[i]; i += 1
    if tag == 1:
        ln = struct.unpack_from(">H", data, i)[0]; i += 2 + ln
    elif tag in (7,8,16,19,20): i += 2
    elif tag == 15: i += 3
    elif tag in (3,4,9,10,11,12,17,18): i += 4
    elif tag in (5,6): i += 8; idx += 1
    idx += 1
i += 6
ifc = struct.unpack_from(">H", data, i)[0]; i += 2 + 2*ifc
nf = struct.unpack_from(">H", data, i)[0]; i += 2
for _ in range(nf):
    i += 6
    ac = struct.unpack_from(">H", data, i)[0]; i += 2
    for _ in range(ac):
        ln = struct.unpack_from(">I", data, i+2)[0]; i += 6 + ln
nm = struct.unpack_from(">H", data, i)[0]; i += 2
methods = []
for _ in range(nm):
    acc, ni, di = struct.unpack_from(">HHH", data, i); i += 6
    name = cp[ni][1]; desc = cp[di][1]
    ac = struct.unpack_from(">H", data, i)[0]; i += 2
    code = None
    for _ in range(ac):
        an = struct.unpack_from(">H", data, i)[0]
        ln = struct.unpack_from(">I", data, i+2)[0]
        body = data[i+6:i+6+ln]
        if cp[an][1] == "Code":
            clen = struct.unpack_from(">I", body, 4)[0]
            code = body[8:8+clen]
        i += 6 + ln
    methods.append((name, desc, code))

for name, desc, code in methods:
    if "lambda_926755007" not in name or code is None: continue
    print("METHOD", name, desc, "len", len(code))
    owners = {}
    for j in range(len(code)-2):
        if code[j] in (0xB6,0xB7,0xB8,0xB9):
            t = struct.unpack_from(">H", code, j+1)[0]
            o, mn, md = meth(t)
            if o.startswith("com/xuncorp") or re.match(r"^androidx/compose/ui/.[^/]*$", o):
                owners.setdefault((o,mn), 0)
                owners[(o,mn)] += 1
    for (o,mn), c in owners.items():
        print(f"   {c:2d}x {o}.{mn}")
