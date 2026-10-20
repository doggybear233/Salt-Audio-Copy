package com.saltrip.rip.album

import com.saltrip.rip.meta.AlbumMeta
import com.saltrip.rip.scsi.DiscToc

/**
 * 生成可编辑的 `album.json` 模板。
 *
 * 目的：给用户一条「先导出、再改、再抓」的确定性流程，
 * 而不是抓完发现名字错了再去改文件（改完 CUE/日志还要跟着改）。
 * 输出是标准 JSON，键名与 [com.saltrip.rip.meta.MetaOverride] 的读取规则一一对应。
 */
object AlbumJsonWriter {

    fun build(album: AlbumMeta?, toc: DiscToc): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"_说明\": \"改完保存后点「抓取整张专辑」即生效；下划线开头的键会被忽略。\",\n")
        sb.append("  \"albumArtist\": ").append(q(album?.albumArtist)).append(",\n")
        sb.append("  \"albumTitle\": ").append(q(album?.albumTitle)).append(",\n")
        sb.append("  \"year\": ").append(q(album?.year)).append(",\n")
        sb.append("  \"genre\": ").append(q(album?.genre)).append(",\n")
        sb.append("  \"discNumber\": ").append(album?.discNumber ?: 1).append(",\n")
        sb.append("  \"totalDiscs\": ").append(album?.totalDiscs ?: 1).append(",\n")
        sb.append("  \"tracks\": [\n")
        toc.tracks.forEachIndexed { i, _ ->
            val number = i + 1
            val meta = album?.tracks?.firstOrNull { it.number == number }
            if (i > 0) sb.append(",\n")
            sb.append("    { \"number\": ").append(number)
            sb.append(", \"title\": ").append(q(meta?.title ?: "Track %02d".format(number)))
            meta?.artist?.let { sb.append(", \"artist\": ").append(q(it)) }
            meta?.isrc?.let { sb.append(", \"isrc\": ").append(q(it)) }
            sb.append(" }")
        }
        sb.append("\n  ]\n}\n")
        return sb.toString()
    }

    /** JSON 字符串字面量：反斜杠/引号转义，换行折成空格（路径里出现换行只会带来麻烦）。 */
    internal fun q(s: String?): String {
        val body = (s ?: "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "")
            .replace("\n", " ")
            .replace("\t", " ")
        return "\"" + body + "\""
    }
}
