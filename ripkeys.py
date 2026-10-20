import zipfile, sys, re
sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
APP = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
z = zipfile.ZipFile(APP)
names = [n for n in z.namelist() if n.endswith(".class")]
pats = [b"cd_rip", b"CD_RIP", b"ripCd", b"cdda_read", b"basscd", b"BassCd", b"CdRip", b"cdRip", b"ripping", b"Ripping"]
for p in pats:
    hit = []
    for n in names:
        d = z.read(n)
        if p in d:
            hit.append(n)
    print(f"{p.decode()}: {len(hit)}")
    for n in hit[:12]:
        print("    ", n)
