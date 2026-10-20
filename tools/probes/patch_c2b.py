import io

base = r'D:\deepseek\workspace\projects\salt-rip\mod\src\main\kotlin\com\saltaudiocopy\rip'

# 1) RipSettings: 字段 + 键 + load + save
p = base + r'\settings\RipSettings.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace(
    "    /** 是否把每轨 CRC 附在 CUE 的 REM 里（方便对账）。 */\n    val annotateCueCrc: Boolean = true",
    "    /** 是否把每轨 CRC 附在 CUE 的 REM 里（方便对账）。 */\n    val annotateCueCrc: Boolean = true,\n"
    "    /** 是否用 C2 位检查读取错误（驱动器不支持时自动降级）。 */\n    val useC2: Boolean = false")
s = s.replace(
    '    private const val KEY_CUE_CRC = "annotateCueCrc"',
    '    private const val KEY_CUE_CRC = "annotateCueCrc"\n    private const val KEY_USE_C2 = "useC2"')
s = s.replace(
    "            annotateCueCrc = readBool(h, KEY_CUE_CRC, d.annotateCueCrc)",
    "            annotateCueCrc = readBool(h, KEY_CUE_CRC, d.annotateCueCrc),\n"
    "            useC2 = readBool(h, KEY_USE_C2, d.useC2)")
s = s.replace(
    "            h.set(KEY_CUE_CRC, s.annotateCueCrc)",
    "            h.set(KEY_CUE_CRC, s.annotateCueCrc)\n            h.set(KEY_USE_C2, s.useC2)")
io.open(p, 'w', encoding='utf-8', newline='').write(s)

# 2) AlbumRipper: 传 useC2
p = base + r'\album\AlbumRipper.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace(
    "                    verify = settings.secureMode,",
    "                    verify = settings.secureMode,\n                    useC2 = settings.useC2,")
io.open(p, 'w', encoding='utf-8', newline='').write(s)

# 3) ConfigActions.ripFirstTrack: 传 useC2
p = base + r'\ConfigActions.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace(
    "                    verify = settings.secureMode,\n                    maxRetries = settings.maxRetries,",
    "                    verify = settings.secureMode,\n                    useC2 = settings.useC2,\n                    maxRetries = settings.maxRetries,")
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
