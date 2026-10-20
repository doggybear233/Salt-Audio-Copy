# -*- coding: utf-8 -*-
"""从 preference_config.json 重新生成 options-table.md（v2，对应 1.0.0）。

v1 的说明文字（取值/默认/作用）按 key 复用，存在 design/options-table-v1.md。
"""
import io
import json
import os

ROOT = r"D:\deepseek\plugin\Salt Audio Copy"
cfg_path = os.path.join(ROOT, "mod", "src", "main", "resources", "preference_config.json")
v1_path = os.path.join(ROOT, "design", "options-table-v1.md")
out_path = os.path.join(ROOT, "options-table.md")

cfg = json.load(io.open(cfg_path, encoding="utf-8"))
v1 = io.open(v1_path, encoding="utf-8").read()

old = {}
for line in v1.splitlines():
    if not line.startswith(("|", "#|")):
        continue
    cells = [c.strip() for c in line.lstrip("#").strip().strip("|").split("|")]
    if len(cells) < 8 or not cells[2] or cells[2] in ("key", "---"):
        continue
    old[cells[2].strip("`")] = (cells[4], cells[5], cells[6])

TYPE_CN = {"switch": "开关", "list": "单选", "edittext": "输入", "button": "按钮"}

# v1 里引用旧分组编号（如「同 A9」）或说法过时的，直接覆盖
OVERRIDE = {
    "outputRoot": ("任意路径；空 = 系统「音乐」", "抓轨产物根目录；面板里点这一行也能改"),
    "folderTemplate": ("如 `%albumartist%/%albumtitle%`", "专辑目录结构；点框再点变量插入光标处"),
    "fileTemplate": ("如 `%tracknr2% %title%`", "音轨文件名；点框再点变量插入光标处"),
    "coverFileName": ("不含扩展名", "封面文件名；点框再点变量插入光标处"),
    "multiDiscFolder": ("如 `CD%discnumber%`", "总碟数 >1 时在专辑目录下再套一层"),
}

BUTTON_EFFECT = {
    "scanDrives": "重新枚举光驱",
    "previewPlan": "算出目录/文件名/预计耗时，不写盘",
    "analyzeDisc": "整盘扫 INDEX 00（只读）",
    "probeReadCommands": "逐条试读命令，报告哪些可用",
    "testDisc": "读完整张盘但不写文件",
    "lookupMetadata": "只联网查元数据并列出候选",
    "exportAlbumJson": "导出可手改的元数据模板",
    "ripFirstTrack": "只抓第 1 轨验证链路",
    "cancelRip": "中止当前抓轨（已写文件保留）",
    "probePath": "在含中文的路径下试写一小段",
    "resetDefaults": "把全部设置恢复默认",
    "openLogFolder": "打开日志所在文件夹",
    "testNetwork": "并发探测 MusicBrainz / Cover Art / CDDB，判断是没记录还是网不通",
}

out = []

n = 0
btn = 0
for g in cfg["configs"]:
    prefs = g["preferences"]
    ks = [p for p in prefs if p.get("type") != "button"]
    bs = [p for p in prefs if p.get("type") == "button"]
    out.append("## %s（%d 设置 + %d 按钮）" % (g["title"], len(ks), len(bs)))
    out.append("")
    out.append("| 序号 | 选项名（界面原文） | key | 控件 | 取值 / 格式 | 默认 | 作用 | 改法（你填） |")
    out.append("| --- | --- | --- | --- | --- | --- | --- | --- |")
    for p in prefs:
        t = p.get("type")
        title = p.get("title", "")
        if t == "button":
            btn += 1
            fn = str(p.get("on_click", "")).split(".")[-1]
            o = old.get(fn, ("", "", ""))
            out.append("| B%d | %s | — | 按钮 | `ConfigActions.%s` | — | %s |  |" % (btn, title, fn, BUTTON_EFFECT.get(fn, "执行动作")))
            continue
        n += 1
        key = p.get("key", "")
        o = old.get(key, ("", "", ""))
        dv = p.get("default_value")
        if t == "switch":
            fmt, dflt = "开 / 关", ("开" if dv else "关")
        elif t == "list":
            labels = p.get("entries") or []
            vals = [str(v) for v in (p.get("entry_values") or [])]
            fmt = " / ".join("%s（%s）" % (a, b) for a, b in zip(labels, vals)) if labels else " / ".join(vals)
            dflt = str(dv)
        else:
            fmt = o[0] or "任意文本"
            dflt = "（空）" if dv in (None, "") else "`" + str(dv) + "`"
        if key in OVERRIDE:
            fmt, _ = OVERRIDE[key]
        effect = OVERRIDE[key][1] if key in OVERRIDE else o[2]
        out.append("| %d | %s | `%s` | %s | %s | %s | %s |  |" % (n, title, key, TYPE_CN.get(t, t), fmt, dflt, effect))
    out.append("")

