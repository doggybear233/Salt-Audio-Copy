"""Extended drive/disc capability probe: CD-TEXT, MCN/ISRC, subchannel Q, C2, cache, speed."""
import ctypes, ctypes.wintypes as wt, sys, time, zlib

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


def run(cdb, buflen, timeout=30, data_in=1):
    total = ctypes.sizeof(SPTD) + 32
    raw = (ctypes.c_ubyte * total)()
    data = (ctypes.c_ubyte * max(buflen, 1))()
    p = SPTD.from_buffer(raw)
    p.Length = ctypes.sizeof(SPTD)
    p.CdbLength = 16
    p.SenseInfoLength = 32
    p.DataIn = data_in
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


def cmd(name, cdb, buflen, timeout=30):
    ok, e, st, buf, sense = run(cdb, buflen, timeout)
    if not ok:
        print(f"  {name:28s} IOCTL FAIL err={e} ({ctypes.FormatError(e).strip()})")
        return None
    if st:
        print(f"  {name:28s} SCSI status={st:#04x} sense={sense[:16].hex(' ')}")
        return None
    print(f"  {name:28s} ok, {len(buf)} B: {buf[:32].hex(' ')}")
    return buf


print("=== 1. MODE SENSE(10) page 0x2A (CD/DVD capabilities & mechanical status) ===")
buf = cmd("mode sense 2A (alloc 64)", [0x5A, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00], 64)
if buf:
    hdr = int.from_bytes(buf[0:2], "big")
    print(f"    mode data length={hdr}")
    off = 8
    while off + 2 < len(buf) and buf[off] not in (0,):
        pc = buf[off] & 0x3F
        plen = buf[off + 1]
        print(f"    page 0x{pc:02x} len={plen} data={buf[off+2:off+2+plen].hex(' ')}")
        if pc == 0x2A and plen >= 20:
            d = buf[off + 2:off + 2 + plen]
            caps = int.from_bytes(d[0:2], "big")
            # SCSI MMC: byte0 bit0 DVD-ROM, bit1 DVD-R, ... ; byte1 bit0 CD-R, bit1 CD-RW, ...
            if plen >= 8:
                mech = int.from_bytes(d[4:6], "big") if plen >= 6 else 0
                print(f"      caps=0x{caps:04x} mech=0x{mech:04x}")
                # byte 6 (offset) = read/write speed support, byte 7
                if plen >= 8:
                    print(f"      byte6=0x{d[6]:02x} byte7=0x{d[7]:02x} byte8=0x{d[8]:02x}")
                # C2 flag is bit 4 of byte 2 for CD-DA (MMC2 "C2 Error Pointers")
                if plen >= 3:
                    b2 = d[2]
                    print(f"      byte2=0x{b2:02x}  C2 pointers={'yes' if b2 & 0x10 else 'no'}"
                          f"  CD-DA accurate stream(bit1)={bool(b2 & 0x02)}"
                          f"  CD-DA current(bit0)={bool(b2 & 0x01)}")
        off += 2 + plen
        if off >= len(buf):
            break

print("\n=== 2. READ TOC format 5 (CD-TEXT) ===")
for alloc in (4 + 0x100, 4 + 0x800, 4 + 0x1000):
    buf = cmd(f"toc fmt5 alloc={alloc}", [0x43, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x05, (alloc >> 8) & 0xFF, alloc & 0xFF], alloc)
    if buf:
        print(f"    data_len={(buf[0]<<8)|buf[1]}")
        break

