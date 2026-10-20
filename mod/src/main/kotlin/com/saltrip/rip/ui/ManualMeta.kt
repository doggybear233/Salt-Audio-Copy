package com.saltrip.rip.ui

import com.saltrip.rip.meta.AlbumMeta
import com.saltrip.rip.meta.TrackMeta

/**
 * 手填元数据的草稿。
 *
 * 用途：网络查不到（小众独立盘、自制合集、gnudb 没收录）时，用户自己在面板里写
 * 艺术家 / 专辑名 / 每轨歌名，保存后写进 `album.json`，抓轨按它命名与写标签。
 *
 * 这里只有数据与纯函数（便于单测）；读写文件与 SCSI 在 [com.saltrip.rip.ConfigActions]。
 */
data class ManualTrack(
    val number: Int,
    val title: String = "",
    val artist: String = ""
)

data class ManualDraft(
    val albumArtist: String = "",
    val albumTitle: String = "",
    val year: String = "",
    val discNumber: Int = 1,
    val totalDiscs: Int = 1,
    val tracks: List<ManualTrack> = emptyList()
) {

    /** 改某一轨的标题 / 艺术家；越界就原样返回（UI 不该传越界值，但别崩）。 */
    fun withTrack(number: Int, title: String? = null, artist: String? = null): ManualDraft {
        val idx = tracks.indexOfFirst { it.number == number }
        if (idx < 0) return this
        val t = tracks[idx]
        val next = tracks.toMutableList()
        next[idx] = t.copy(
            title = title ?: t.title,
            artist = artist ?: t.artist
        )
        return copy(tracks = next)
    }

    /** 至少写了一样东西才算「有内容」，用于保存前校验。 */
    val hasAnything: Boolean
        get() = albumArtist.isNotBlank() || albumTitle.isNotBlank() ||
            tracks.any { it.title.isNotBlank() }

    /**
     * 变成抓轨/写标签用的 [AlbumMeta]。
     *
     * 空标题写成 `Track 07`，空轨艺术家留 null（写标签时会回落到专辑艺术家）——
     * 与网络元数据缺失时的占位规则一致，避免出现空文件名。
     */
    fun toAlbumMeta(discId: String, cddbId: String = ""): AlbumMeta = AlbumMeta(
        albumArtist = albumArtist.trim(),
        albumTitle = albumTitle.trim(),
        year = year.trim().ifBlank { null },
        genre = null,
        discId = discId,
        cddbId = cddbId,
        source = "manual",
        tracks = tracks.map { t ->
            TrackMeta(
                number = t.number,
                title = t.title.trim().ifBlank { "Track %02d".format(t.number) },
                artist = t.artist.trim().ifBlank { null }
            )
        },
        discNumber = discNumber.coerceAtLeast(1),
        totalDiscs = totalDiscs.coerceAtLeast(1)
    )

    companion object {

        /** 面板上的占位符一律当作「没填」。 */
        private val PLACEHOLDERS = setOf(
            "", "未知艺术家", "未知专辑", "未知", "Unknown Artist", "Unknown Album", "Unknown"
        )

        fun displayable(v: String?): String =
            if (v == null || v.trim() in PLACEHOLDERS) "" else v.trim()

        /**
         * 为新插入的盘建草稿：优先沿用已有的 `album.json`（用户上次手填/选过的），
         * 否则用面板上已知的艺术家/专辑（可能为空）+ `Track NN` 占位。
         */
        fun forDisc(
            trackCount: Int,
            artist: String?,
            album: String?,
            cached: AlbumMeta? = null
        ): ManualDraft {
            val count = if (trackCount > 0) trackCount else cached?.tracks?.size ?: 0
            val tracks = (1..count).map { n ->
                val hit = cached?.tracks?.firstOrNull { it.number == n }
                ManualTrack(
                    number = n,
                    // 不预填「Track 01」：用户要能直接打字，不用先删占位名。
                    // 真没填的时候 [toAlbumMeta] 会补回同样的默认名，输出文件不会空。
                    title = displayable(hit?.title),
                    artist = displayable(hit?.artist)
                )
            }
            return ManualDraft(
                albumArtist = displayable(cached?.albumArtist).ifBlank { displayable(artist) },
                albumTitle = displayable(cached?.albumTitle).ifBlank { displayable(album) },
                year = displayable(cached?.year),
                discNumber = cached?.discNumber?.takeIf { it > 0 } ?: 1,
                totalDiscs = cached?.totalDiscs?.takeIf { it > 0 } ?: 1,
                tracks = tracks
            )
        }
    }
}

/** 面板按钮文案/动作的判定（纯函数，便于单测）。 */
object ManualEntry {

    /**
     * 「刷新元数据」要不要变成「手动填写元数据…」。
     *
     * 只有**查过一轮且确实没有候选**才变：还没查就变会让人以为网络已失败；
     * 有候选时也不变，那时用户要的是换版本，不是重填。
     */
    fun buttonIsManual(candidatesEmpty: Boolean, lookupTried: Boolean): Boolean =
        candidatesEmpty && lookupTried
}
