"""离线核对面板挂载点：不运行宿主，只按宿主实现（ԩ.class）的匹配规则检查三个字符串是否正确。"""
import zipfile, struct, sys

ZIP = r'D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll'
z = zipfile.ZipFile(ZIP)

def parse_cp(b):
    # 返回 (cp 列表, 常量池结束偏移)
    n = struct.unpack_from('>H', b, 8)[0]
    cp = [None] * n
    i = 10
    k = 1
    while k < n:
        tag = b[i]; i += 1
        if tag == 1:
            ln = struct.unpack_from('>H', b, i)[0]; i += 2
            cp[k] = ('Utf8', b[i:i+ln].decode('utf-8', 'replace')); i += ln
        elif tag in (7, 8, 16, 19, 20):
            cp[k] = (tag, struct.unpack_from('>H', b, i)[0]); i += 2
        elif tag in (15,):
            cp[k] = (tag, b[i], struct.unpack_from('>H', b, i+1)[0]); i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            cp[k] = (tag, struct.unpack_from('>HH', b, i)); i += 4
        elif tag in (5, 6):
            cp[k] = (tag, struct.unpack_from('>HH', b, i)); i += 8
            k += 1
        else:
            raise ValueError('bad tag %d at %d' % (tag, i))
        k += 1
    return cp, i

def utf8(cp, idx):
    e = cp[idx]
    return e[1] if e and e[0] == 'Utf8' else None

def cls_name(cp, idx):
    e = cp[idx]
    return utf8(cp, e[1])

def methodref(cp, idx):
    tag, (ci, nti) = cp[idx]
    _, (ni, di) = cp[nti]
    return cls_name(cp, ci), utf8(cp, ni), utf8(cp, di)

TARGET_CLASS = 'androidx/compose/ui/\u0769'
TARGET_NAME = '\u037f'
TARGET_DESC_HEAD = '(Landroidx/compose/ui/\u0998;Landroidx/compose/runtime/Composer;II)V'
CALLER = 'com/xuncorp/voxzen/ui/navigation/ComposableSingletons$AppNavigation_desktopKt.class'

ok = True

# 1) 目标类里是否有这个方法，且签名符合「void + 恰好一个 Composer + 参数前置为 …]
b = z.read(TARGET_CLASS + '.class')
cp, off = parse_cp(b)
found = []
for k in range(1, len(cp)):
    if cp[k] and cp[k][0] == 'Utf8' and cp[k][1] == TARGET_NAME:
        desc = None
        print('utf8 idx', k, TARGET_NAME)
for k in range(1, len(cp)):
    if cp[k] and cp[k][0] == 'Utf8':
        v = cp[k][1]
        if v == TARGET_DESC_HEAD:
            found.append(k)
print('target desc present in %s: %s' % (TARGET_CLASS, bool(found)))
if not found:
    ok = False

# 2) 调用者类里，对该 Methodref 的调用次数必须恰好 1
b2 = z.read(CALLER)
cp2, off2 = parse_cp(b2)
midx = []
for k in range(1, len(cp2)):
    e = cp2[k]
    if e and e[0] == 10:
        c, n, d = methodref(cp2, k)
        if c == TARGET_CLASS and n == TARGET_NAME and d == TARGET_DESC_HEAD:
            midx.append(k)
print('methodref idx in caller:', midx)
if len(midx) != 1:
    ok = False
    print('!! 期望恰好 1 个 Methodref')
else:
    idx = midx[0]
    pat = struct.pack('>H', idx)
    # invokestatic 操作数在常量池之后；粗算整文件出现次数（cp 里也有 idx 的引用，需要排除 cp 区域）
    body = b2[off2:]
    cnt = body.count(pat)
    print('occurrences after constant pool:', cnt)
    if cnt != 1:
        print('!! 调用点数量不是 1，afterComposable 会报 ambiguous')

print('RESULT:', 'OK' if ok else 'PROBLEM')
sys.exit(0 if ok else 1)
