"""Minimal SPTI probe: INQUIRY / TEST UNIT READY / READ TOC / GET CONFIG / MODE SENSE.
Read-only (GENERIC_READ). Proves the M0 transport path on this host.
"""
import ctypes, ctypes.wintypes as wt, sys, struct

sys.stdout.reconfigure(encoding="utf-8")

GENERIC_READ = 0x80000000
FILE_SHARE_READ = 1
FILE_SHARE_WRITE = 2
OPEN_EXISTING = 3
IOCTL_SCSI_PASS_THROUGH_DIRECT = 0x0004D014
SCSI_IOCTL_DATA_OUT = 0
SCSI_IOCTL_DATA_IN = 1
SCSI_IOCTL_DATA_UNSPECIFIED = 2


class SCSI_PASS_THROUGH_DIRECT(ctypes.Structure):
    _fields_ = [
        ("Length", ctypes.c_ushort),
        ("ScsiStatus", ctypes.c_ubyte),
        ("PathId", ctypes.c_ubyte),
        ("TargetId", ctypes.c_ubyte),
        ("Lun", ctypes.c_ubyte),
        ("CdbLength", ctypes.c_ubyte),
        ("SenseInfoLength", ctypes.c_ubyte),
        ("DataIn", ctypes.c_ubyte),
        ("DataTransferLength", ctypes.c_ulong),
        ("TimeOutValue", ctypes.c_ulong),
        ("DataBuffer", ctypes.c_void_p),
        ("SenseInfoOffset", ctypes.c_ulong),
        ("Cdb", ctypes.c_ubyte * 16),
    ]


class SENSE(ctypes.Structure):
    _pack_ = 1
    _fields_ = [("buf", ctypes.c_ubyte * 32)]


k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.CreateFileW.restype = wt.HANDLE
k32.CreateFileW.argtypes = [wt.LPCWSTR, wt.DWORD, wt.DWORD, ctypes.c_void_p,
                            wt.DWORD, wt.DWORD, wt.HANDLE]
k32.DeviceIoControl.restype = wt.BOOL
k32.DeviceIoControl.argtypes = [wt.HANDLE, wt.DWORD, ctypes.c_void_p, wt.DWORD,
                                ctypes.c_void_p, wt.DWORD, ctypes.POINTER(wt.DWORD),
                                ctypes.c_void_p]
k32.CloseHandle.argtypes = [wt.HANDLE]


def open_dev(letter):
    access = GENERIC_READ | 0x40000000  # GENERIC_WRITE
    h = k32.CreateFileW(f"\\\\.\\{letter}:", access,
                        FILE_SHARE_READ | FILE_SHARE_WRITE, None,
                        OPEN_EXISTING, 0, None)
    if h == wt.HANDLE(-1).value or h is None:
        raise OSError(ctypes.get_last_error(), "CreateFileW failed")
    return h


def spt(h, cdb, buflen, data_in=SCSI_IOCTL_DATA_IN, timeout=10):
    data = (ctypes.c_ubyte * max(buflen, 1))()
    sense = SENSE()
    p = SCSI_PASS_THROUGH_DIRECT()
    p.Length = ctypes.sizeof(SCSI_PASS_THROUGH_DIRECT)
    p.CdbLength = 16
    p.SenseInfoLength = ctypes.sizeof(SENSE)
    p.DataIn = data_in
    p.DataTransferLength = buflen
    p.TimeOutValue = timeout
    p.DataBuffer = ctypes.cast(data, ctypes.c_void_p)
    p.SenseInfoOffset = ctypes.sizeof(SCSI_PASS_THROUGH_DIRECT)
    for i, b in enumerate(cdb):
        p.Cdb[i] = b
    # buffer must hold params + sense (sense offset is relative to struct start)
    total = ctypes.sizeof(SCSI_PASS_THROUGH_DIRECT) + ctypes.sizeof(SENSE)
    raw = (ctypes.c_ubyte * total)()
    ctypes.memmove(raw, ctypes.byref(p), ctypes.sizeof(p))
    returned = wt.DWORD(0)
    ok = k32.DeviceIoControl(h, IOCTL_SCSI_PASS_THROUGH_DIRECT, raw, total,
                             raw, total, ctypes.byref(returned), None)
    if not ok:
        err = ctypes.get_last_error()
        return None, None, f"DeviceIoControl err={err} ({ctypes.FormatError(err).strip()})"
    q = SCSI_PASS_THROUGH_DIRECT.from_buffer_copy(raw)
    s = SENSE.from_buffer_copy(raw, ctypes.sizeof(SCSI_PASS_THROUGH_DIRECT))
    return q.ScsiStatus, bytes(data[:buflen]), s


