import ctypes, ctypes.wintypes as wt, sys
sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
IOCTL = 0x0004D014
class SPTD(ctypes.Structure):
    _fields_=[("Length",ctypes.c_ushort),("ScsiStatus",ctypes.c_ubyte),("PathId",ctypes.c_ubyte),
              ("TargetId",ctypes.c_ubyte),("Lun",ctypes.c_ubyte),("CdbLength",ctypes.c_ubyte),
              ("SenseInfoLength",ctypes.c_ubyte),("DataIn",ctypes.c_ubyte),
              ("DataTransferLength",ctypes.c_ulong),("TimeOutValue",ctypes.c_ulong),
              ("DataBuffer",ctypes.c_void_p),("SenseInfoOffset",ctypes.c_ulong),
              ("Cdb",ctypes.c_ubyte*16)]
k32=ctypes.WinDLL("kernel32",use_last_error=True)
k32.CreateFileW.restype=wt.HANDLE
k32.CreateFileW.argtypes=[wt.LPCWSTR,wt.DWORD,wt.DWORD,ctypes.c_void_p,wt.DWORD,wt.DWORD,wt.HANDLE]
k32.DeviceIoControl.restype=wt.BOOL
k32.DeviceIoControl.argtypes=[wt.HANDLE,wt.DWORD,ctypes.c_void_p,wt.DWORD,ctypes.c_void_p,wt.DWORD,ctypes.POINTER(wt.DWORD),ctypes.c_void_p]
h=k32.CreateFileW("\\\\.\\F:",0xC0000000,3,None,3,0,None)
def run(cdb,buflen):
    total=ctypes.sizeof(SPTD)+32
    raw=(ctypes.c_ubyte*total)(); data=(ctypes.c_ubyte*max(buflen,16))()
    p=SPTD.from_buffer(raw); p.Length=ctypes.sizeof(SPTD); p.CdbLength=12; p.SenseInfoLength=32
    p.DataIn=1; p.DataTransferLength=buflen; p.TimeOutValue=20
    p.DataBuffer=ctypes.cast(data,ctypes.c_void_p); p.SenseInfoOffset=ctypes.sizeof(SPTD)
    for i,b in enumerate(cdb): p.Cdb[i]=b
    ret=wt.DWORD(0); ok=k32.DeviceIoControl(h,IOCTL,raw,total,raw,total,ctypes.byref(ret),None)
    return ok, bytes(data), bytes(raw[ctypes.sizeof(SPTD):ctypes.sizeof(SPTD)+18])
# format 5, various start tracks / alloc
for start in (0,1):
    for alloc in (0x0400,0x1000):
        cdb=[0x43,0x00,0x05,0,0,0,start,(alloc>>8)&0xFF,alloc&0xFF,0,0,0]
        ok,data,sense=run(cdb,alloc)
        nonz=[(i,b) for i,b in enumerate(data) if b]
        print(f"fmt5 start={start} alloc={alloc:#x} ok={ok} nonzero={len(nonz)} head={data[:16].hex(' ')} sense={sense.hex(' ')}")
        if nonz[:8]: print("    first nonzero:", nonz[:8])
# also format 0 with alloc big for sanity
cdb=[0x43,0x00,0x00,0,0,0,0,0x04,0x00,0,0,0]
ok,data,sense=run(cdb,0x0400)
print("fmt0 sanity ok=",ok,"len=",int.from_bytes(data[0:2],'big'))
h and k32.CloseHandle(h)
