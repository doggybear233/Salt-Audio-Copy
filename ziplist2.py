import zipfile, sys, re

sys.stdout.reconfigure(encoding="utf-8")
p = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
z = zipfile.ZipFile(p)
names = z.namelist()

def show(title, pattern, limit=40):
    rx = re.compile(pattern, re.I)
    hits = [n for n in names if rx.search(n)]
    print(f"\n=== {title}: {len(hits)} ===")
    for n in hits[:limit]:
        print("  ", n)

show("pf4j", r"^org/pf4j")
show("JNA", r"^com/sun/jna|^net/java/dev/jna")
show("workshop api", r"^com/xuncorp/spw/workshop")
show("host plugin service", r"voxzen/.*(plugin|mod)", 60)
show("library scan", r"voxzen/.*(scan|import|library)", 60)
show("jna in lib", r"jna")
