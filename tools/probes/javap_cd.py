import zipfile, os, subprocess, sys

ZIP = r'D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll'
JAVAP = r'D:\dev\jdk-25\bin\javap.exe'
z = zipfile.ZipFile(ZIP)
os.makedirs('out/host', exist_ok=True)

names = ['androidx/compose/ui/\u045b.class',
         'com/xuncorp/voxzen/ui/navigation/AppNavigation_desktopKt$appNavigationBuilder$$inlined$entry$default$69.class']
for n in names:
    data = z.read(n)
    p = 'out/host/' + n.replace('/', '_')
    open(p, 'wb').write(data)
    print('=== ' + n)
    r = subprocess.run([JAVAP, '-p', '-s', p], capture_output=True, text=True, errors='replace')
    print(r.stdout[:4000])
