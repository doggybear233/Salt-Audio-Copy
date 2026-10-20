import io, glob, re, subprocess

root = r'D:\deepseek\workspace\projects\salt-rip\mod\src\test\kotlin\com\saltrip\rip'
changed = []
for p in glob.glob(root + r'\*.kt'):
    s = io.open(p, encoding='utf-8').read()
    if 'CdDevice(' not in s:
        continue
    if 'HwGate.assumeMedia()' not in s:
        s = re.sub(r'(fun \w+\(\) \{\n)', r'\1        HwGate.assumeMedia()\n', s)
        if 'import com.saltrip.rip.HwGate' not in s and 'package com.saltrip.rip' in s:
            pass  # 同包，无需 import
        io.open(p, 'w', encoding='utf-8', newline='').write(s)
        changed.append(p.split('\\')[-1])
print('gated:', changed)

# 杀掉可能持有设备的旧 JVM
ps = subprocess.run(['powershell', '-NoProfile', '-Command',
                     "Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.StartTime -lt (Get-Date).AddMinutes(-2) } | ForEach-Object { try { Stop-Process -Id $_.Id -Force } catch {} }"],
                    capture_output=True, text=True)
print('cleanup done')
