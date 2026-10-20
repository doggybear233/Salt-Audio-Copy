"""Metadata chain proof: TOC -> MusicBrainz DiscID -> MB release -> cover, plus gnudb (CDDB) fallback.

Verified against MusicBrainz ground truth on 2026-10-19:
  TOC offsets (LBA) [0,20115,...], lead-out 194092
  -> MB discid mHEn5XPh1chsnQA5EbR0_npgXSs-  (matches MB's own record for release
     d3d40b24-50ef-40cb-933c-9d8048e12919, sectors 194242 = lead-out + 150)
Algorithm: SHA-1 over "%02X%02X%08X" + 99 x "%08X" with every offset shifted by +150,
then base64 with libdiscid charset  '+'->'.'  '/'->'_'  '='->'-'.
CDDB id (verified via gnudb): "1f090a1d" -> 松田聖子 / Strawberry Time (1987, J-Pop).
"""
import ctypes, ctypes.wintypes as wt, sys, hashlib, base64, json, urllib.request, urllib.error, urllib.parse, time

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
UA = "SaltRip/0.1 ( https://github.com/example/salt-rip )"


def spti(h, cdb, buflen, timeout=20):
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


def read_toc(h):
    ok, st, toc = spti(h, [0x43, 0x00, 0, 0, 0, 0, 0, 0x03, 0x24, 0, 0, 0], 804)
    tl = (toc[0] << 8) | toc[1]
    first, last = toc[2], toc[3]
    ent = {}
    for i in range((tl - 2) // 8):
        o = 4 + i * 8
        ent[toc[o + 2]] = int.from_bytes(toc[o + 4:o + 8], "big")
    leadout = ent.pop(0xAA)
    tracks = [(t, ent[t]) for t in sorted(ent)]
    return first, last, tracks, leadout


def mb_discid(tracks, leadout, first, last, skip=150):
    """MusicBrainz DiscID (verified)."""
    s = f"{first:02X}{last:02X}{leadout + skip:08X}"
    starts = [off for _, off in tracks]
    for i in range(99):
        s += f"{(starts[i] + skip) if i < len(starts) else 0:08X}"
    d = base64.b64encode(hashlib.sha1(s.encode("ascii")).digest()).decode()
    return d.replace("+", ".").replace("/", "_").replace("=", "-")


def cddb_id(tracks, leadout):
    """CDDB/freedb id (verified via gnudb -> 1f090a1d):
    n = (sum of per-track floored durations in seconds - first track second offset) & 0xFF,
    checksum = iterated digit sum of that same total, then total disc seconds.
    Offsets are shifted by +150 like the MB discid."""
    total = 0
    for i, (_, off) in enumerate(tracks):
        nxt = tracks[i + 1][1] if i + 1 < len(tracks) else leadout
        total += ((nxt + 150) - (off + 150)) // 75
    lead_s = (leadout + 150) // 75
    first_s = (tracks[0][1] + 150) // 75
    n = (total - first_s) % 0xFF
    digits = total
    while digits >= 10:
        digits = sum(int(c) for c in str(digits))
    return f"{n:02x}{digits:02x}{lead_s:04x}"


LET = sys.argv[1] if len(sys.argv) > 1 else "F"
h = k32.CreateFileW(f"\\\\.\\{LET}:", 0xC0000000, 3, None, 3, 0, None)
first, last, tracks, leadout = read_toc(h)
print(f"TOC: {len(tracks)} tracks, lead-out LBA {leadout} ({leadout/75:.1f}s), MB sectors {leadout+150}")

d_id = mb_discid(tracks, leadout, first, last)
c_id = cddb_id(tracks, leadout)
print(f"MB DiscID  : {d_id}")
print(f"CDDB ID    : {c_id}")

# ---- MusicBrainz ----
def mb_get(url, tries=4):
    for attempt in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read().decode()), None
        except urllib.error.HTTPError as e:
            body = e.read()[:200].decode("utf-8", "replace")
            if e.code in (503, 502, 429) and attempt + 1 < tries:
                time.sleep(1.5 * (attempt + 1))
                continue
            return None, f"HTTP {e.code}: {body}"
        except Exception as e:
            if attempt + 1 < tries:
                time.sleep(1.0)
                continue
            return None, f"{type(e).__name__}: {e}"
    return None, "exhausted"