out.append("## B 面板设置（8 页二级导航）")
out.append("")
out.append("| 页 | 标题 | 项数 | 默认可见 |")
out.append("| --- | --- | --- | --- |")
for pid, title, cnt, note in [
    ("P1", "输出位置", 8, "是（默认停这页）"),
    ("P2", "命名模板", 3, "是"),
    ("P3", "音频格式", 10, "条件：FLAC / 外部编码器相关项按格式显隐"),
    ("P4", "标签与附属文件", 7, "条件：按格式显隐"),
    ("P5", "元数据", 7, "条件：关掉联网取元数据后隐藏 3 项"),
    ("P6", "光驱与校验", 12, "条件：关掉安全双读后隐藏重试三项"),
    ("P7", "间隙", 4, "条件：关掉间隙检测后隐藏三项"),
    ("P8", "诊断", 13, "是"),
]:
    out.append("| %s | %s | %d | %s |" % (pid, title, cnt, note))
out.append("")
out.append("面板不再重复实现一套控件：点「设置」直接进这 8 页，改的是同一份 `RipSettings`。")
out.append("")
out.append("## C 已移除 / 仍隐藏")
out.append("")
out.append("| key | 状态 | 说明 |")
out.append("| --- | --- | --- |")
out.append("| `testMode` | 已移除 | 0.30.1 删掉「显示诊断工具」：诊断页动作常驻 |")
out.append("| `appearance` | 已移除 | 0.29.7 删掉：面板跟随宿主主题，不给用户选颜色 |")
out.append("| `includeGaps` | 已移除 | 被 `gapMode`（append/prepend/leaveout）取代 |")
out.append("")
out.append("## D 与 v1 的差异（本版重排结果）")
out.append("")
out.append("- 4 组 → 8 组；`ripAlbum`/`pauseRip` 从配置页移到面板（只在面板出现）。")
out.append("- 新增两个原本隐藏的键：`multiDiscFolder`（多碟子目录模板）、`annotateCueCrc`（CUE 写 CRC）。")
out.append("- 新增按钮「打开日志文件夹」；`chooseOutputRoot`/`chooseFlacExe` 并入各自的行。")
out.append("- 51 键 = 原 48 + 新增 3（multiDiscFolder / annotateCueCrc / proxyUrl）；15 按钮 = 原 15 - 移到面板的 2（ripAlbum/pauseRip）+ 新增 2（openLogFolder / testNetwork）。")
out.append("")
# 头部放最后补：键数要等统计完才知道（0.31.0 加了 autoLookupOnInsert）。
out[0:0] = [
    "# 盐专辑（Salt Audio Copy）配置界面选项统计表 v2（1.0.0）",
    "",
    "> 1.0.0 起配置页为 **%d 组 / %d 个设置键 / %d 个按钮**（已移除「导入编码器」相关选项）；面板内是同一份数据的 8 页二级导航。"
    % (len(cfg["configs"]), n, btn),
    "> 本表由 `design/options_table.py` 从 `mod/src/main/resources/preference_config.json` 生成，与代码同步。",
    "> 末列「改法（你填）」留空；要改就在那格写（行首加 `#` 表示删掉这一项）。",
    "",
]
io.open(out_path, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
print("rows=%d buttons=%d groups=%d" % (n, btn, len(cfg["configs"])))
