"""List host UI/workshop classes from the jpackage classpath (zip appended to ffmpeg-x64.dll)."""
import zipfile, sys, re, struct

sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
APP = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"


def constant_pool_strings(data):
    """Parse a class file constant pool and return UTF8 strings."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return []
    n = struct.unpack_from(">H", data, 8)[0]
    out = []
    i = 10
    idx = 1
    while idx < n:
        tag = data[i]
        i += 1
        if tag == 1:  # Utf8
            ln = struct.unpack_from(">H", data, i)[0]
            i += 2
            out.append(data[i:i + ln].decode("utf-8", "replace"))
            i += ln
        elif tag in (7, 8, 16, 19, 20):
            i += 2
        elif tag in (15,):
            i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 4
        elif tag in (5, 6):
            i += 8
            idx += 1
        else:
            break
        idx += 1
    return out


z = zipfile.ZipFile(APP)
names = [n for n in z.namelist() if n.endswith(".class")]
print(f"total classes: {len(names)}")

groups = {
    "workshop/api": r"^(api|com/xuncorp/spw)/",
    "voxzen ui": r"^com/xuncorp/voxzen/(ui|screen|page|navigation)/",
    "voxzen other": r"^com/xuncorp/voxzen/(?!ui|screen|page|navigation)",
    "pf4j": r"^org/pf4j/",
}
for label, pat in groups.items():
    sel = [n for n in names if re.search(pat, n)]
    print(f"\n## {label}: {len(sel)}")
    for n in sorted(sel)[:80]:
        print("   ", n)
    if len(sel) > 80:
        print(f"    ... {len(sel) - 80} more")

# find any class mentioning plugin screens / routes
print("\n## classes whose names look like mod/workshop UI")
mod = [n for n in names if re.search(r"(Mod|Workshop|Plugin)", n, re.I)]
for n in sorted(mod):
    print("   ", n)

print("\n## strings in a few key classes")
for target in [n for n in names if n.endswith(("ScreenRoute.class", "RepoService.class"))][:4]:
    print(f"\n-- {target}")
    try:
        strs = constant_pool_strings(z.read(target))
    except Exception as e:
        print("   err", e)
        continue
    for s in strs:
        if 3 < len(s) < 80:
            print("   ", s)
