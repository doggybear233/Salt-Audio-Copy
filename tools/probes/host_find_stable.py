"""Find host classes with readable (non-obfuscated) names, esp. mod/config/workshop UI."""
import zipfile, sys, struct, re

sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
APP = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
z = zipfile.ZipFile(APP)
names = [n for n in z.namelist() if n.endswith(".class")]


def readable(s):
    # obfuscated names use CJK/greek/symbol glyphs; readable ones are plain ASCII identifiers
    return all(ord(c) < 128 for c in s)


cands = []
for n in names:
    if not n.startswith("com/xuncorp/voxzen/"):
        continue
    base = n.rsplit("/", 1)[-1].replace(".class", "")
    parts = [p for p in n.split("/")[:-1]]
    if not readable(base) or not all(readable(p) for p in parts):
        continue
    if re.search(r"(Mod|Config|Workshop|Plugin|Setting|Preference|Screen|Route|Nav)", n, re.I):
        cands.append(n)

print(f"readable mod/config/workshop-ish classes: {len(cands)}")
for n in sorted(cands):
    print("  ", n)

# also: which classes contain the string "ModConfigContent"?
print("\n## classes whose constant pool contains 'ModConfigContent' / 'workshop'")
for n in names:
    if not n.startswith("com/xuncorp/voxzen/"):
        continue
    try:
        data = z.read(n)
    except Exception:
        continue
    for probe in (b"ModConfigContent", b"preference_config", b"WorkshopApi"):
        if probe in data:
            print(f"   {n}  <- {probe.decode()}")
            break
