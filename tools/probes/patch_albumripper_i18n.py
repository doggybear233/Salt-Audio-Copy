# -*- coding: utf-8 -*-
"""把 AlbumRipper 里会显示到面板的文案包成 Strings.message / Strings.fmt。

日志（Diag.log）保持中文——日志是给我们看的，不做翻译。
"""
import io

P = "src/main/kotlin/com/saltrip/rip/album/AlbumRipper.kt"

PAIRS = [
    ('onStage("读取元数据…")',
     'onStage(Strings.message("读取元数据…"))'),
    ('warn("CD-TEXT 读取失败: ${it.message}")',
     'warn(Strings.fmt("CD-TEXT 读取失败: %s", it.message))'),
    ('notes += "使用上次保存的元数据（album.json）"',
     'notes += Strings.message("使用上次保存的元数据（album.json）")'),
    ('warn("元数据查询异常: ${it.message}")',
     'warn(Strings.fmt("元数据查询异常: %s", it.message))'),
    ('notes += "已关闭联网元数据查询"',
     'notes += Strings.message("已关闭联网元数据查询")'),
    ('notes += "使用盘片 CD-TEXT 命名"',
     'notes += Strings.message("使用盘片 CD-TEXT 命名")'),
    ('"没有选中任何音轨（规则：${settings.trackSelection.ifBlank { "全部" }}，盘上有 ${availableTracks.size} 轨）"',
     'Strings.fmt(\n'
     '                "没有选中任何音轨（规则：%s，盘上有 %s 轨）",\n'
     '                settings.trackSelection.ifBlank { Strings.message("全部") },\n'
     '                availableTracks.size\n'
     '            )'),
    ('notes += "按选择规则抓取：${TrackSelection.describe(picked)}"',
     'notes += Strings.fmt("按选择规则抓取：%s", TrackSelection.describe(picked))'),
    ('onStage("探测 INDEX 00 间隙…")',
     'onStage(Strings.message("探测 INDEX 00 间隙…"))'),
    ('warn("间隙预扫失败（抓轨时逐轨再试）: ${it.message}")',
     'warn(Strings.fmt("间隙预扫失败（抓轨时逐轨再试）: %s", it.message))'),
    ('onStage("测试读取第 ${entry.track} 轨…")',
     'onStage(Strings.fmt("测试读取第 %s 轨…", entry.track))'),
    ('warn(DiscGuard.mismatchMessage(toc.leadOutLba, now.leadOutLba, "第 ${entry.track} 轨之前"))',
     'warn(\n'
     '                        DiscGuard.mismatchMessage(\n'
     '                            toc.leadOutLba, now.leadOutLba,\n'
     '                            Strings.fmt("第 %s 轨之前", entry.track)\n'
     '                        )\n'
     '                    )'),
    ('notes += "第 ${entry.track} 轨目标文件已存在，按设置跳过"',
     'notes += Strings.fmt("第 %s 轨目标文件已存在，按设置跳过", entry.track)'),
    ('onStage("抓取第 ${entry.track} 轨：$title")',
     'onStage(Strings.fmt("抓取第 %s 轨：%s", entry.track, title))'),
    ('warn(\n'
     '                        "第 ${entry.track} 轨测试/复制 CRC 不一致（0x%08X vs 0x%08X），已重抓"\n'
     '                            .format(expected, r.crc32)\n'
     '                    )',
     'warn(\n'
     '                        Strings.fmt(\n'
     '                            "第 %s 轨测试/复制 CRC 不一致（0x%08X vs 0x%08X），已重抓",\n'
     '                            entry.track, expected, r.crc32\n'
     '                        )\n'
     '                    )'),
    ('warn("第 ${entry.track} 轨重抓后仍与测试通道不一致（0x%08X）".format(again.crc32))',
     'warn(Strings.fmt("第 %s 轨重抓后仍与测试通道不一致（0x%08X）", entry.track, again.crc32))'),
    ('notes += "第 ${entry.track} 轨重抓后与测试通道一致"',
     'notes += Strings.fmt("第 %s 轨重抓后与测试通道一致", entry.track)'),
    ('warn("offset 校正失败: ${it.message}")',
     'warn(Strings.fmt("offset 校正失败: %s", it.message))'),
    ('warn("第 ${entry.track} 轨 WAV 标签写入失败（音频不受影响）: ${it.message}")',
     'warn(Strings.fmt("第 %s 轨 WAV 标签写入失败（音频不受影响）: %s", entry.track, it.message))'),
    ('warn("第 ${entry.track} 轨外部编码失败（已保留 WAV）: ${e.message}")',
     'warn(Strings.fmt("第 %s 轨外部编码失败（已保留 WAV）: %s", entry.track, e.message))'),
    ('warn("第 ${entry.track} 轨标签写入失败（音频不受影响）: ${it.message}")',
     'warn(Strings.fmt("第 %s 轨标签写入失败（音频不受影响）: %s", entry.track, it.message))'),
    ('warn("第 ${entry.track} 轨 FLAC 编码失败（已保留 WAV）: ${e.message}")',
     'warn(Strings.fmt("第 %s 轨 FLAC 编码失败（已保留 WAV）: %s", entry.track, e.message))'),
    ('notes += "间隙处理为 ${settings.gapMode} 时音轨边界不连续，已跳过整盘镜像"',
     'notes += Strings.fmt(\n'
     '                "间隙处理为 %s 时音轨边界不连续，已跳过整盘镜像",\n'
     '                settings.gapMode\n'
     '            )'),
    ('onStage("生成整盘镜像…")',
     'onStage(Strings.message("生成整盘镜像…"))'),
    ('notes += "整盘镜像目标已存在，按设置跳过拼接（逐轨文件保留）"',
     'notes += Strings.message("整盘镜像目标已存在，按设置跳过拼接（逐轨文件保留）")'),
    ('warn("整盘镜像 FLAC 编码失败（已保留 WAV）: ${it.message}")',
     'warn(Strings.fmt("整盘镜像 FLAC 编码失败（已保留 WAV）: %s", it.message))'),
    ('warn("整盘镜像拼接失败（逐轨文件保留）: ${it.message}")',
     'warn(Strings.fmt("整盘镜像拼接失败（逐轨文件保留）: %s", it.message))'),
    ('warn("封面写入失败: ${it.message}")',
     'warn(Strings.fmt("封面写入失败: %s", it.message))'),
    ('warn("封面内嵌失败（音频与封面文件不受影响）: ${it.message}")',
     'warn(Strings.fmt("封面内嵌失败（音频与封面文件不受影响）: %s", it.message))'),
    ('warn("CUE 写入失败: ${it.message}")',
     'warn(Strings.fmt("CUE 写入失败: %s", it.message))'),
    ('warn("日志写入失败: ${it.message}")',
     'warn(Strings.fmt("日志写入失败: %s", it.message))'),
    ('notes += "元数据副本: ${p.fileName}"',
     'notes += Strings.fmt("元数据副本: %s", p.fileName)'),
    ('warn("元数据副本写入失败: ${it.message}")',
     'warn(Strings.fmt("元数据副本写入失败: %s", it.message))'),
    ('notes += "错误报告: ${p.fileName}"',
     'notes += Strings.fmt("错误报告: %s", p.fileName)'),
    ('notes += "弹出托盘失败: ${it.message}"',
     'notes += Strings.fmt("弹出托盘失败: %s", it.message)'),
]

