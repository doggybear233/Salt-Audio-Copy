"""汇总 mod/build/test-results/test/*.xml 的用例数（跨批次自检用）。

用法：cd 'D:\\deepseek\\plugin\\Salt Audio Copy'; python tools\\test-summary.py
输出：tests / failures / errors / skipped / files，以及失败用例名。
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

root = os.path.join("mod", "build", "test-results", "test")
files = sorted(glob.glob(os.path.join(root, "*.xml")))
if not files:
    print("no test results under", root)
    sys.exit(2)

tests = failures = errors = skipped = 0
bad = []
for f in files:
    t = ET.parse(f).getroot()
    tcount = int(t.get("tests", 0))
    tests += tcount
    failures += int(t.get("failures", 0))
    errors += int(t.get("errors", 0))
    skipped += int(t.get("skipped", 0))
    for case in t.iter("testcase"):
        if case.find("failure") is not None or case.find("error") is not None:
            bad.append(f"{case.get('classname')}.{case.get('name')}")

print(f"files={len(files)} tests={tests} failures={failures} errors={errors} skipped={skipped}")
for b in bad:
    print("FAILED:", b)
