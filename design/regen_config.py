# -*- coding: utf-8 -*-
"""把 preference_config.json 重排成 9 组（v0.30 设计）。

用现有条目做素材，只调整分组顺序、标题，并补 3 个新条目：
multiDiscFolder / annotateCueCrc / openLogFolder。
"""
import json
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = os.path.join(ROOT, "mod", "src", "main", "resources", "preference_config.json")

d = json.load(io.open(src, encoding="utf-8"))
by_key = {}
by_click = {}
for g in d["configs"]:
    for p in g["preferences"]:
        if "key" in p:
            by_key[p["key"]] = p
        if "on_click" in p:
            by_click[p["on_click"].split(".")[-1]] = p


def K(k):
    return dict(by_key[k])


def B(name):
    return dict(by_click[name])


multi = {
    "type": "edittext",
    "key": "multiDiscFolder",
    "title": "多碟子目录模板",
    "summary": "多碟套装时在专辑目录下再建一层；%discnumber% 会替换成碟号",
    "default_value": "CD%discnumber%",
}
cuecrc = {
    "type": "switch",
    "key": "annotateCueCrc",
    "title": "在 CUE 里附上每轨 CRC",
    "summary": "REM 行写入 CRC，方便和 EAC 日志对账",
    "default_value": True,
}
logbtn = {
    "type": "button",
    "on_click": "com.saltrip.rip.ConfigActions.openLogFolder",
    "title": "打开日志文件夹",
}

groups = [
    ("1 · 抓轨范围", [K("trackSelection")]),
    ("2 · 输出位置", [K("outputRoot"), B("chooseOutputRoot"), K("overwritePolicy"), K("resume"),
                  K("discNumberOverride"), K("totalDiscsOverride"), multi, K("ejectAfterRip")]),
    ("3 · 命名模板", [K("folderTemplate"), K("fileTemplate"), K("coverFileName")]),
    ("4 · 音频格式", [K("format"), K("imageMode"), K("flacExe"), B("chooseFlacExe"), K("flacLevel"),
                  K("keepWav"), K("encoderExe"), K("encoderArgs"), K("encoderExt")]),
    ("5 · 标签与附属文件", [K("writeTags"), K("embedCover"), K("coverSize"), K("writeWavTags"),
                       K("writeCue"), cuecrc, K("writeLog")]),
    ("6 · 元数据", [K("fetchMetadata"), K("useCdText"), K("preferTrackCount"), K("releaseIndex"),
                K("overrideFile"), K("reuseAlbumJson"), K("writeAlbumJson")]),
    ("7 · 光驱与校验", [K("driveLetter"), K("driveSpeed"), K("readCommand"), K("secureMode"),
                  K("testAndCopy"), K("errorRecovery"), K("maxRetries"), K("retryDelayMs"),
                  K("offsetSamples"), K("useC2"), K("defeatCache"), K("guardDisc")]),
    ("8 · 间隙", [K("detectGaps"), K("gapMethod"), K("gapMode"), K("analyzeBeforeRip")]),
    ("9 · 诊断", [K("testMode"), B("scanDrives"), B("previewPlan"), B("analyzeDisc"),
              B("probeReadCommands"), B("testDisc"), B("lookupMetadata"), B("exportAlbumJson"),
              B("ripFirstTrack"), B("cancelRip"), B("probePath"), B("resetDefaults"), logbtn]),
]

for _title, items in groups:
    for p in items:
        if p.get("key") == "testMode":
            p["title"] = "显示诊断工具"
            p["summary"] = "打开后，面板里才出现扫描/试写/预扫等真机诊断按钮"

out = {"configs": [{"title": t, "config": "config.json", "preferences": items}
                   for t, items in groups]}
txt = json.dumps(out, ensure_ascii=False, indent=2) + "\n"
io.open(src, "w", encoding="utf-8", newline="\n").write(txt)

ks = [p["key"] for g in out["configs"] for p in g["preferences"] if "key" in p]
bs = [p["on_click"].split(".")[-1] for g in out["configs"] for p in g["preferences"] if "on_click" in p]
print("groups", len(out["configs"]), "keys", len(ks), "unique", len(set(ks)), "buttons", len(bs))
print("missing", set(by_key) - set(ks), "extra", set(ks) - set(by_key))
print("dups", sorted({k for k in ks if ks.count(k) > 1}))
