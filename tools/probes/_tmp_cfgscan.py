import zipfile
HOST = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
zf = zipfile.ZipFile(HOST)
names = [n for n in zf.namelist() if n.endswith(".class")]
print("classes:", len(names))
hits = []
for n in names:
    try:
        b = zf.read(n)
    except Exception:
        continue
    if b"preference_config" in b or b"preferenceConfig" in b:
        hits.append(n)
print("hits:", len(hits))
for h in hits[:40]:
    print(h)
