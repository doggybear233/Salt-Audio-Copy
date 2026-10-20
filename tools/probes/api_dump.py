"""Dump methods (name + descriptor) of the public workshop API classes."""
import zipfile, sys, struct

sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
APP = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"


def parse_class(data):
    assert data[:4] == b"\xca\xfe\xba\xbe"
    n = struct.unpack_from(">H", data, 8)[0]
    cp = [None] * n
    i = 10
    idx = 1
    while idx < n:
        tag = data[i]
        i += 1
        if tag == 1:
            ln = struct.unpack_from(">H", data, i)[0]
            i += 2
            cp[idx] = data[i:i + ln].decode("utf-8", "replace")
            i += ln
        elif tag == 7:
            cp[idx] = ("Class", struct.unpack_from(">H", data, i)[0]); i += 2
        elif tag == 8:
            cp[idx] = ("String", struct.unpack_from(">H", data, i)[0]); i += 2
        elif tag == 16:
            cp[idx] = ("MethodType", struct.unpack_from(">H", data, i)[0]); i += 2
        elif tag == 19:
            cp[idx] = ("Module", struct.unpack_from(">H", data, i)[0]); i += 2
        elif tag == 20:
            cp[idx] = ("Package", struct.unpack_from(">H", data, i)[0]); i += 2
        elif tag == 15:
            i += 3
        elif tag in (3, 4):
            i += 4
        elif tag in (9, 10, 11, 12, 17, 18):
            i += 4
        elif tag in (5, 6):
            i += 8
            idx += 1
        else:
            raise ValueError(f"bad tag {tag}")
        idx += 1
    # skip access, this, super, interfaces
    p = i + 6
    ifc = struct.unpack_from(">H", data, p)[0]
    p += 2 + 2 * ifc
    def skip_members(p):
        cnt = struct.unpack_from(">H", data, p)[0]
        p += 2
        out = []
        for _ in range(cnt):
            acc, ni, di, ac = struct.unpack_from(">HHHH", data, p)
            p += 8
            for _ in range(ac):
                alen = struct.unpack_from(">I", data, p + 2)[0]
                p += 6 + alen
            out.append((acc, cp[ni], cp[di]))
        return p, out
    p, fields = skip_members(p)
    p, methods = skip_members(p)
    return cp, methods


z = zipfile.ZipFile(APP)
targets = [
    "com/xuncorp/spw/workshop/api/WorkshopApi.class",
    "com/xuncorp/spw/workshop/api/WorkshopApi$Ui.class",
    "com/xuncorp/spw/workshop/api/WorkshopApi$Library.class",
    "com/xuncorp/spw/workshop/api/WorkshopApi$Manager.class",
    "com/xuncorp/spw/workshop/api/WorkshopApi$Playback.class",
    "com/xuncorp/spw/workshop/api/PluginContext.class",
    "com/xuncorp/spw/workshop/api/SpwPlugin.class",
    "com/xuncorp/spw/workshop/api/hook/HookRegistrar.class",
    "com/xuncorp/spw/workshop/api/hook/ComposableHook.class",
    "com/xuncorp/spw/workshop/api/hook/ContentHook.class",
    "com/xuncorp/spw/workshop/api/WorkshopPluginManager.class",
]
for t in targets:
    if t not in z.namelist():
        print(f"\n## {t} MISSING")
        continue
    cp, methods = parse_class(z.read(t))
    print(f"\n## {t}")
    for acc, name, desc in methods:
        if name and not name.startswith("access$"):
            print(f"   {'public ' if acc & 1 else '       '}{name}{desc}")

print("\n## any class mentioning 'Screen' in the API namespace")
for n in z.namelist():
    if n.startswith("com/xuncorp/spw/") and ("Screen" in n or "Ui" in n or "UI" in n):
        print("   ", n)
