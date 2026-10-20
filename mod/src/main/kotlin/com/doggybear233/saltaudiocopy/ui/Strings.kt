package com.doggybear233.saltaudiocopy.ui

import com.doggybear233.saltaudiocopy.Diag
import com.doggybear233.saltaudiocopy.meta.Json

/**
 * 面板文案的多语言表（中文 / English / 日本語）。
 *
 * 为什么自己做：宿主把语言存在 `com.xuncorp.voxzen.util.AppConfig.getLanguageTag()`
 * （形如 `zh-CN` / `en-US` / `ja-JP`），但插件 API 没有暴露「宿主当前语言」，
 * 也没有给插件提供资源覆盖机制。所以这里反射读一次、缓存，再按前缀选表；
 * 读不到就按 JVM 默认 Locale 猜，最坏退回中文。
 *
 * 只覆盖**面板**文案：设置页（宿主渲染的 preference_config.json）与日志保持中文，
 * 因为设置页的标题/摘要由宿主按我们给的 JSON 原样显示，做多语言需要多份 JSON，
 * 而宿主的插件配置不支持按语言切换文件。
 */
object Strings {

    enum class Lang { ZH, EN, JA }

    /** 解析一次后缓存；面板每次重组都会调用，不能每次都反射。 */
    @Volatile
    private var cached: Lang? = null

    fun lang(): Lang = cached ?: resolve().also { cached = it }

    fun key(key: String): String = table(lang())[key] ?: table(Lang.ZH)[key] ?: key

    /**
     * 取词条，查不到就用 [fallback]。
     *
     * 设置页的 50 个条目文案主要在 `SettingsSpec` 里（中文为源），
     * 只有确实翻译过的才在表里加 `opt_<key>`；这里避免「没翻译就显示 opt_xxx」。
     */
    fun keyOr(key: String, fallback: String): String =
        table(lang())[key] ?: table(Lang.ZH)[key] ?: fallback

    /**
     * 消息翻译：把代码里的中文原文翻成当前语言。
     *
     * 消息分散在 `ConfigActions` / `Discs` / `DiscWatcher` / `PanelState` 里，且大量带插值，
     * 没法像面板那样按 key 组织；所以字典以**中文原文**为键（`/messages-i18n.json`），
     * 查不到就原样返回中文——新加的消息不会因为漏翻而显示成 `msg_xxx`。
     */
    fun message(zh: String): String = messageTable()[lang()]?.get(zh) ?: zh

    /**
     * 带参数的消息：字典里存的是模板（`%s` 占位），先翻译再格式化。
     *
     * 之所以不直接翻译拼好的字符串：译文语序和中文不同（英文 "Ripping track 3/11"，
     * 日文「3/11 トラックを取得中」），只能把参数交给译文自己排。
     */
    fun fmt(zhTemplate: String, vararg args: Any?): String {
        val tpl = message(zhTemplate)
        return runCatching { String.format(java.util.Locale.ROOT, tpl, *args) }.getOrDefault(tpl)
    }

    /** 测试用：字典里到底收了多少条。 */
    internal fun messageCount(lang: Lang): Int = messageTable()[lang]?.size ?: 0

    /** 测试用：这条中文原文在 en 与 ja 里都有译文吗。 */
    internal fun hasMessage(zh: String): Boolean = hasMessage(Lang.EN, zh) && hasMessage(Lang.JA, zh)

    internal fun hasMessage(lang: Lang, zh: String): Boolean =
        !messageTable()[lang]?.get(zh).isNullOrBlank()

    private object MessageTables {
        val byLang: Map<Lang, Map<String, String>> by lazy { loadMessages() }

        private fun loadMessages(): Map<Lang, Map<String, String>> {
            val text = runCatching {
                Strings::class.java.getResourceAsStream("/messages-i18n.json")
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull() ?: return emptyMap()
            val root = runCatching { Json.parse(text) }.getOrNull() ?: return emptyMap()
            val entries = Json.obj(Json.path(root, "entries"))
            val out = HashMap<Lang, Map<String, String>>()
            for (lang in listOf(Lang.EN, Lang.JA)) {
                val m = HashMap<String, String>()
                for ((zh, v) in entries) {
                    val pair = Json.arr(v)
                    val translated = Json.str(pair.getOrNull(if (lang == Lang.EN) 0 else 1))
                    if (!translated.isNullOrBlank()) m[zh] = translated
                }
                if (m.isNotEmpty()) out[lang] = m
            }
            return out
        }
    }