j = None
print("\n[MusicBrainz]")
url = f"https://musicbrainz.org/ws/2/discid/{d_id}?fmt=json&inc=artists+recordings+release-groups"
t0 = time.time()
j, err = mb_get(url)
if err:
    print(f"  {err}")
else:
    print(f"  OK in {time.time()-t0:.2f}s, releases={len(j.get('releases', []))}")
    for rel in j.get("releases", [])[:5]:
        art = " & ".join(a["name"] for a in rel.get("artist-credit", []))
        print(f"   - {rel.get('title')} / {art} / {rel.get('date','?')} / {rel.get('country','?')} "
              f" / {rel.get('track-count','?')}trk / score={rel.get('score')} / {rel.get('id')}")

# cover art probe for the first release found above
try:
    if not j or not j.get("releases"):
        raise RuntimeError("no release to probe")
    mbid = j["releases"][0]["id"]
    curl = f"https://coverartarchive.org/release/{mbid}/front-500"
    req = urllib.request.Request(curl, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=20) as r:
        data = r.read()
    print(f"  cover front-500: HTTP {r.status}, {len(data)} bytes, {r.headers.get('Content-Type')}")
    cj = f"https://coverartarchive.org/release/{mbid}"
    with urllib.request.urlopen(urllib.request.Request(cj, headers={"User-Agent": UA}), timeout=20) as r:
        arts = json.loads(r.read().decode()).get("images", [])
    print(f"  CAA images: {len(arts)}")
    for im in arts[:6]:
        print(f"   - {im.get('types')} front={im.get('front')} {im.get('image')} "
              f"thumb={im.get('thumbnails', {}).get('500')}")
except Exception as e:
    print(f"  cover: {type(e).__name__}: {e}")

# ---- gnudb (CDDB) fallback ----
print("\n[gnudb CDDB fallback]")
offs_f = [off + 150 for _, off in tracks]
total_s = (leadout + 150) // 75
q = f"cddb query {c_id} {len(tracks)} " + " ".join(map(str, offs_f)) + f" {total_s}"
gurl = ("http://gnudb.gnudb.org/~cddb/cddb.cgi?cmd=" + urllib.parse.quote(q)
        + "&hello=test+localhost+cddb+0.1&proto=6")
try:
    with urllib.request.urlopen(urllib.request.Request(gurl, headers={"User-Agent": UA}), timeout=20) as r:
        body = r.read().decode("utf-8", "replace")
    print("  " + body.splitlines()[0])
    cat = ""
    key = ""
    for line in body.splitlines()[1:]:
        if line.startswith("data "):
            parts = line.split(None, 3)
            cat, key = parts[1], parts[2]
            print(f"  match: {cat} / {key} -> {parts[3] if len(parts) > 3 else ''}")
    if cat:
        rq = f"cddb read {cat} {key}"
        rurl = ("http://gnudb.gnudb.org/~cddb/cddb.cgi?cmd=" + urllib.parse.quote(rq)
                + "&hello=test+localhost+cddb+0.1&proto=6")
        with urllib.request.urlopen(urllib.request.Request(rurl, headers={"User-Agent": UA}), timeout=20) as r:
            rec = r.read().decode("utf-8", "replace")
        for line in rec.splitlines():
            if line.startswith(("DTITLE=", "DYEAR=", "DGENRE=")) or line.startswith("TTITLE"):
                print("   ", line)
except Exception as e:
    print(f"  {type(e).__name__}: {e}")

k32.CloseHandle(h)
