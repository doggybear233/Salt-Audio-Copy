import zipfile, os
HOST = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
OUT = r"D:\deepseek\workspace\projects\salt-rip\out\cfg"
os.makedirs(OUT, exist_ok=True)
zf = zipfile.ZipFile(HOST)
data = zf.read("androidx/compose/ui/\u0936.class")
p = os.path.join(OUT, "cfg.class")
open(p,"wb").write(data)
print(p, len(data))
