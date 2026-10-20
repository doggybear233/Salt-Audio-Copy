# 复现 MMC/READ10/D8 三种读命令在真机上的返回值，定位回退链里到底哪一条先失败
import ctypes, struct, sys
from ctypes import wintypes

GENERIC_READ, GENERIC_WRITE = 0x80000000, 0x40000000
OPEN_EXISTING = 3
FILE_ATTRIBUTE_NORMAL = 0x80
IOCTL_SCSI_PASS_THROUGH_DIRECT = 0x4D014
SCSI_IOCTL_DATA_IN = 1

k32 = ctypes.WinDLL('kernel32', use_last_error=True)
k32.CreateFileW.restype = wintypes.HANDLE
k32.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                            wintypes.LPVOID, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
k32.DeviceIoControl.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.LPVOID, wintypes.DWORD,
                                wintypes.LPVOID, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD), wintypes.LPVOID]
k32.CloseHandle.argtypes = [wintypes.HANDLE]


class SPTD(ctypes.Structure):
    _fields_ = [("Length", wintypes.USHORT), ("ScsiStatus", ctypes.c_ubyte),
                ("PathId", ctypes.c_ubyte), ("TargetId", ctypes.c_ubyte), ("Lun", ctypes.c_ubyte),
                ("CdbLength", ctypes.c_ubyte), ("SenseInfoLength", ctypes.c_ubyte), ("DataIn", ctypes.c_ubyte),
                ("DataTransferLength", wintypes.ULONG), ("TimeOutValue", wintypes.ULONG),
                ("DataBuffer", ctypes.c_void_p), ("SenseInfoOffset", wintypes.ULONG),
                ("Cdb", ctypes.c_ubyte * 16)]


def be24(b, off, v):
    b[off] = (v >> 16) & 0xFF
    b[off + 1] = (v >> 8) & 0xFF
    b[off + 2] = v & 0xFF


def cdb_mmc(lba, n, flags):
    c = bytearray(12)
    c[0] = 0xBE
    c[1] = 0x04
    be24(c, 2, lba)
    be24(c, 6, n)
    c[9] = flags
    return bytes(c)


def cdb_read10(lba, n):
    c = bytearray(12)
    c[0] = 0x28
    c[1] = 0
    c[2] = (lba >> 24) & 0xFF
    c[3] = (lba >> 16) & 0xFF
    c[4] = (lba >> 8) & 0xFF
    c[5] = lba & 0xFF
    c[7] = (n >> 8) & 0xFF
    c[8] = n & 0xFF
    return bytes(c)


def cdb_d8(lba, n):
    c = bytearray(12)
    c[0] = 0xD8
    be24(c, 2, lba)
    be24(c, 6, n)
    return bytes(c)


def send(h, cdb, buf_len):
    buf = ctypes.create_string_buffer(buf_len)
    sense = ctypes.create_string_buffer(32)
    s = SPTD()
    s.Length = ctypes.sizeof(SPTD)
    s.CdbLength = 12
    s.SenseInfoLength = 32
    s.DataIn = SCSI_IOCTL_DATA_IN
    s.DataTransferLength = buf_len
    s.TimeOutValue = 10
    s.DataBuffer = ctypes.cast(buf, ctypes.c_void_p)
    s.SenseInfoOffset = ctypes.sizeof(SPTD)
    for i, b in enumerate(cdb):
        s.Cdb[i] = b
    total = ctypes.create_string_buffer(ctypes.sizeof(SPTD) + 32)
    ctypes.memmove(total, ctypes.byref(s), ctypes.sizeof(SPTD))
    returned = wintypes.DWORD(0)
    ok = k32.DeviceIoControl(h, IOCTL_SCSI_PASS_THROUGH_DIRECT, total,
                             ctypes.sizeof(SPTD) + 32, total, ctypes.sizeof(SPTD) + 32,
                             ctypes.byref(returned), None)
    err = ctypes.get_last_error()
    out = ctypes.cast(total, ctypes.POINTER(SPTD)).contents
    sense_bytes = bytes(total[ctypes.sizeof(SPTD):ctypes.sizeof(SPTD) + 32])
    key = sense_bytes[2] & 0x0F if len(sense_bytes) > 13 else None
    asc = sense_bytes[12] if len(sense_bytes) > 12 else None
    return ok, err, out.ScsiStatus, key, asc, out.DataTransferLength


h = k32.CreateFileW("\\\\.\\F:", GENERIC_READ | GENERIC_WRITE, 3, None, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, None)
if h == wintypes.HANDLE(-1).value or h is None:
    print("open failed", ctypes.get_last_error())
    sys.exit(1)

for name, cdb, n in [
    ("MMC 0xBE F8 x1", cdb_mmc(0, 1, 0xF8), 2352),
    ("MMC 0xBE F8 x16", cdb_mmc(0, 16, 0xF8), 16 * 2352),
    ("MMC 0xBE F8 x27", cdb_mmc(0, 27, 0xF8), 27 * 2352),
    ("MMC 0xBE 10 x16", cdb_mmc(0, 16, 0x10), 16 * 2352),
    ("READ10 x16", cdb_read10(0, 16), 16 * 2352),
    ("D8 x1", cdb_d8(0, 1), 2352),
]:
    ok, err, st, key, asc, got = send(h, cdb, n)
    print("%-18s ok=%d err=%d status=0x%X key=%s asc=%s xfer=%d" % (name, ok, err, st, key, asc, got))

k32.CloseHandle(h)
