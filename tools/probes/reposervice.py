import zipfile, re, sys

sys.stdout.reconfigure(encoding="utf-8")
p = r"D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
z = zipfile.ZipFile(p)

names = [n for n in z.namelist() if n.startswith("com/xuncorp/voxzen/service/RepoService")]
print("RepoService classes:", len(names))
for n in names[:30]:
    print("  ", n)

# crude: pull UTF8-ish strings from the top-level RepoService class
target = "com/xuncorp/voxzen/service/RepoService.class"
if target in z.namelist():
    data = z.read(target)
    strings = set(re.findall(rb"[\x20-\x7e]{4,64}", data))
    cand = sorted(s.decode("ascii") for s in strings)
    interesting = [s for s in cand if re.search(r"(?i)rebuild|scan|import|folder|watch|refresh|index|add", s)]
    print(f"\n--- interesting constants in RepoService ({len(cand)} total) ---")
    for s in interesting[:80]:
        print("  ", s)
