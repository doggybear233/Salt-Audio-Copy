import zipfile, os, sys, shutil
HOST = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
OUT = r"D:\deepseek\workspace\projects\salt-rip\out\cdclass"
os.makedirs(OUT, exist_ok=True)
zf = zipfile.ZipFile(HOST)
names = [n for n in zf.namelist() if n.startswith("androidx/compose/ui/") and n.endswith(".class")]
# 找出与 CDToolkit 渲染相关的类：含 'KB/s' 之类格式串的已定位为 ݩ
targets = [n for n in names if "\u071d" in n or "\u071d" in n]
print("candidates:", len(targets))
for n in targets[:20]:
    print(repr(n))
    data = zf.read(n)
    p = os.path.join(OUT, "cls_%d.class" % (abs(hash(n)) % 10000))
    open(p, "wb").write(data)
    print("  ->", p, len(data))
