# -*- coding: utf-8 -*-
"""第二遍：把预览/自检/元数据说明这些「面板里能看到」的文本包成 Strings.*。

等宽表格的表头与数据行保持中文——它们是为了贴进 issue 的原始 dump，
翻译会破坏列对齐，收益也近乎为零。
"""
import io
import json

PAIRS = [
    # ── album/RipPlan.kt ──────────────────────────────────────────
    ("src/main/kotlin/com/saltaudiocopy/rip/album/RipPlan.kt", [
        ('sb.appendLine("输出目录：$albumDir")',
         'sb.appendLine(Strings.fmt("输出目录：%s", albumDir))'),
        ('sb.appendLine("专辑：$albumArtist - $albumTitle")',
         'sb.appendLine(Strings.fmt("专辑：%s - %s", albumArtist, albumTitle))'),
        ('sb.appendLine("轨数：${tracks.size}（合计 ${tracks.sumOf { it.sectors }} 扇区，约 ${gb(totalBytes)}）")',
         'sb.appendLine(\n'
         '                Strings.fmt(\n'
         '                    "轨数：%s（合计 %s 扇区，约 %s）",\n'
         '                    tracks.size, tracks.sumOf { it.sectors }, gb(totalBytes)\n'
         '                )\n'
         '            )'),
        ('freeBytes?.let { sb.appendLine("可用空间：${gb(it)}" + if (it < totalBytes) "  ← 不足" else "") }',
         'freeBytes?.let {\n'
         '                sb.appendLine(\n'
         '                    Strings.fmt("可用空间：%s", gb(it)) +\n'
         '                        if (it < totalBytes) "  " + Strings.message("← 不足") else ""\n'
         '                )\n'
         '            }'),
        ('val extras = listOfNotNull(cueName?.let { "CUE：$it" }, logName?.let { "日志：$it" }, coverName?.let { "封面：$it" })',
         'val extras = listOfNotNull(\n'
         '                cueName?.let { Strings.fmt("CUE：%s", it) },\n'
         '                logName?.let { Strings.fmt("日志：%s", it) },\n'
         '                coverName?.let { Strings.fmt("封面：%s", it) }\n'
         '            )'),
    ]),
    # ── album/DiscAnalysis.kt ────────────────────────────────────
    ("src/main/kotlin/com/saltaudiocopy/rip/album/DiscAnalysis.kt", [
        ('if (gaps.isEmpty()) return "没有音轨"',
         'if (gaps.isEmpty()) return Strings.message("没有音轨")'),
        ('sb.append("共 %d 轨 / %s / %d 轨有预间隙".format(gaps.size, CueWriter.msf(total.toInt()), withGap))',
         'sb.append(\n'
         '            Strings.fmt("共 %s 轨 / %s / %s 轨有预间隙", gaps.size, CueWriter.msf(total.toInt()), withGap)\n'
         '        )'),
    ]),
    # ── album/OutputCheck.kt ─────────────────────────────────────
    ("src/main/kotlin/com/saltaudiocopy/rip/album/OutputCheck.kt", [
        ('wavCount == 0 && cueCount == 0 -> "目录里没有 WAV/CUE：$dir"',
         'wavCount == 0 && cueCount == 0 -> Strings.fmt("目录里没有 WAV/CUE：%s", dir)'),
        ('ok -> "检查通过：$wavCount 个 WAV，$cueCount 个 CUE"',
         'ok -> Strings.fmt("检查通过：%s 个 WAV，%s 个 CUE", wavCount, cueCount)'),
        ('else -> "发现 ${problems.size} 个问题（共 $wavCount 个 WAV，$cueCount 个 CUE）"',
         'else -> Strings.fmt(\n'
         '                "发现 %s 个问题（共 %s 个 WAV，%s 个 CUE）",\n'
         '                problems.size, wavCount, cueCount\n'
         '            )'),
        ('listOf(Problem("", "目录不存在：$dir"))',
         'listOf(Problem("", Strings.fmt("目录不存在：%s", dir)))'),
        ('facts == null -> problems += Problem(name, "文件太短，连 44 字节的 WAV 头都不完整")',
         'facts == null -> problems += Problem(\n'
         '                            name,\n'
         '                            Strings.message("文件太短，连 44 字节的 WAV 头都不完整")\n'
         '                        )'),
        ('!facts.headerOk -> problems += Problem(name, "不是合法的 RIFF/WAVE 头")',
         '!facts.headerOk -> problems += Problem(\n'
         '                            name, Strings.message("不是合法的 RIFF/WAVE 头")\n'
         '                        )'),
        ('facts.dataBytes == 0L -> problems += Problem(name, "只有文件头，没有音频数据")',
         'facts.dataBytes == 0L -> problems += Problem(\n'
         '                            name, Strings.message("只有文件头，没有音频数据")\n'
         '                        )'),
    ]),
    # ── album/DiscTest.kt ────────────────────────────────────────
    ("src/main/kotlin/com/saltaudiocopy/rip/album/DiscTest.kt", [
        ('onStage("测试读取第 ${entry.track} 轨…")',
         'onStage(Strings.fmt("测试读取第 %s 轨…", entry.track))'),
        ('if (rows.isEmpty()) return "没有测试任何音轨"',
         'if (rows.isEmpty()) return Strings.message("没有测试任何音轨")'),
        ('if (r.errors > 0) { sb.append("  ⚠ 有无法读取的扇区"); bad++ }',
         'if (r.errors > 0) { sb.append("  ⚠ " + Strings.message("有无法读取的扇区")); bad++ }'),
        ('sb.append("合计 %d 轨 / %d 扇区 / %s".format(rows.size, total, if (bad == 0) "全部读通" else "$bad 轨有错误"))',
         'sb.append(\n'
         '            Strings.fmt(\n'
         '                "合计 %s 轨 / %s 扇区 / %s",\n'
         '                rows.size, total,\n'
         '                if (bad == 0) Strings.message("全部读通")\n'
         '                else Strings.fmt("%s 轨有错误", bad)\n'
         '            )\n'
         '        )'),
    ]),
    # ── meta/MetadataCandidates.kt ───────────────────────────────
    ("src/main/kotlin/com/saltaudiocopy/rip/meta/MetadataCandidates.kt", [
        ('notes += "MusicBrainz 查询失败：${it.message ?: it.javaClass.simpleName}"',
         'notes += Strings.fmt(\n'
         '                    "MusicBrainz 查询失败：%s", it.message ?: it.javaClass.simpleName\n'
         '                )'),
        ('notes += "MusicBrainz 没有这张盘的记录"',
         'notes += Strings.message("MusicBrainz 没有这张盘的记录")'),
        ('notes += "MusicBrainz 命中 ${releases.size} 个发行版（显示前 ${out.size} 个）"',
         'notes += Strings.fmt(\n'
         '                    "MusicBrainz 命中 %s 个发行版（显示前 %s 个）", releases.size, out.size\n'
         '                )'),
        ('notes += "CD-TEXT 命中（盘上自带曲名）"',
         'notes += Strings.message("CD-TEXT 命中（盘上自带曲名）")'),
        ('notes += "CDDB 缓存命中（离线也能用）"',
         'notes += Strings.message("CDDB 缓存命中（离线也能用）")'),
    ]),
    # ── meta/MetadataService.kt ─────────────────────────────────
    ("src/main/kotlin/com/saltaudiocopy/rip/meta/MetadataService.kt", [
        ('"MusicBrainz 查询失败: ${it.message}"',
         'Strings.fmt("MusicBrainz 查询失败: %s", it.message)'),
        ('"MusicBrainz: ${releases.size} 个候选"',
         'Strings.fmt("MusicBrainz: %s 个候选", releases.size)'),
        ('"（只列前 ${shown.size}）"', 'Strings.fmt("（只列前 %s）", shown.size)'),
        ('"${it.titles.size} 轨标题（字符集 ${it.charset}）"',
         'Strings.fmt("%s 轨标题（字符集 %s）", it.titles.size, it.charset)'),
    ]),
]

