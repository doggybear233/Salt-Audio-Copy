import hashlib, base64

def mb(first,last,lead,tracks):
    s = "%02X%02X%08X" % (first,last,lead+150)
    d = {t:o for t,o in tracks}
    for i in range(1,100):
        s += "%08X" % ((d[i]+150) if i in d else 0) if i in d else "00000000"
    h = hashlib.sha1(s.encode("ascii")).digest()
    b = base64.b64encode(h).decode()
    return b.replace("+",".").replace("/","_").replace("=","-")

def cddb(offsets, lead):
    ob = [o+150 for o in offsets]
    lo = lead+150
    total = 0
    for i,o in enumerate(ob):
        nxt = ob[i+1] if i+1 < len(ob) else lo
        total += (nxt-o)//75
    n = (total - ob[0]//75) % 255
    crc = sum(int(c) for c in str(total))
    return "%02x%02x%04x" % (n, len(ob), crc), total

print("case A", mb(1,2,10000,[(1,0),(2,5000)]), cddb([0,5000],10000))
print("case B", mb(1,3,224166,[(1,0),(2,30000),(3,60000)]), cddb([0,30000,60000],224166))
