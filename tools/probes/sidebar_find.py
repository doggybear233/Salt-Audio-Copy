import zipfile, sys, re
sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
APP = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
z = zipfile.ZipFile(APP)
names = [n for n in z.namelist() if n.endswith(".class")]
cands = []
for n in names:
    d = z.read(n)
    has_lib = b"MusicLibrary" in d
    has_set = b"Settings" in d
    has_rail = (b"NavigationRail" in d) or (b"NavigationDrawer" in d) or (b"NavigationBar" in d)
    if has_lib and has_set:
        cands.append((n, has_rail, len(d)))
print("classes with both MusicLibrary+Settings:", len(cands))
for n, rail, ln in cands:
    print(f"   {ln:7d} {'RAIL' if rail else '    '}  {n}")