TEMPLATES = {
    "← 不足": ["← not enough", "← 不足"],
    "输出目录：%s": ["Output folder: %s", "出力先：%s"],
    "专辑：%s - %s": ["Album: %s - %s", "アルバム：%s - %s"],
    "轨数：%s（合计 %s 扇区，约 %s）":
        ["Tracks: %s (%s sectors in total, about %s)", "トラック数：%s（合計 %s セクタ、約 %s）"],
    "可用空间：%s": ["Free space: %s", "空き容量：%s"],
    "CUE：%s": ["CUE: %s", "CUE：%s"],
    "日志：%s": ["Log: %s", "ログ：%s"],
    "封面：%s": ["Cover: %s", "カバー：%s"],
    "没有音轨": ["No tracks", "トラックがありません"],
    "共 %s 轨 / %s / %s 轨有预间隙":
        ["%s tracks / %s / %s of them have a pregap", "%s トラック / %s / %s トラックにプリギャップ"],
    "目录里没有 WAV/CUE：%s": ["No WAV/CUE in the folder: %s", "フォルダに WAV/CUE がありません：%s"],
    "检查通过：%s 个 WAV，%s 个 CUE":
        ["Check passed: %s WAV, %s CUE", "チェック通過：WAV %s 個、CUE %s 個"],
    "发现 %s 个问题（共 %s 个 WAV，%s 个 CUE）":
        ["Found %s problems (%s WAV, %s CUE)", "%s 件の問題（WAV %s 個、CUE %s 個）"],
    "目录不存在：%s": ["The folder does not exist: %s", "フォルダが存在しません：%s"],
    "文件太短，连 44 字节的 WAV 头都不完整":
        ["The file is too short — not even a 44-byte WAV header",
         "ファイルが短すぎます（44 バイトの WAV ヘッダも揃っていません）"],
    "不是合法的 RIFF/WAVE 头": ["Not a valid RIFF/WAVE header", "正しい RIFF/WAVE ヘッダではありません"],
    "只有文件头，没有音频数据": ["Header only, no audio data", "ヘッダのみで音声データがありません"],
    "测试读取第 %s 轨…": ["Test-reading track %s…", "%s トラック目をテスト読み取り中…"],
    "没有测试任何音轨": ["No track was tested", "テストしたトラックがありません"],
    "有无法读取的扇区": ["unreadable sectors", "読み取れないセクタがあります"],
    "全部读通": ["all read fine", "すべて読み取り成功"],
    "%s 轨有错误": ["%s tracks have errors", "%s トラックにエラー"],
    "合计 %s 轨 / %s 扇区 / %s": ["Total %s tracks / %s sectors / %s", "合計 %s トラック / %s セクタ / %s"],
    "MusicBrainz 查询失败：%s": ["MusicBrainz lookup failed: %s", "MusicBrainz の検索に失敗：%s"],
    "MusicBrainz 查询失败: %s": ["MusicBrainz lookup failed: %s", "MusicBrainz の検索に失敗：%s"],
    "MusicBrainz 没有这张盘的记录": ["MusicBrainz has no entry for this disc",
                                     "MusicBrainz にこのディスクの登録がありません"],
    "MusicBrainz 命中 %s 个发行版（显示前 %s 个）":
        ["MusicBrainz found %s releases (showing the first %s)",
         "MusicBrainz で %s 件のリリース（先頭 %s 件を表示）"],
    "MusicBrainz: %s 个候选": ["MusicBrainz: %s candidates", "MusicBrainz：%s 件の候補"],
    "（只列前 %s）": ["(first %s only)", "（先頭 %s 件のみ）"],
    "CD-TEXT 命中（盘上自带曲名）": ["CD-TEXT found (the disc carries the titles)",
                                     "CD-TEXT を検出（ディスクに曲名あり）"],
    "CDDB 缓存命中（离线也能用）": ["CDDB cache hit (works offline)", "CDDB キャッシュにヒット（オフラインでも可）"],
    "%s 轨标题（字符集 %s）": ["%s track titles (charset %s)", "%s トラックのタイトル（文字コード %s）"],
}