def show(name, status, buf, sense, take=64):
    if status is None:
        print(f"{name}: FAIL {sense}")
        return
    print(f"{name}: status={status:#04x}")
    if status != 0:
        sb = bytes(sense.buf)
        print(f"  sense: {sb[:18].hex(' ')}  (key={sb[2]:#04x} asc={sb[12]:#04x} ascq={sb[13]:#04x})")
    if buf is not None:
        print(f"  data[{take}]: {buf[:take].hex(' ')}")
    return buf


letter = sys.argv[1] if len(sys.argv) > 1 else "F"
h = open_dev(letter)
print(f"opened \\\\.\\{letter}: handle={h}")

# 1) TEST UNIT READY
st, buf, se = spt(h, [0x00, 0, 0, 0, 0, 0], 0, SCSI_IOCTL_DATA_UNSPECIFIED)
show("TEST_UNIT_READY", st, None, se)

# 2) INQUIRY
st, buf, se = spt(h, [0x12, 0, 0, 0, 36, 0], 36)
if show("INQUIRY", st, buf, se):
    b = buf
    print(f"  vendor={b[8:16].decode('ascii','replace').strip()!r} "
          f"product={b[16:32].decode('ascii','replace').strip()!r} "
          f"rev={b[32:36].decode('ascii','replace').strip()!r}")
    print(f"  peripheral_type={b[0]:#04x} (5=CD/DVD)  version={b[2]:#04x}")

# 3) GET CONFIGURATION (all)
st, buf, se = spt(h, [0x46, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00], 0x400)
if show("GET_CONFIGURATION", st, buf, se, 16):
    head = struct.unpack_from(">IHH", buf, 0)
    print(f"  config data length={head[0]} current_profile={head[1]:#06x} -> {head[2]:#06x}")
    off = 8
    feats = []
    while off + 4 <= min(len(buf), head[0] + 4):
        code, b2, b3, add = struct.unpack_from(">HBBB", buf, off)
        length = b3 & 0x0F
        if length == 0:
            break
        flags = b2
        feats.append((code, flags >> 1 & 0x01, (flags >> 4) & 0x01, (flags >> 2) & 0x01, flags & 0x01))
        off += 4 + length
    print(f"  profiles/features ({len(feats)}):")
    for code, cur, persist, ver, cur2 in feats[:24]:
        kind = "profile" if (code & 0x8000) == 0 else "feature"
        print(f"    {kind} {code:#06x} current={cur or cur2}")

# 4) MODE SENSE(10), page 0x08 (caching)
st, buf, se = spt(h, [0x5A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00], 0x40)
if show("MODE_SENSE10(caching page 0x08)", st, buf, se, 32):
    # header 8 bytes, then page
    if len(buf) > 8:
        page = buf[8:]
        pc = page[0] & 0x3F
        print(f"  page_code={pc:#04x} len={page[1]} RCD={page[2] & 0x01} (1=read cache disabled)")

# 5) READ TOC (format 0)
st, buf, se = spt(h, [0x43, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00], 0x400)
if show("READ_TOC(format0)", st, buf, se, 16):
    tl = struct.unpack_from(">H", buf, 0)[0]
    first, last = buf[2], buf[3]
    print(f"  toc_len={tl} first_track={first} last_track={last}  -> ", end="")
    print("AUDIO CD" if first >= 1 and last >= first else "no audio tracks / data disc")
    n = (tl - 2) // 8
    for i in range(n):
        e = 8 + i * 8
        if e + 8 > len(buf):
            break
        ctrl_adr, track, _res, start = struct.unpack_from(">BBBI", buf, e)
        msf = struct.unpack_from(">BBB", buf, e + 5)
        print(f"    track {track:3d} ctrl={ctrl_adr >> 4} adr={ctrl_adr & 0xF} start_lba={start} "
              f"msf={msf[0]:02d}:{msf[1]:02d}:{msf[2]:02d}")

k32.CloseHandle(h)
