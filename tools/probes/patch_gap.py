import io

base = r'D:\deepseek\workspace\projects\salt-rip\mod\src\main\kotlin\com\saltaudiocopy\rip'

# 1) RipResult 增加 gapSectors
p = base + r'\rip\RipEngine.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace("import com.doggybear233.saltaudiocopy.scsi.CdDevice",
              "import com.doggybear233.saltaudiocopy.scsi.CdDevice\nimport com.doggybear233.saltaudiocopy.scsi.GapDetector")
s = s.replace("""        useC2: Boolean = false,
        writeFile: Boolean = true
    ): RipResult {""",
"""        useC2: Boolean = false,
        writeFile: Boolean = true,
        detectGaps: Boolean = false,
        gapFloorLba: Int = -1
    ): RipResult {""")
s = s.replace("""        CdDevice(scan.path).use { dev ->""",
"""        var gapSectors = 0
        CdDevice(scan.path).use { dev ->
            if (detectGaps) {
                val floor = if (gapFloorLba >= 0) gapFloorLba else startLba
                val g = runCatching { GapDetector.detect(startLba, floor) { lba -> dev.readQ(lba) } }
                    .onFailure { Diag.log("gap detect failed lba=$startLba", it) }
                    .getOrNull()
                if (g != null && g.sectors > 0) {
                    gapSectors = g.sectors
                    Diag.log("gap track=$trackNo index00=${g.sectors} sectors (${g.msf}) startLba=${g.startLba}")
                }
            }""")
s = s.replace("""        val result = RipResult(trackNo, outFile, done, done.toLong() * 2352, crc.value, elapsed, cancelled, retries)""",
"""        val result = RipResult(trackNo, outFile, done, done.toLong() * 2352, crc.value, elapsed, cancelled, retries)""")
s = s.replace("""                "test=${!writeFile} " +""",
"""                "test=${!writeFile} gap=$gapSectors " +""")
# RipResult data class 增加字段
s = s.replace("""data class RipResult(""", """data class RipResult(""")
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('engine patched')
