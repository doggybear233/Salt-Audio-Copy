"""Corrected READ TOC probing: format goes in CDB[2], start track in CDB[6]."""
import ctypes, ctypes.wintypes as wt, sys

sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)

IOCTL = 0x0004D014


class SPTD(ctypes.Structure):
    _fields_ = [
        ("Length", ctypes.c_ushort), ("ScsiStatus", ctypes.c_ubyte),
        ("PathId", ctypes.c_ubyte), ("TargetId", ctypes.c_ubyte),
        ("Lun", ctypes.c_ubyte), ("CdbLength", ctypes.c_ubyte),
        ("SenseInfoLength", ctypes.c_ubyte), ("DataIn", ctypes.c_ubyte),
        ("DataTransferLength", ctypes.c_ulong), ("TimeOutValue", ctypes.c_ulong),
        ("DataBuffer", ctypes.c_void_p), ("SenseInfoOffset", ctypes.c_ulong),
        ("Cdb", ctypes.c_ubyte * 16),
    ]

k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.CreateFileW.restype = wt.HANDLE
k32.CreateFileW.argtypes = [wt.LPCWSTR, wt.DWORD, wt.DWORD, ctypes.c_void_p, wt.DWORD, wt.DWORD, wt.HANDLE]
k32.DeviceIoControl.restype = wt.BOOL
k32.DeviceIoControl.argtypes = [wt.HANDLE, wt.DWORD, ctypes.c_void_p, wt.DWORD, ctypes.c_void_p,
                                wt.DWORD, ctypes.POINTER(wt.DWORD), ctypes.c_void_p]

LETTER = sys.argv[1] if len(sys.argv) > 1 else "F"
h = k32.CreateFileW(f"\\\\.\\{LETTER}:", 0xC0000000, 3, None, 3, 0, None)


def run(cdb, buflen, timeout=30):
    total = ctypes.sizeof(SPTD) + 32
    raw = (ctypes.c_ubyte * total)()
    data = (ctypes.c_ubyte * max(buflen, 1))()
    p = SPTD.from_buffer(raw)
    p.Length = ctypes.sizeof(SPTD)
    p.CdbLength = 16
    p.SenseInfoLength = 32
    p.DataIn = 1
    p.DataTransferLength = buflen
    p.TimeOutValue = timeout
    p.DataBuffer = ctypes.cast(data, ctypes.c_void_p)
    p.SenseInfoOffset = ctypes.sizeof(SPTD)
    for i, b in enumerate(cdb):
        p.Cdb[i] = b
    ret = wt.DWORD(0)
    ok = k32.DeviceIoControl(h, IOCTL, raw, total, raw, total, ctypes.byref(ret), None)
    return ok, (SPTD.from_buffer_copy(raw).ScsiStatus if ok else None), bytes(data)


def read_toc(fmt, start=0, alloc=804, msf=True):
    cdb = [0x43, 0x02 if msf else 0x00, fmt, 0, 0, 0, start, (alloc >> 8) & 0xFF, alloc & 0xFF, 0, 0, 0]
    return run(cdb, alloc)


for fmt in (0, 2, 3, 4, 5):
    ok, st, buf = read_toc(fmt, 0, 804)
    if not ok or st:
        print(f"format {fmt}: ok={ok} status={st}")
        continue
    dlen = (buf[0] << 8) | buf[1]
    print(f"\n=== format {fmt}: data_len={dlen} first={buf[2]} last={buf[3]} ===")
    body = buf[4:4 + max(0, dlen - 2)]
    print(f"  body {len(body)} B: {body[:48].hex(' ')}")
    if fmt in (0, 3, 4, 5):
        for i in range(len(body) // 8):
            e = body[i * 8:(i + 1) * 8]
            addr = int.from_bytes(e[4:8], "big")
            print(f"   e{i:02d} adr/ctl=0x{e[1]:02x} trk=0x{e[2]:02x} addr={addr} ({addr // 4500:02d}:{(addr // 75) % 60:02d}:{addr % 75:02d})")
    elif fmt == 2:
        for i in range(len(body) // 11):
            e = body[i * 11:(i + 1) * 11]
            sess, adr_ctl, tno, point = e[0], e[1], e[2], e[3]
            msf = (e[4], e[5], e[6])
            pmsf = (e[8], e[9], e[10])
            print(f"   e{i:02d} sess={sess:3d} adr={adr_ctl >> 4} ctl={adr_ctl & 0xF} tno={tno:3d} "
                  f"point=0x{point:02x} msf={msf[0]:02d}:{msf[1]:02d}:{msf[2]:02d} "
                  f"pmsf={pmsf[0]:02d}:{pmsf[1]:02d}:{pmsf[2]:02d}")

k32.CloseHandle(h)
