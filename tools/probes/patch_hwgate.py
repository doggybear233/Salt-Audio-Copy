import io, glob

DRIVE = r'\\.\F:'
files = glob.glob(r'D:\deepseek\workspace\projects\salt-rip\mod\src\test\kotlin\com\saltaudiocopy\rip\*LiveTest.kt')
for p in files:
    s = io.open(p, encoding='utf-8').read()
    if 'HwGate' in s:
        continue
    # 在每个 Test 方法体第一行前插入媒体闸门
    s = s.replace('    @Test\n', '    @Test\n')
    if 'import com.doggybear233.saltaudiocopy.HwGate' not in s:
        s = s.replace('import org.junit.jupiter.api.Assumptions.assumeTrue',
                      'import com.doggybear233.saltaudiocopy.HwGate\nimport org.junit.jupiter.api.Assumptions.assumeTrue')
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('touched', p.split('\\')[-1])