    private fun messageTable(): Map<Lang, Map<String, String>> = MessageTables.byLang

    /** 测试用：强制语言 / 复位。 */
    fun override(lang: Lang?) {
        cached = lang
    }

    private fun resolve(): Lang {
        val tag = hostLanguageTag() ?: java.util.Locale.getDefault().toLanguageTag()
        val lower = tag.lowercase()
        return when {
            lower.startsWith("zh") -> Lang.ZH
            lower.startsWith("ja") -> Lang.JA
            lower.startsWith("en") -> Lang.EN
            else -> Lang.ZH
        }
    }

    /** 反射读宿主语言标签；读不到返回 null。 */
    internal fun hostLanguageTag(): String? = runCatching {
        val cls = Class.forName("com.xuncorp.voxzen.util.AppConfig")
        val inst = cls.getField("INSTANCE").get(null)
        (cls.getMethod("getLanguageTag").invoke(inst) as? String)?.takeIf { it.isNotBlank() }
    }.onFailure { Diag.log("read host language failed", it) }.getOrNull()

    private fun table(lang: Lang): Map<String, String> = when (lang) {
        Lang.ZH -> ZH
        Lang.EN -> EN
        Lang.JA -> JA
    }

    private val ZH = mapOf(
        "appearance" to "外观",
        "appearance_auto" to "跟随系统",
        "appearance_light" to "浅色",
        "appearance_dark" to "深色",
        "title" to "盐专辑",
        "subtitle" to "SPTI 直连 · 安全双读 · CUE 与日志",
        "settings" to "设置",
        "settings_close" to "收起设置",
        "scan" to "扫描",
        "rip_album" to "抓整盘",
        "rip_again" to "重新抓取",
        "pause" to "暂停",
        "resume" to "继续",
        "cancel" to "取消",
        "cancel_confirm" to "确认取消",
        "refresh_meta" to "刷新元数据",
        "versions" to "版本",
        "pick_target_hint" to "点上面的输入框选中要编辑的模板，再点下面的变量",
        "add_token_hint" to "把变量加到选中的模板：",
        "editing_this" to "正在编辑这个模板",
        "test_only" to "只测不写",
        "open_folder" to "打开目录",
        "output_root" to "输出目录",
        "choose" to "选择",
        "default_root" to "（默认：音乐目录 / Salt Audio Copy）",
        "tpl_folder" to "专辑目录模板",
        "tpl_file" to "文件名模板",
        "tpl_cover" to "封面文件名",
        "target_folder" to "目录",
        "target_file" to "文件名",
        "target_cover" to "封面",
        "clear" to "清空",
        "secure" to "安全双读校验",
        "test_mode" to "测试模式（显示只测不写等测试动作）",
        "save" to "保存",
        "badge_audio" to "CD-DA",
        "badge_data" to "数据盘",
        "manual_meta" to "手动填写元数据…",
        "manual_title" to "手动填写元数据",
        "manual_hint" to "查不到时自己写：歌手、专辑名、每轨歌名都行。保存后抓轨按这些名字建目录、写标签。",
        "manual_artist" to "专辑艺术家",
        "manual_album" to "专辑名",
        "manual_year" to "年份",
        "manual_disc" to "碟号",
        "manual_discs" to "总碟数",
        "manual_tracks" to "曲目",
        "manual_track_title" to "歌名",
        "manual_track_artist" to "该轨艺术家（可空）",
        "manual_fill_cdtext" to "用 CD-TEXT 填",
        "manual_save" to "保存元数据",
        "manual_cover" to "导入封面…",
        "manual_cover_chosen" to "换一张封面…",
        "manual_no_cdtext" to "这张盘没有 CD-TEXT"
    )

