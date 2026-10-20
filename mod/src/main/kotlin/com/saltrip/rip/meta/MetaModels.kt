package com.saltrip.rip.meta

/** 单轨元数据（按光盘轨号索引）。 */
data class TrackMeta(
    val number: Int,
    val title: String,
    val artist: String? = null,
    val lengthMs: Long? = null,
    val isrc: String? = null
) {
    val lengthMmSs: String
        get() = lengthMs?.let {
            val total = it / 1000
            "%d:%02d".format(total / 60, total % 60)
        } ?: ""
}

/** 一张专辑的元数据；`source` 记录来源（musicbrainz / gnudb / manual）。 */
data class AlbumMeta(
    val albumArtist: String,
    val albumTitle: String,
    val year: String? = null,
    val genre: String? = null,
    val discId: String,
    val cddbId: String,
    val source: String,
    val tracks: List<TrackMeta> = emptyList(),
    val cover: ByteArray? = null,
    val coverMime: String? = null,
    val releaseId: String? = null,
    val discNumber: Int = 1,
    val totalDiscs: Int = 1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AlbumMeta) return false
        return albumArtist == other.albumArtist && albumTitle == other.albumTitle &&
            year == other.year && genre == other.genre && discId == other.discId &&
            cddbId == other.cddbId && source == other.source && tracks == other.tracks
    }

    override fun hashCode(): Int =
        listOf(albumArtist, albumTitle, year, genre, discId, cddbId, source, tracks).hashCode()

    fun track(n: Int): TrackMeta? = tracks.firstOrNull { it.number == n }

    fun copy(albumArtist: String = this.albumArtist, albumTitle: String = this.albumTitle): AlbumMeta =
        AlbumMeta(albumArtist, albumTitle, year, genre, discId, cddbId, source, tracks, cover, coverMime, releaseId, discNumber, totalDiscs)

    fun withCover(bytes: ByteArray?, mime: String?): AlbumMeta =
        AlbumMeta(albumArtist, albumTitle, year, genre, discId, cddbId, source, tracks, bytes, mime, releaseId, discNumber, totalDiscs)

    fun withIds(discId: String, cddbId: String): AlbumMeta =
        AlbumMeta(albumArtist, albumTitle, year, genre, discId, cddbId, source, tracks, cover, coverMime, releaseId, discNumber, totalDiscs)
}

/** 一次查询的完整结果：命中的专辑 + 诊断信息（用于日志与 UI 提示）。 */
data class LookupOutcome(
    val album: AlbumMeta?,
    val notes: List<String>,
    val candidates: Int = 0
)