def ensure_import(src: str, pkg: str) -> str:
    line = f"import {pkg}"
    if line in src:
        return src
    idx = src.find("\nimport ")
    if idx < 0:
        raise SystemExit("没有 import 段")
    return src[:idx] + "\n" + line + src[idx:]


def main():
    total = 0
    for path, pairs in PAIRS:
        src = io.open(path, encoding="utf-8").read()
        if any("Strings." in new for _, new in pairs):
            src = ensure_import(src, "com.doggybear233.saltaudiocopy.ui.Strings")
        for old, new in pairs:
            n = src.count(old)
            if n != 1:
                raise SystemExit(f"{path}: 命中 {n} 次（应为 1）：{old[:50]}")
            src = src.replace(old, new)
        io.open(path, "w", encoding="utf-8", newline="\n").write(src)
        total += len(pairs)
        print("patched", path, len(pairs))

    mp = "src/main/resources/messages-i18n.json"
    doc = json.load(io.open(mp, encoding="utf-8"))
    added = 0
    for k, v in TEMPLATES.items():
        if k not in doc["entries"]:
            doc["entries"][k] = v
            added += 1
    io.open(mp, "w", encoding="utf-8", newline="\n").write(
        json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
    print("pairs", total, "| templates added", added, "| entries", len(doc["entries"]))


if __name__ == "__main__":
    main()
