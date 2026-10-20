import io, glob, re

files = glob.glob(r'D:\deepseek\workspace\projects\salt-rip\mod\src\test\kotlin\com\saltrip\rip\*LiveTest.kt')
for p in files:
    s = io.open(p, encoding='utf-8').read()
    if 'HwGate.assumeMedia()' in s:
        continue
    s = re.sub(r'(fun \w+\(\) \{\n)', r'\1        HwGate.assumeMedia()\n', s)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('gated', p.split('\\')[-1])
