package com.saltrip.rip

/**
 * 版本号单一来源。
 *
 * 以前 `AlbumRipper.APP_VERSION` 硬编码成 `"0.7.0"`，导致 EAC 日志抬头、CUE 注释、
 * WAV 的 comment 标签一直写旧版本（用户排查问题时被误导）。现在只在这里改。
 *
 * `build.gradle.kts` 的 `version` 仍然要同步——`VersionTest` 会读文件比对，漏改会红。
 */
object Version {
    const val NAME = "Salt Audio Copy"
    const val VALUE = "0.39.4"
}
