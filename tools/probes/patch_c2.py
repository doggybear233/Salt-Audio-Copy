import io, re, sys
p = r'D:\deepseek\workspace\projects\salt-rip\mod\src\main\kotlin\com\saltaudiocopy\rip\rip\RipEngine.kt'
s = io.open(p, encoding='utf-8').read()

s = s.replace(
    """        verify: Boolean = false,
        maxRetries: Int = 8,
        retryDelayMs: Int = 120
    ): RipResult {""",
    """        verify: Boolean = false,
        maxRetries: Int = 8,
        retryDelayMs: Int = 120,
        useC2: Boolean = false
    ): RipResult {""")

s = s.replace(
    """        var done = 0
        var retries = 0""",
    """        var done = 0
        var retries = 0
        var c2Active = useC2
        var c2Errors = 0""")

s = s.replace(
    """                            val len = readCompare(
                                dev, first.segment, second.segment, startLba + done, n,
                                a, b, verify, maxRetries, retryDelayMs,
                                onRetry = { retries++ }
                            )""",
    """                            val len = readCompare(
                                dev, first.segment, second.segment, startLba + done, n,
                                a, b, verify, maxRetries, retryDelayMs,
                                onRetry = { retries++ }
                            )
                            if (c2Active) {
                                val c2 = checkC2(
                                    dev, startLba + done, n, maxRetries, retryDelayMs,
                                    onRetry = { retries++ },
                                    onUnsupported = { c2Active = false }
                                )
                                if (c2 > 0) c2Errors += c2
                            }""")

s = s.replace(
    """        val result = RipResult(trackNo, outFile, done, done.toLong() * 2352, crc.value, elapsed, cancelled, retries)
        Diag.log(
            "rip track=$trackNo sectors=$done/$total crc32=0x${java.lang.Long.toHexString(crc.value)} " +
                "retries=$retries verify=$verify elapsed=${elapsed}ms cancelled=$cancelled -> $outFile"
        )""",
    """        val result = RipResult(trackNo, outFile, done, done.toLong() * 2352, crc.value, elapsed, cancelled, retries)
        Diag.log(
            "rip track=$trackNo sectors=$done/$total crc32=0x${java.lang.Long.toHexString(crc.value)} " +
                "retries=$retries verify=$verify c2=${if (useC2) "on($c2Errors 扇区报告)" else "off"} " +
                "elapsed=${elapsed}ms cancelled=$cancelled -> $outFile"
        )""")

# insert checkC2 before readCompare doc
anchor = """    /**
     * 读一块并写入 [a]，返回有效字节数。"""
check = '''    /**
     * 用 C2 位重读同一块，返回报告 C2 错误的扇区数。
     *
     * 只做「有没有错」判断：有错就整块重读（C2 指针纠错不在本实现范围内，EAC 同样靠重读）。
     * 驱动器不支持 C2（抛 SCSI 错误）时回调 [onUnsupported] 并返回 0，之后本次抓轨不再尝试。
     */
    internal fun checkC2(
        dev: CdDevice,
        lba: Int,
        n: Int,
        maxRetries: Int,
        retryDelayMs: Int,
        onRetry: () -> Unit = {},
        onUnsupported: () -> Unit = {}
    ): Int {
        var attempt = 0
        while (true) {
            val buf = try {
                dev.readAudioSectorsC2(lba, n)
            } catch (e: ScsiException) {
                Diag.log("c2 unsupported lba=$lba n=$n: ${e.message}")
                onUnsupported()
                return 0
            }
            val errors = C2.sectorErrorBytes(buf, n)
            val bad = errors.count { it > 0 }
            if (bad == 0) return 0
            if (attempt >= maxRetries) {
                Diag.log("c2 gave up lba=$lba n=$n after $maxRetries retries（$bad 扇区仍报告错误）")
                return bad
            }
            attempt++
            onRetry()
            Diag.log("c2 retry lba=$lba n=$n attempt=$attempt bad=$bad")
            if (retryDelayMs > 0) Thread.sleep(retryDelayMs.toLong())
        }
    }

'''
s = s.replace(anchor, check + anchor)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('patched')
