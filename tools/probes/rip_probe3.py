"""M1 transport proof: proper TOC, SET CD SPEED, chunked full-track read + CRC."""
import ctypes, ctypes.wintypes as wt, sys, zlib, time, hashlib

sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)

IOCTL = 0x0004D014
MAX_CHUNK = 16  # empirical: >16 sectors per SCSI_PASS_THROUGH_DIRECT -> ERROR_INVALID_PARAMETER


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


def run(cdb, buflen, timeout=20):
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
    ctypes.set_last_error(0)
    ok = k32.DeviceIoControl(h, IOCTL, raw, total, raw, total, ctypes.byref(ret), None)
    e = ctypes.get_last_error()
    st = SPTD.from_buffer_copy(raw).ScsiStatus if ok else None
    sense = bytes(raw[ctypes.sizeof(SPTD):])
    return ok, e, st, bytes(data), sense


def readcd(lba, count, flags=0xF8, sect=0x04):
    cdb = [0xBE, sect, (lba >> 24) & 0xFF, (lba >> 16) & 0xFF, (lba >> 8) & 0xFF, lba & 0xFF,
           0, 0, count, flags, 0, 0, 0, 0, 0, 0]
    return run(cdb, 2352 * count)


def set_speed(read_kbs=0xFFFF, write_kbs=0xFFFF):
    cdb = [0xBB, 0, 0, 0, (read_kbs >> 8) & 0xFF, read_kbs & 0xFF,
           (write_kbs >> 8) & 0xFF, write_kbs & 0xFF, 0, 0, 0, 0]
    return run(cdb, 0)


def msf(lba):
    lba = max(lba, 0)
    return f"{lba // 4500:02d}:{(lba // 75) % 60:02d}:{lba % 75:02d}"


# ---------- 1. full TOC ----------
print(f"=== TOC ({LETTER}:) ===")
ok, e, st, toc, _ = run([0x43, 0x00, 0, 0, 0, 0, 0, 0x03, 0x24, 0, 0, 0], 804, 20)
tl = (toc[0] << 8) | toc[1]
print(f"  ioctl_ok={ok} scsi_status={st} toc_len={tl} first={toc[2]} last={toc[3]}")
tracks = {}
n = (tl - 2) // 8
for i in range(n):
    o = 4 + i * 8
    ctl_adr, trk = toc[o + 1], toc[o + 2]
    addr = int.from_bytes(toc[o + 4:o + 8], "big")
    adr, ctl = ctl_adr >> 4, ctl_adr & 0x0F
    tracks[trk] = (addr, ctl, adr)
    kind = "audio" if ctl & 0x04 == 0 else "data"
    extra = ""
    if ctl & 0x02:
        extra += " pre-emphasis"
    if ctl & 0x01:
        extra += " digital-copy"
    print(f"  t{trk:02d} lba={addr:6d} msf={msf(addr)} {kind}{extra} (adr={adr} ctl={ctl})")

lead = tracks.get(0xAA, (None,))[0]
first, last = toc[2], toc[3]
print(f"  lead-out lba={lead} ({msf(lead)}) total_tracks={last - first + 1}")

# ---------- 2. speed ----------
print("\n=== SET CD SPEED ===")
for kb in (0xFFFF, 0x0000):
    ok, e, st, _, sense = set_speed(kb)
    print(f"  read={kb:#06x}: ioctl_ok={ok} err={e} scsi={st} sense={sense[:10].hex(' ')}")

# ---------- 3. throughput at different modes ----------
def bench(chunk, n_chunks, lba0=1000):
    t0 = time.perf_counter()
    total = 0
    for i in range(n_chunks):
        ok, e, st, buf, _ = readcd(lba0 + i * chunk, chunk)
        if not ok or st:
            print(f"    chunk {i} failed ok={ok} err={e} st={st}")
            break
        total += chunk
    dt = time.perf_counter() - t0
    if dt:
        print(f"  chunk={chunk:2d}: {total:4d} sectors in {dt*1000:7.1f} ms -> "
              f"{total/dt:6.0f} sec/s = {total/dt/150:4.1f}x = {total*2352/dt/1024:5.0f} KB/s")


print("\n=== throughput ===")
for chunk in (1, 4, 8, 16):
    bench(chunk, max(1, 64 // chunk))

# ---------- 4. full track rip with CRC ----------
if lead and last >= first:
    t1 = tracks.get(first, (0,))[0]
    t2 = tracks.get(first + 1, (lead,))[0]
    nsec = t2 - t1
    print(f"\n=== full read of track {first}: lba {t1}..{t2 - 1} ({nsec} sectors, "
          f"{nsec / 75:.1f} s audio) ===")
    crc = 0
    md5 = hashlib.md5()
    got = 0
    t0 = time.perf_counter()
    errs = 0
    pos = 0
    while pos < nsec:
        cnt = min(MAX_CHUNK, nsec - pos)
        ok, e, st, buf, sense = readcd(t1 + pos, cnt)
        if not ok or st:
            errs += 1
            print(f"    read fail at lba {t1 + pos}: ok={ok} err={e} st={st} sense={sense[:14].hex(' ')}")
            if errs > 3:
                break
            continue
        crc = zlib.crc32(buf, crc)
        md5.update(buf)
        got += cnt
        pos += cnt
    dt = time.perf_counter() - t0
    print(f"  read {got}/{nsec} sectors, crc32={crc & 0xFFFFFFFF:#010x}, md5={md5.hexdigest()}")
    print(f"  time {dt:.2f}s -> {got/dt/150:.1f}x, errors={errs}")
    # AccurateRip v1-style: CRC32 over the whole track's samples, standard polynomial,
    # plus the "CRC of first 450 sectors" used by AR v1
    if got == nsec:
        ar_crc = zlib.crc32(b"")  # placeholder marker

# ---------- 5. cache behaviour ----------
print("\n=== cache detection ===")
def timed(lba, chunk=16, n=8):
    t0 = time.perf_counter()
    for i in range(n):
        ok, e, st, buf, _ = readcd(lba + i * chunk, chunk)
        if not ok or st:
            return None
    return time.perf_counter() - t0

a = timed(4000)
b = timed(4000)
print(f"  first pass {a*1000 if a else -1:.1f} ms, second pass {b*1000 if b else -1:.1f} ms, "
      f"identical_region={a and b and b < a * 0.6}")

k32.CloseHandle(h)
