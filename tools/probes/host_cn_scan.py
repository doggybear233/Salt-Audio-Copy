"""Search all class files for Chinese UI strings to identify the host CD screen's feature set."""
import zipfile, sys

sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
APP = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
z = zipfile.ZipFile(APP)
names = [n for n in z.namelist() if n.endswith(".class")]

words = ["抓轨", "刻录", "光盘", "音轨", "提取", "翻录", "CD 文本", "CD文本", "读取光盘", "驱动器", "光驱"]
found = {}
for n in names:
    try:
        data = z.read(n)
    except Exception:
        continue
    for w in words:
        if w.encode("utf-8") in data:
            found.setdefault(w, []).append(n)

for w in words:
    lst = found.get(w, [])
    print(f"\n=== {w}: {len(lst)}")
    for n in lst[:15]:
        print("   ", n)