TEMPLATES = {
    "读取元数据…": ["Reading metadata…", "メタデータを読み取り中…"],
    "CD-TEXT 读取失败: %s": ["CD-TEXT read failed: %s", "CD-TEXT の読み取りに失敗：%s"],
    "使用上次保存的元数据（album.json）": ["Using the metadata saved last time (album.json)",
                                           "前回保存したメタデータを使用（album.json）"],
    "元数据查询异常: %s": ["Metadata lookup error: %s", "メタデータ検索で例外：%s"],
    "已关闭联网元数据查询": ["Online metadata lookup is off", "オンラインメタデータ検索は無効です"],
    "使用盘片 CD-TEXT 命名": ["Naming from the disc's CD-TEXT", "ディスクの CD-TEXT から命名します"],
    "全部": ["all", "すべて"],
    "没有选中任何音轨（规则：%s，盘上有 %s 轨）":
        ["No track is selected (rule: %s, the disc has %s tracks)",
         "トラックが選択されていません（規則：%s、ディスクは %s トラック）"],
    "按选择规则抓取：%s": ["Ripping by the selection rule: %s", "選択規則で取得：%s"],
    "探测 INDEX 00 间隙…": ["Detecting INDEX 00 gaps…", "INDEX 00 ギャップを検出中…"],
    "间隙预扫失败（抓轨时逐轨再试）: %s":
        ["Gap pre-scan failed (will retry per track while ripping): %s",
         "ギャップの事前スキャンに失敗（取得中にトラックごとに再試行します）：%s"],
    "测试读取第 %s 轨…": ["Test-reading track %s…", "%s トラック目をテスト読み取り中…"],
    "第 %s 轨之前": ["before track %s", "%s トラック目の前"],
    "第 %s 轨目标文件已存在，按设置跳过":
        ["Track %s already exists — skipped per settings", "%s トラック目のファイルが既存のため、設定に従いスキップ"],
    "抓取第 %s 轨：%s": ["Ripping track %s: %s", "%s トラック目を取得：%s"],
    "第 %s 轨测试/复制 CRC 不一致（0x%08X vs 0x%08X），已重抓":
        ["Track %s: test/copy CRC mismatch (0x%08X vs 0x%08X) — re-ripped",
         "%s トラック目：テスト／コピーの CRC 不一致（0x%08X vs 0x%08X）。再取得しました"],
    "第 %s 轨重抓后仍与测试通道不一致（0x%08X）":
        ["Track %s still differs from the test pass after re-ripping (0x%08X)",
         "%s トラック目：再取得後もテストと CRC が一致しません（0x%08X）"],
    "第 %s 轨重抓后与测试通道一致":
        ["Track %s matches the test pass after re-ripping", "%s トラック目：再取得でテストと一致しました"],
    "offset 校正失败: %s": ["Offset correction failed: %s", "オフセット補正に失敗：%s"],
    "第 %s 轨 WAV 标签写入失败（音频不受影响）: %s":
        ["Track %s: WAV tag write failed (audio is fine): %s",
         "%s トラック目：WAV タグの書き込みに失敗（音声は無事）：%s"],
    "第 %s 轨外部编码失败（已保留 WAV）: %s":
        ["Track %s: external encoding failed (WAV kept): %s",
         "%s トラック目：外部エンコードに失敗（WAV は保持）：%s"],
    "第 %s 轨标签写入失败（音频不受影响）: %s":
        ["Track %s: tag write failed (audio is fine): %s",
         "%s トラック目：タグの書き込みに失敗（音声は無事）：%s"],
    "第 %s 轨 FLAC 编码失败（已保留 WAV）: %s":
        ["Track %s: FLAC encoding failed (WAV kept): %s",
         "%s トラック目：FLAC エンコードに失敗（WAV は保持）：%s"],
    "间隙处理为 %s 时音轨边界不连续，已跳过整盘镜像":
        ["Gaps set to %s makes track boundaries non-contiguous; disc image skipped",
         "ギャップ処理が %s のときはトラック境界が連続しないため、イメージ取得をスキップしました"],
    "生成整盘镜像…": ["Building the disc image…", "ディスクイメージを作成中…"],
    "整盘镜像目标已存在，按设置跳过拼接（逐轨文件保留）":
        ["The image target already exists — join skipped per settings (per-track files kept)",
         "イメージの出力先が既存のため、設定に従い結合をスキップ（トラック別ファイルは保持）"],
    "整盘镜像 FLAC 编码失败（已保留 WAV）: %s":
        ["Disc image FLAC encoding failed (WAV kept): %s",
         "ディスクイメージの FLAC エンコードに失敗（WAV は保持）：%s"],
    "整盘镜像拼接失败（逐轨文件保留）: %s":
        ["Disc image join failed (per-track files kept): %s",
         "ディスクイメージの結合に失敗（トラック別ファイルは保持）：%s"],
    "封面写入失败: %s": ["Cover write failed: %s", "カバーの書き込みに失敗：%s"],
    "封面内嵌失败（音频与封面文件不受影响）: %s":
        ["Embedding the cover failed (audio and cover file are unaffected): %s",
         "カバーの埋め込みに失敗（音声とカバーファイルは無事）：%s"],
    "CUE 写入失败: %s": ["CUE write failed: %s", "CUE の書き込みに失敗：%s"],
    "日志写入失败: %s": ["Log write failed: %s", "ログの書き込みに失敗：%s"],
    "元数据副本: %s": ["Metadata copy: %s", "メタデータの複製：%s"],
    "元数据副本写入失败: %s": ["Metadata copy write failed: %s", "メタデータ複製の書き込みに失敗：%s"],
    "错误报告: %s": ["Error report: %s", "エラーレポート：%s"],
    "弹出托盘失败: %s": ["Ejecting the tray failed: %s", "トレイの排出に失敗：%s"],
}


def main():
    src = io.open(P, encoding="utf-8").read()
    if "import com.saltrip.rip.ui.Strings" not in src:
        src = src.replace("import com.saltrip.rip.ui.SettingsStore",
                          "import com.saltrip.rip.ui.SettingsStore\nimport com.saltrip.rip.ui.Strings", 1)
        if "import com.saltrip.rip.ui.Strings" not in src:
            src = src.replace("\nimport ", "\nimport com.saltrip.rip.ui.Strings\nimport ", 1)
    for old, new in PAIRS:
        n = src.count(old)
        if n != 1:
            raise SystemExit(f"命中 {n} 次（应为 1）：{old[:60]}")
        src = src.replace(old, new)
    io.open(P, "w", encoding="utf-8", newline="\n").write(src)

    import json
    MP = "src/main/resources/messages-i18n.json"
    doc = json.load(io.open(MP, encoding="utf-8"))
    entries = doc["entries"]
    added = 0
    for k, v in TEMPLATES.items():
        if k not in entries:
            entries[k] = v
            added += 1
    io.open(MP, "w", encoding="utf-8", newline="\n").write(
        json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
    print("patched", P, "| templates added", added, "| entries", len(entries))


if __name__ == "__main__":
    main()
