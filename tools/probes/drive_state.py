import ctypes, ctypes.wintypes as w, sys, threading, time

GENERIC_READ = 0x80000000
GENERIC_WRITE = 0x40000000
OPEN_EXISTING = 3
FILE_SHARE_READ = 1
FILE_SHARE_WRITE = 2

k32 = ctypes.WinDLL('kernel32', use_last_error=True)
k32.CreateFileW.restype = w.HANDLE
k32.CreateFileW.argtypes = [w.LPCWSTR, w.DWORD, w.DWORD, w.LPVOID, w.DWORD, w.DWORD, w.HANDLE]

result = {}


def probe():
    t0 = time.time()
    h = k32.CreateFileW('\\\\.\\F:', GENERIC_READ | GENERIC_WRITE,
                        FILE_SHARE_READ | FILE_SHARE_WRITE, None, OPEN_EXISTING, 0, None)
    result['create_ms'] = int((time.time() - t0) * 1000)
    result['handle'] = h
    if h == w.HANDLE(-1).value or h is None:
        result['err'] = ctypes.get_last_error()
        return
    # 读卷信息（走文件系统，最容易被卡住的那条路）
    t1 = time.time()
    buf = ctypes.create_unicode_buffer(261)
    ok = k32.GetVolumeInformationW('F:\\', buf, 261, None, None, None, None, 0)
    result['vol_ms'] = int((time.time() - t1) * 1000)
    result['vol_ok'] = bool(ok)
    result['vol_err'] = ctypes.get_last_error()
    k32.CloseHandle(h)


th = threading.Thread(target=probe, daemon=True)
th.start()
th.join(12)
if th.is_alive():
    print('HUNG: 12 秒内没有返回（设备/驱动层被卡住）')
else:
    print(result)
