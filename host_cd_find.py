"""Find which host classes implement the CDToolkit screen and what CD features the host already has."""
import zipfile, sys, re, struct

sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
APP = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
z = zipfile.ZipFile(APP)
names = [n for n in z.namelist() if n.endswith(".class")]

probes = [b"CDToolkit", b"cdda", b"cddb", b"CDDA", b"AudioCD", b"audioCd", b"CD-ROM", b"cda"]
hits = {p: [] for p in probes}
for n in names:
    try:
        data = z.read(n)
    except Exception:
        continue
    for p in probes:
        if p in data:
            hits[p].append(n)

for p, lst in hits.items():
    print(f"\n=== {p.decode(errors='replace')}: {len(lst)} classes")
    for n in lst[:40]:
        print("   ", n)

print("\n=== candidate CD UI/data classes (readable names) ===")
for n in names:
    base = n.rsplit("/", 1)[-1]
    if re.search(r"^(CD|Cd|Disc|Toc|TOC)", base) and all(ord(c) < 128 for c in base):
        print("   ", n)
for n in names:
    if re.search(r"[/$](CD|Cd|Cdda|AudioCd)[A-Za-z]*\.class$", n):
        print("  *", n)