    private val EN = mapOf(
        "appearance" to "Appearance",
        "appearance_auto" to "System",
        "appearance_light" to "Light",
        "appearance_dark" to "Dark",
        "title" to "Salt Audio Copy",
        "subtitle" to "SPTI direct · secure re-read · CUE & log",
        "settings" to "Settings",
        "settings_close" to "Hide settings",
        "scan" to "Scan",
        "rip_album" to "Rip disc",
        "rip_again" to "Rip again",
        "pause" to "Pause",
        "resume" to "Resume",
        "cancel" to "Cancel",
        "cancel_confirm" to "Confirm cancel",
        "refresh_meta" to "Refresh metadata",
        "versions" to "Version",
        "pick_target_hint" to "Tap a field above to pick the template, then tap a variable below",
        "add_token_hint" to "Append to the selected template:",
        "editing_this" to "Editing this template",
        "test_only" to "Test only",
        "open_folder" to "Open folder",
        "output_root" to "Output folder",
        "choose" to "Choose",
        "default_root" to "(default: Music / Salt Audio Copy)",
        "tpl_folder" to "Album folder template",
        "tpl_file" to "Track file template",
        "tpl_cover" to "Cover file name",
        "target_folder" to "Folder",
        "target_file" to "File",
        "target_cover" to "Cover",
        "clear" to "Clear",
        "secure" to "Secure double read",
        "test_mode" to "Test mode (show test-only actions)",
        "save" to "Save",
        "badge_audio" to "CD-DA",
        "badge_data" to "Data disc",
        "manual_meta" to "Enter metadata manually…",
        "manual_title" to "Enter metadata manually",
        "manual_hint" to "Nothing found online: type the artist, album and track titles yourself. Rip names folders and tags from these.",
        "manual_artist" to "Album artist",
        "manual_album" to "Album title",
        "manual_year" to "Year",
        "manual_disc" to "Disc",
        "manual_discs" to "Total discs",
        "manual_tracks" to "Tracks",
        "manual_track_title" to "Title",
        "manual_track_artist" to "Track artist (optional)",
        "manual_fill_cdtext" to "Fill from CD-TEXT",
        "manual_save" to "Save metadata",
        "manual_cover" to "Import cover…",
        "manual_cover_chosen" to "Change cover…",
        "manual_no_cdtext" to "This disc has no CD-TEXT"
    )

    private val JA = mapOf(
        "appearance" to "外観",
        "appearance_auto" to "システム",
        "appearance_light" to "ライト",
        "appearance_dark" to "ダーク",
        "title" to "Salt Audio Copy",
        "subtitle" to "SPTI 直結 · セキュア再読み · CUE とログ",
        "settings" to "設定",
        "settings_close" to "閉じる",
        "scan" to "スキャン",
        "rip_album" to "全曲取り込み",
        "rip_again" to "再取得",
        "pause" to "一時停止",
        "resume" to "再開",
        "cancel" to "中止",
        "cancel_confirm" to "中止を確定",
        "refresh_meta" to "メタデータ更新",
        "versions" to "バージョン",
        "pick_target_hint" to "上の入力欄を選んでから、下の変数を押してください",
        "add_token_hint" to "選択中のテンプレートに追加：",
        "editing_this" to "このテンプレートを編集中",
        "test_only" to "テストのみ",
        "open_folder" to "フォルダーを開く",
        "output_root" to "出力先",
        "choose" to "選択",
        "default_root" to "（既定：ミュージック / Salt Audio Copy）",
        "tpl_folder" to "アルバムフォルダーのテンプレート",
        "tpl_file" to "ファイル名テンプレート",
        "tpl_cover" to "カバー画像のファイル名",
        "target_folder" to "フォルダー",
        "target_file" to "ファイル名",
        "target_cover" to "カバー",
        "clear" to "クリア",
        "secure" to "セキュア二重読み",
        "test_mode" to "テストモード（テスト用の操作を表示）",
        "save" to "保存",
        "badge_audio" to "CD-DA",
        "badge_data" to "データディスク",
        "manual_meta" to "メタデータを手入力…",
        "manual_title" to "メタデータを手入力",
        "manual_hint" to "ネットで見つからない場合に、アーティスト・アルバム名・曲名を自分で入力します。保存するとこの名前でフォルダーとタグを作ります。",
        "manual_artist" to "アルバムアーティスト",
        "manual_album" to "アルバム名",
        "manual_year" to "年",
        "manual_disc" to "ディスク番号",
        "manual_discs" to "総ディスク数",
        "manual_tracks" to "曲",
        "manual_track_title" to "曲名",
        "manual_track_artist" to "この曲のアーティスト（任意）",
        "manual_fill_cdtext" to "CD-TEXT から入力",
        "manual_save" to "メタデータを保存",
        "manual_cover" to "カバーを取り込む…",
        "manual_cover_chosen" to "カバーを変更…",
        "manual_no_cdtext" to "このディスクに CD-TEXT はありません"
    )
}
