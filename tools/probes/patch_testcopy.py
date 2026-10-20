import io

p = r'D:\deepseek\workspace\projects\salt-rip\mod\src\main\kotlin\com\saltaudiocopy\rip\rip\RipEngine.kt'
s = io.open(p, encoding='utf-8').read()

# 1) 参数：writeFile
s = s.replace(
    """        retryDelayMs: Int = 120,
        useC2: Boolean = false
    ): RipResult {""",
    """        retryDelayMs: Int = 120,
        useC2: Boolean = false,
        writeFile: Boolean = true
    ): RipResult {""")

# 2) 打开 WAV 的条件化：test 模式不建文件，用空壳统计
s = s.replace(
    """        CdDevice(scan.path).use { dev ->
            WavWriter(outFile).use { wav ->""",
    """        CdDevice(scan.path).use { dev ->
            val wav: WavSink = if (writeFile) WavWriter(outFile) else NullWavSink
            wav.use {""")

s = s.replace(
    """                            wav.write(a, 0, len)""",
    """                            wav.write(a, 0, len)""")

s = s.replace(
    """        val result = RipResult(trackNo, outFile, done, done.toLong() * 2352, crc.value, elapsed, cancelled, retries)
        Diag.log(
            "rip track=$trackNo sectors=$done/$total crc32=0x${java.lang.Long.toHexString(crc.value)} " +""",
    """        val result = RipResult(trackNo, outFile, done, done.toLong() * 2352, crc.value, elapsed, cancelled, retries)
        Diag.log(
            "rip track=$trackNo sectors=$done/$total crc32=0x${java.lang.Long.toHexString(crc.value)} " +
                "test=${!writeFile} " +""")

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('patched engine')
