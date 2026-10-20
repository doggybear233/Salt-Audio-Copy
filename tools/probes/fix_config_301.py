# -*- coding: utf-8 -*-
"""把 trackSelection 移进「诊断」组，删掉空的「抓轨范围」组，重排 1..8。"""
import io
import json

P = r"D:\deepseek\plugin\Salt Audio Copy\mod\src\main\resources\preference_config.json"
d = json.load(io.open(P, encoding="utf-8"))

track = None
for g in d["configs"]:
    for it in g["preferences"]:
        if it.get("key") == "trackSelection":
            track = it
for g in d["configs"]:
    g["preferences"] = [i for i in g["preferences"] if i.get("key") != "trackSelection"]

diag = next(g for g in d["configs"] if "诊断" in g["title"])
diag["preferences"].insert(0, track)

groups = [g for g in d["configs"] if g["preferences"]]
for i, g in enumerate(groups, 1):
    tail = g["title"].split("·", 1)[-1].strip()
    g["title"] = "%d · %s" % (i, tail)
d["configs"] = groups

io.open(P, "w", encoding="utf-8", newline="\n").write(json.dumps(d, ensure_ascii=False, indent=2) + "\n")

n = sum(1 for g in groups for i in g["preferences"] if i.get("type") != "button")
b = sum(1 for g in groups for i in g["preferences"] if i.get("type") == "button")
print("groups=%d keys=%d buttons=%d" % (len(groups), n, b))
for g in groups:
    print(" ", g["title"], [i.get("key") or i.get("on_click", "").split(".")[-1] for i in g["preferences"]])
