import zipfile, sys, re
sys.stdout.reconfigure(encoding="utf-8")
APP = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
z = zipfile.ZipFile(APP)
names = z.namelist()
res = [n for n in names if re.search(r"\.(json|properties|xml|txt|yaml|yml)$", n)]
print("resource-ish entries:", len(res))
for n in res[:60]:
    print("  ", n)
print("\n--- entries mentioning CD/rip in name ---")
for n in names:
    if re.search(r"(cd|disc|rip)", n, re.I) and not n.endswith(".class"):
        print("  ", n)
