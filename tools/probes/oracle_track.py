"""独立 oracle：按正确 CDB（READ CD，LBA 为 CDB[2..5] 四字节大端）读某轨，算 CRC32。

用途：和 Kotlin 侧抓轨结果对账——0.14.0 的 LBA 字段错位教训说明「自己跟自己对账」
可能一起错，必须有一个与主代码不同的实现。
"""
import ctypes, sys, zlib
from ctypes import wintypes

GENERIC_READ, GENERIC_WRITE = 0x80000000, 0x40000000
OPEN_EXISTING = 3
IOCTL = 0x4D014
k32 = ctypes.WinDLL('kernel32', use_last_error=True)
k32.CreateFileW.restype = wintypes.HANDLE


class SPTD(ctypes.Structure):
    _fields_ = [("Length", wintypes.USHORT), ("ScsiStatus", ctypes.c_ubyte),
                ("PathId", ctypes.c_ubyte), ("TargetId", ctypes.c_ubyte), ("Lun", ctypes.c_ubyte),
                ("CdbLength", ctypes.c_ubyte), ("SenseInfoLength", ctypes.c_ubyte), ("DataIn", ctypes.c_ubyte),
                ("DataTransferLength", wintypes.ULONG), ("TimeOutValue", wintypes.ULONG),
                ("DataBuffer", ctypes.c_void_p), ("SenseInfoOffset", wintypes.ULONG),
                ("Cdb", ctypes.c_ubyte * 16)]


def cdb(lba, n):
    c = bytearray(12)
    c[0] = 0xBE
    c[1] = 0x04
    c[2] = (lba >> 24) & 0xFF
    c[3] = (lba >> 16) & 0xFF
    c[4] = (lba >> 8) & 0xFF
    c[5] = lba & 0xFF
    c[6] = (n >> 16) & 0xFF
    c[7] = (n >> 8) & 0xFF
    c[8] = n & 0xFF
    c[9] = 0xF8
    return bytes(c)


def read(h, lba, n):
    size = n * 2352
    buf = ctypes.create_string_buffer(size)
    s = SPTD()
    s.Length = ctypes.sizeof(SPTD)
    s.CdbLength = 12
    s.SenseInfoLength = 32
    s.DataIn = 1
    s.DataTransferLength = size
    s.TimeOutValue = 20
    s.DataBuffer = ctypes.cast(buf, ctypes.c_void_p)
    s.SenseInfoOffset = ctypes.sizeof(SPTD)
    for i, b in enumerate(cdb(lba, n)):
        s.Cdb[i] = b
    total = ctypes.create_string_buffer(ctypes.sizeof(SPTD) + 32)
    ctypes.memmove(total, ctypes.byref(s), ctypes.sizeof(SPTD))
    ret = wintypes.DWORD(0)
    ok = k32.DeviceIoControl(h, IOCTL, total, ctypes.sizeof(SPTD) + 32, total,
                             ctypes.sizeof(SPTD) + 32, ctypes.byref(ret), None)
    if not ok:
        raise OSError(ctypes.get_last_error(), "DeviceIoControl")
    out = ctypes.cast(total, ctypes.POINTER(SPTD)).contents
    if out.ScsiStatus != 0:
        sense = bytes(total[ctypes.sizeof(SPTD):ctypes.sizeof(SPTD) + 32])
        raise RuntimeError("SCSI status=0x%02X key=%d asc=0x%02X" % (out.ScsiStatus, sense[2] & 0x0F, sense[12]))
    return buf.raw[:out.DataTransferLength]


def toc(h):
    buf = ctypes.create_string_buffer(1024)
    s = SPTD()
    s.Length = ctypes.sizeof(SPTD)
    s.CdbLength = 12
    s.SenseInfoLength = 32
    s.DataIn = 1
    s.DataTransferLength = 1024
    s.TimeOutValue = 10
    s.DataBuffer = ctypes.cast(buf, ctypes.c_void_p)
    s.SenseInfoOffset = ctypes.sizeof(SPTD)
    c = bytearray(12)
    c[0] = 0x43
    c[2] = 0
    c[7] = 0x04
    c[8] = 0x00
    for i, b in enumerate(c):
        s.Cdb[i] = b
    total = ctypes.create_string_buffer(ctypes.sizeof(SPTD) + 32)
    ctypes.memmove(total, ctypes.byref(s), ctypes.sizeof(SPTD))
    ret = wintypes.DWORD(0)
    k32.DeviceIoControl(h, IOCTL, total, ctypes.sizeof(SPTD) + 32, total,
                        ctypes.sizeof(SPTD) + 32, ctypes.byref(ret), None)
    out = ctypes.cast(total, ctypes.POINTER(SPTD)).contents
    data = buf.raw[:out.DataTransferLength]
    first, last = data[2], data[3]
    entries = {}
    for i in range(first, last + 1):
        off = 4 + (i - first) * 8
        entries[i] = int.from_bytes(data[off + 4:off + 8], "big")
    leadout = int.from_bytes(data[4 + (last - first + 1) * 8 + 4:4 + (last - first + 1) * 8 + 8], "big")
    return entries, leadout


h = k32.CreateFileW("\\\\.\\F:", GENERIC_READ | GENERIC_WRITE, 3, None, OPEN_EXISTING, 0x80, None)
entries, leadout = toc(h)
print("tracks:", sorted(entries), "leadOut:", leadout)
track = int(sys.argv[1]) if len(sys.argv) > 1 else 2
start = entries[track]
end = entries.get(track + 1, leadout)
total = end - start
crc = 0
done = 0
while done < total:
    n = min(16, total - done)
    crc = zlib.crc32(read(h, start + done, n), crc)
    done += n
print("track=%d startLba=%d sectors=%d crc32=0x%08X bytes=%d" % (track, start, total, crc & 0xFFFFFFFF, total * 2352))
k32.CloseHandle(h)
