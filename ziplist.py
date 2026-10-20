import zipfile, sys, collections

p = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
try:
    z = zipfile.ZipFile(p)
except Exception as e:
    print("NOT ZIP:", e)
    sys.exit()

names = z.namelist()
print("entries:", len(names))
tops = collections.Counter()
for n in names:
    parts = n.split("/")
    tops["/".join(parts[:2]) if n.count("/") >= 1 else parts[0]] += 1
for k, v in tops.most_common(25):
    print(f"{v:6d}  {k}")

hits = [n for n in names if "workshop" in n.lower() or "plugin" in n.lower()]
print("--- plugin/workshop hits:", len(hits))
for n in hits[:60]:
    print("  ", n)