print("\n=== 3. READ TOC format 2 (full TOC: MCN / ISRC) ===")
buf = cmd("toc fmt2 alloc=804", [0x43, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x02, 0x03, 0x24], 804)
if buf:
    tl = (buf[0] << 8) | buf[1]
    n = max(0, (tl - 2) // 11)
    print(f"    toc_len={tl}, entries={n}")
    for i in range(n):
        o = 4 + i * 11
        if o + 11 > len(buf):
            break
        sess, adr_ctl, tno, point, min_, sec, frame, pmin, psec, pframe = buf[o:o + 10]
        adr, ctl = adr_ctl >> 4, adr_ctl & 0x0F
        print(f"    #{i:02d} sess={sess} adr={adr} ctl={ctl} point=0x{point:02x} "
              f"msf={min_:02d}:{sec:02d}:{frame:02d} pmsf={pmin:02d}:{psec:02d}:{pframe:02d}")

print("\n=== 4. READ TOC format 3 (PMA) / format 4 (ATIP) ===")
for fmt, nm in ((0x03, "PMA"), (0x04, "ATIP")):
    cmd(f"toc fmt{fmt} ({nm})", [0x43, 0x00, 0x00, 0x00, 0x00, 0x00, fmt, fmt, 0x01, 0x00], 256)

print("\n=== 5. subchannel Q in audio read (0xBE, 2352+96 per sector) ===")
for flags, label in ((0xF8 | 0x01, "subQ"), (0xF8 | 0x04, "subQ raw")):
    ok, e, st, buf, sense = run([0xBE, 0x04, 0x00, 0x00, 0x03, 0xE8, 0, 0, 1, flags, 0, 0, 0, 0, 0, 0], 2352 + 96)
    if ok and not st:
        sub = buf[2352:]
        # de-interleave 96 bytes: 12 bytes x 8
        q = bytearray()
        for j in range(12):
            for k in range(8):
                q.append(sub[j * 8 + k])
        print(f"  flags={flags:#04x} ({label}): {len(buf)} B, subheader={sub[:4].hex(' ')}")
        print(f"    Q(0x{flags:02x})={q.hex(' ')}")
        if len(q) >= 12:
            print(f"    ctl/adr=0x{q[0]:02x} track={q[1]:02x} index={q[2]:02x} "
                  f"rel={q[3]:02x}:{q[4]:02x}:{q[5]:02x} abs={q[7]:02x}:{q[8]:02x}:{q[9]:02x}")
    else:
        print(f"  flags={flags:#04x} ({label}): ok={ok} err={e} st={st} sense={sense[:12].hex(' ')}")

print("\n=== 6. C2 error reporting (CDB[9] bit1, 2352+294 per sector) ===")
for flags in (0xFA, 0xF8 | 0x02):
    ok, e, st, buf, sense = run([0xBE, 0x04, 0x00, 0x00, 0x03, 0xE8, 0, 0, 1, flags, 0, 0, 0, 0, 0, 0], 2352 + 294)
    print(f"  flags={flags:#04x}: ok={ok} err={e} st={st} sense={sense[:12].hex(' ')}")
    if ok and not st:
        err_bytes = buf[2352:]
        print(f"    C2 bytes={len(err_bytes)} nonzero={sum(1 for b in err_bytes if b)} "
              f"head={err_bytes[:16].hex(' ')}")

print("\n=== 7. cache behaviour (EAC-style: read A, read B, read A again) ===")
def read1(lba):
    ok, e, st, buf, _ = run([0xBE, 0x04, (lba >> 24) & 0xFF, (lba >> 16) & 0xFF, (lba >> 8) & 0xFF,
                             lba & 0xFF, 0, 0, 16, 0xF8, 0, 0, 0, 0, 0, 0], 2352 * 16)
    return ok and not st, buf

for label, seq in (("A-B-A", [(1000, 16), (3000, 16), (1000, 16)]),
                   ("A-A", [(1000, 16), (1000, 16)])):
    times = []
    crcs = []
    for lba, n in seq:
        t0 = time.perf_counter()
        ok, buf = read1(lba)
        times.append((time.perf_counter() - t0) * 1000)
        crcs.append(zlib.crc32(buf) if ok else None)
    print(f"  {label}: times={[f'{t:.1f}ms' for t in times]} crcs_equal={len(set(crcs)) == 1}")

print("\n=== 8. speed control (512 sectors, 16/chunk) ===")
def bench(label, nsec=512):
    t0 = time.perf_counter()
    got = 0
    for i in range(0, nsec, 16):
        ok, buf = read1(5000 + i)
        if not ok:
            break
        got += 16
    dt = time.perf_counter() - t0
    print(f"  {label:22s}: {got} sectors in {dt:.2f}s -> {got/dt:6.0f} sec/s = {got/dt/150:4.1f}x")

bench("default speed")
for kb, tag in ((0xFFFF, "SET SPEED max"), (0x0BB8, "SET SPEED 3000KB/s"), (0x04B0, "SET SPEED 1200KB/s")):
    ok, e, st, _, _ = run([0xBB, 0, 0, 0, (kb >> 8) & 0xFF, kb & 0xFF, 0xFF, 0xFF, 0, 0, 0, 0], 0)
    print(f"  {tag}: ok={ok} st={st}")
    bench(tag)

k32.CloseHandle(h)
