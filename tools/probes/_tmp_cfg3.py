import zipfile
HOST = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
zf = zipfile.ZipFile(HOST)
pats = [b"entry_values", b"on_click", b"preferences", b"configs"]
res = {p: [] for p in pats}
for n in zf.namelist():
    if not n.endswith(".class"): continue
    try: b = zf.read(n)
    except Exception: continue
    for p in pats:
        if p in b:
            res[p].append(n)
for p in pats:
    print(p, len(res[p]))
    for n in res[p][:12]:
        print("   ", n)
