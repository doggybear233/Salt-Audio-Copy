"""Which raw-CD access paths work without elevation?
Tests: CreateFile access rights, IOCTL_CDROM_READ_TOC, IOCTL_CDROM_RAW_READ,
IOCTL_SCSI_GET_ADDRESS, IOCTL_CDROM_GET_DRIVE_GEOMETRY, and SPTI with RW.
"""
import ctypes, ctypes.wintypes as wt, sys, struct

sys.stdout.reconfigure(encoding="utf-8")

k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.CreateFileW.restype = wt.HANDLE
k32.CreateFileW.argtypes = [wt.LPCWSTR, wt.DWORD, wt.DWORD, ctypes.c_void_p,
                            wt.DWORD, wt.DWORD, wt.HANDLE]
k32.DeviceIoControl.restype = wt.BOOL
k32.DeviceIoControl.argtypes = [wt.HANDLE, wt.DWORD, ctypes.c_void_p, wt.DWORD,
                                ctypes.c_void_p, wt.DWORD, ctypes.POINTER(wt.DWORD),
                                ctypes.c_void_p]
k32.CloseHandle.argtypes = [wt.HANDLE]

GENERIC_READ = 0x80000000
GENERIC_WRITE = 0x40000000
IOCTL_CDROM_READ_TOC = 0x00024000
IOCTL_CDROM_RAW_READ = 0x0002403E
IOCTL_SCSI_GET_ADDRESS = 0x00041018
IOCTL_CDROM_GET_DRIVE_GEOMETRY = 0x0002404C
IOCTL_CDROM_GET_CONFIGURATION = 0x00024058

letter = sys.argv[1] if len(sys.argv) > 1 else "F"
path = f"\\\\.\\{letter}:"

for access, label in ((GENERIC_READ, "READ"), (GENERIC_READ | GENERIC_WRITE, "READ|WRITE")):
    h = k32.CreateFileW(path, access, 3, None, 3, 0, None)
    ok = h not in (None, wt.HANDLE(-1).value)
    err = ctypes.get_last_error()
    print(f"CreateFile({label}) -> {'OK' if ok else f'FAIL err={err} ({ctypes.FormatError(err).strip()})'}")
    if ok:
        for ioctl, name, inlen, outlen in (
            (IOCTL_CDROM_READ_TOC, "READ_TOC", 0, 1024),
            (IOCTL_CDROM_GET_DRIVE_GEOMETRY, "GEOMETRY", 0, 32),
            (IOCTL_CDROM_GET_CONFIGURATION, "GET_CONFIG", 8, 1024),
            (IOCTL_SCSI_GET_ADDRESS, "SCSI_GET_ADDRESS", 0, 16),
        ):
            inbuf = (ctypes.c_ubyte * max(inlen, 1))()
            outbuf = (ctypes.c_ubyte * outlen)()
            ret = wt.DWORD(0)
            r = k32.DeviceIoControl(h, ioctl, inbuf, inlen, outbuf, outlen,
                                    ctypes.byref(ret), None)
            e = ctypes.get_last_error()
            print(f"   {name}: {'ok' if r else f'fail err={e} ({ctypes.FormatError(e).strip()})'}"
                  f" returned={ret.value}")
            if r and name == "GEOMETRY":
                cyl, mt, tps, spc, bps = struct.unpack_from("<iIIII", bytes(outbuf), 0)
                print(f"      geometry: bytes/sector={bps} sectors/track={tps} "
                      f"tracks/cyl={spc} media_type={mt}")
            if r and name == "SCSI_GET_ADDRESS":
                b = bytes(outbuf)
                print(f"      bus={b[4]} target={b[6]} lun={b[5]}")
        k32.CloseHandle(h)
