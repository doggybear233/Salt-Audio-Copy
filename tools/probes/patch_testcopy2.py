import io

p = r'D:\deepseek\workspace\projects\salt-rip\mod\src\main\kotlin\com\saltaudiocopy\rip\album\AlbumRipper.kt'
s = io.open(p, encoding='utf-8').read()

# 1) 测试通道：在正式循环前先跑一遍"只读不写"的读取，收集每轨 CRC
s = s.replace(
    """        for (i in selected) {
            if (cancel()) { cancelled = true; break }
            val entry = toc.tracks[i]""",
    """        // Test & Copy：先用「只读不写」的测试通道跑一遍，拿到每轨 CRC 作为对照
        val testCrc = HashMap<Int, Long>()
        if (settings.testAndCopy) {
            for (i in selected) {
                if (cancel()) { cancelled = true; break }
                val entry = toc.tracks[i]
                onStage("测试读取第 ${entry.track} 轨…")
                val r = RipEngine.ripTrack(
                    scan, i, dir.resolve("__test__.tmp"), cancel, { },
                    verify = settings.secureMode,
                    maxRetries = settings.maxRetries,
                    retryDelayMs = settings.retryDelayMs,
                    useC2 = settings.useC2,
                    writeFile = false
                )
                testCrc[entry.track] = r.crc32
            }
            Diag.log("test&copy: 测试通道完成 ${testCrc.size} 轨")
        }

        for (i in selected) {
            if (cancel()) { cancelled = true; break }
            val entry = toc.tracks[i]""")

# 2) 正式读取后与测试通道比对
s = s.replace(
    """            if (settings.offsetSamples != 0 && !r.cancelled) {""",
    """            if (settings.testAndCopy && !r.cancelled) {
                val expected = testCrc[entry.track]
                if (expected != null && expected != r.crc32) {
                    Diag.log(
                        "test&copy mismatch track=${entry.track} test=0x%08X copy=0x%08X，重抓一次"
                            .format(expected, r.crc32)
                    )
                    notes += "第 ${entry.track} 轨测试/复制 CRC 不一致（0x%08X vs 0x%08X），已重抓"
                        .format(expected, r.crc32)
                    val again = RipEngine.ripTrack(
                        scan, i, outFile, cancel, { },
                        verify = settings.secureMode,
                        maxRetries = settings.maxRetries,
                        retryDelayMs = settings.retryDelayMs,
                        useC2 = settings.useC2
                    )
                    if (again.crc32 != expected) {
                        notes += "第 ${entry.track} 轨重抓后仍与测试通道不一致（0x%08X）".format(again.crc32)
                    } else {
                        notes += "第 ${entry.track} 轨重抓后与测试通道一致"
                    }
                }
            }
            if (settings.offsetSamples != 0 && !r.cancelled) {""")

io.open(p, 'w', encoding='utf-8', newline='').write(s)

# 3) 设置项
p = r'D:\deepseek\workspace\projects\salt-rip\mod\src\main\kotlin\com\saltaudiocopy\rip\settings\RipSettings.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace(
    "    /** 是否用 C2 位检查读取错误（驱动器不支持时自动降级）。 */\n    val useC2: Boolean = false",
    "    /** 是否用 C2 位检查读取错误（驱动器不支持时自动降级）。 */\n    val useC2: Boolean = false,\n"
    "    /** Test & Copy：先测试读取一遍拿 CRC，再正式写入并逐轨比对。 */\n    val testAndCopy: Boolean = false")
s = s.replace(
    '    private const val KEY_USE_C2 = "useC2"',
    '    private const val KEY_USE_C2 = "useC2"\n    private const val KEY_TEST_COPY = "testAndCopy"')
s = s.replace(
    "            useC2 = readBool(h, KEY_USE_C2, d.useC2)",
    "            useC2 = readBool(h, KEY_USE_C2, d.useC2),\n"
    "            testAndCopy = readBool(h, KEY_TEST_COPY, d.testAndCopy)")
s = s.replace(
    "            h.set(KEY_USE_C2, s.useC2)",
    "            h.set(KEY_USE_C2, s.useC2)\n            h.set(KEY_TEST_COPY, s.testAndCopy)")
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
