# -*- coding: utf-8 -*-
"""0.30.1：把「只抓这些音轨」挪进诊断组、删掉「显示诊断工具」，组号重排为 1..8。

直接改现有 JSON（保留原有中文标题与 summary），不重建条目。
"""
import io
import json
import os

ROOT = r"D:\deepseek\plugin\Salt Audio Copy"
p = os.path.join(ROOT, "mod", "src", "main", "resources", "preference_config.json")
d = json.load(io.open(p, encoding="utf-8"))

groups = d["configs"]
by_key = {}
for g in groups:
    for it in g["preferences"]:
        if "key" in it:
            by_key[it["key"]] = it

track = by_key["trackSelection"]
groups = [g for g in groups if not any(i.get("key") == "trackSelection" for i in g["preferences"])]
diag = next(g for g in groups if any(i.get("key") == "testMode" for i in g["preferences"]))
diag["preferences"] = [i for i in diag["preferences"] if i.get("key") != "testMode"]
diag["preferences"].insert(0, track)

for i, g in enumerate(groups, 1):
    tail = g["title"].split("·", 1)[-1].strip()
    g["title"] = "%d · %s" % (i, tail)

io.open(p, "w", encoding="utf-8", newline="\n").write(
    json.dumps(d, ensure_ascii=False, indent=2) + "\n"
)
n = sum(1 for g in groups for i in g["preferences"] if i.get("type") != "button")
b = sum(1 for g in groups for i in g["preferences"] if i.get("type") == "button")
print("groups=%d keys=%d buttons=%d" % (len(groups), n, b))
for g in groups:
    print(" ", g["title"])
