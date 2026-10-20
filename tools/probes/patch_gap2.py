import io

base = r'D:\deepseek\workspace\projects\salt-rip\mod'

# settings
p = base + r'\src\main\kotlin\com\saltrip\rip\settings\RipSettings.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace(
    "    /** Test & Copy：先测试读取一遍拿 CRC，再正式写入并逐轨比对。 */\n    val testAndCopy: Boolean = false",
    "    /** Test & Copy：先测试读取一遍拿 CRC，再正式写入并逐轨比对。 */\n    val testAndCopy: Boolean = false,\n"
    "    /** 是否用子通道 Q 探测音轨前的 INDEX 00 间隙。 */\n    val detectGaps: Boolean = true")
s = s.replace('    private const val KEY_TEST_COPY = "testAndCopy"',
              '    private const val KEY_TEST_COPY = "testAndCopy"\n    private const val KEY_DETECT_GAPS = "detectGaps"')
s = s.replace("            testAndCopy = readBool(h, KEY_TEST_COPY, d.testAndCopy)",
              "            testAndCopy = readBool(h, KEY_TEST_COPY, d.testAndCopy),\n"
              "            detectGaps = readBool(h, KEY_DETECT_GAPS, d.detectGaps)")
s = s.replace("            h.set(KEY_TEST_COPY, s.testAndCopy)",
              "            h.set(KEY_TEST_COPY, s.testAndCopy)\n            h.set(KEY_DETECT_GAPS, s.detectGaps)")
io.open(p, 'w', encoding='utf-8', newline='').write(s)

# AlbumRipper: 传 detectGaps + 上一轨结束作为下界
p = base + r'\src\main\kotlin\com\saltrip\rip\album\AlbumRipper.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace("""                retryDelayMs = settings.retryDelayMs,
                useC2 = settings.useC2
            )
            if (settings.testAndCopy && !r.cancelled) {""",
"""                retryDelayMs = settings.retryDelayMs,
                useC2 = settings.useC2,
                detectGaps = settings.detectGaps,
                gapFloorLba = if (i > 0) toc.sectorsOf(i - 1).let { it.first + it.second } else startLba
            )
            if (settings.testAndCopy && !r.cancelled) {""")
io.open(p, 'w', encoding='utf-8', newline='').write(s)

# preference json: switch
p = base + r'\src\main\resources\preference_config.json'
s = io.open(p, encoding='utf-8').read()
s = s.replace("""        {
          "type": "switch",
          "key": "testAndCopy",""",
"""        {
          "type": "switch",
          "key": "detectGaps",
          "title": "探测 INDEX 00 间隙",
          "summary": "用子通道 Q 往前扫最多 1 秒，检测音轨前的预间隙并写入日志（只报告，不改文件边界）。",
          "default_value": true
        },
        {
          "type": "switch",
          "key": "testAndCopy",""")
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
