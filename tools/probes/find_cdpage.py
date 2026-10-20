import zipfile, sys

ZIP = r'D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll'
needle = b'ScreenRoute$CDToolkit'

z = zipfile.ZipFile(ZIP)
hits = []
for n in z.namelist():
    if not n.endswith('.class'):
        continue
    b = z.read(n)
    if needle in b:
        hits.append(n)

print(len(hits))
for h in hits[:20]:
    print(h)
