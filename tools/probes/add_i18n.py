# -*- coding: utf-8 -*-
"""把 0.38.1 新增的消息/页名/选项标签并入 messages-i18n.json。

中文原文是键；值是 [en, ja]。已有条目保持不动。
"""
import io
import json

PATH = "mod/src/main/resources/messages-i18n.json"

ADD = {
    # ── 进度 / 面板状态 ────────────────────────────────────────────
    "%s 轨": ["%s tracks", "%s トラック"],
    "%s — %s（%s 轨）": ["%s — %s (%s tracks)", "%s — %s（%s トラック）"],
    "第 %s/%s 轨 · %s%%": ["Track %s/%s · %s%%", "%s/%s トラック · %s%%"],
    "正在估算…": ["Estimating…", "推定しています…"],
    "即将完成": ["Almost done", "まもなく完了"],
    "剩余约 %s 秒": ["about %s s left", "残り約 %s 秒"],
    "剩余约 %s 分": ["about %s min left", "残り約 %s 分"],
    "剩余约 %s 分 %s 秒": ["about %s min %s s left", "残り約 %s 分 %s 秒"],
    "剩余约 %s 小时": ["about %s h left", "残り約 %s 時間"],
    "剩余约 %s 小时 %s 分": ["about %s h %s min left", "残り約 %s 時間 %s 分"],
    "已用 %ss 剩余约 %ss 重试 %s": ["%ss elapsed, about %ss left, %s retries",
                                    "%ss 経過・残り約 %ss・リトライ %s"],
    "正在抓取 %s 轨…": ["Ripping %s tracks…", "%s トラックを取得中…"],
    "开始抓取整张专辑（%s 轨，%s）…": ["Ripping the whole album (%s tracks, %s)…",
                                       "アルバム全体を取得します（%s トラック、%s）…"],
    "双读校验": ["secure re-read", "二重読み検証"],
    "快速模式": ["fast mode", "高速モード"],
    "开始抓取第 1 轨…": ["Ripping track 1…", "1 トラック目を取得中…"],
    "开始测试读取，不会写任何文件…": ["Test reading, nothing will be written…",
                                      "テスト読み取り中（ファイルは書きません）…"],
    "已抓完 · %s 轨": ["Done · %s tracks", "完了 · %s トラック"],
    "已抓完 · %s 轨 · 用时 %s 秒": ["Done · %s tracks · %s s", "完了 · %s トラック · %s 秒"],
    "已抓完（%s 项需要留意）· %s 轨 · 用时 %s 秒":
        ["Done (%s things to check) · %s tracks · %s s",
         "完了（確認事項 %s 件）· %s トラック · %s 秒"],
    "记录、CUE、日志都已写好；再抓一遍请点「重新抓取」（会先确认）":
        ["CUE and log are written; to rip again press Rip again (asks first)",
         "CUE とログは書き込み済み。もう一度取得するには「再取得」を押します（確認あり）"],
    "已抓完 · 要重新抓取吗？再点一次「重新抓取」确认":
        ["Done · rip again? Press Rip again once more to confirm",
         "完了 · 再取得しますか？もう一度「再取得」で確定します"],
    "确认中：再点一次「重新抓取」才开始。%s。点「取消」解除确认。":
        ["Waiting for confirmation: press Rip again once more. %s. Press Cancel to clear.",
         "確認待ち：もう一度「再取得」で開始します。%s。「キャンセル」で解除されます。"],
    "第二次点击才会真的重抓；%s。点「取消」或换盘会解除确认。":
        ["The second click really re-rips; %s. Cancel or swapping the disc clears it.",
         "2 回目のクリックで実際に再取得します。%s。「キャンセル」かディスク交換で解除されます。"],
    "同名文件已存在，本次写成「%s」这样的副本":
        ["Files with the same name already exist, so this run wrote copies such as \"%s\"",
         "同名ファイルが既にあるため、今回は「%s」のような複製として書き出しました"],
    "（要替换请在 设置 → 输出位置 → 同名文件已存在时 选「覆盖」）":
        ["(To replace them, set Settings → Output → If a file exists to Overwrite)",
         "（置き換えるには 設定 → 出力先 → 同名ファイルがある場合 で「上書き」を選択）"],
    "已暂停": ["Paused", "一時停止"],
    "已暂停（当前块结束后停住），再点一次继续":
        ["Paused (stops after the current chunk); press again to resume",
         "一時停止（現在のブロックの後で停止）。もう一度押すと再開します"],
    "已取消": ["Cancelled", "キャンセルしました"],
    "已取消（%s/%s 扇区）": ["Cancelled (%s/%s sectors)", "キャンセル（%s/%s セクタ）"],
    "已取消：完成 %s/%s 轨，%s": ["Cancelled: %s/%s tracks done, %s",
                                   "キャンセル：%s/%s トラック完了、%s"],
    "已删除本次生成的 %s 个文件": ["Deleted %s files created by this run",
                                    "今回生成した %s 個のファイルを削除しました"],
    "文件保留在 %s": ["Files kept in %s", "ファイルは %s に残っています"],
    "抓轨完成：%s/%s 轨，用时 %s 秒": ["Rip finished: %s/%s tracks in %s s",
                                       "取得完了：%s/%s トラック、%s 秒"],
    "封面已保存": ["Cover saved", "カバーを保存しました"],
    "日志：%s": ["Log: %s", "ログ：%s"],
    "重试 %s 次": ["%s retries", "リトライ %s 回"],
    "抓轨失败": ["Rip failed", "取得に失敗しました"],
    "抓轨失败：": ["Rip failed: ", "取得に失敗しました："],
    "抓轨失败: %s": ["Rip failed: %s", "取得に失敗しました：%s"],
    "  %02d  %6d 扇区  CRC32 %08X%s": ["  %02d  %6d sectors  CRC32 %08X%s",
                                       "  %02d  %6d セクタ  CRC32 %08X%s"],
    "自动（推荐）": ["Auto (recommended)", "自動（推奨）"],
    "已扫描": ["Scan finished", "スキャン完了"],
    "尚未扫描光驱": ["No drive scanned yet", "まだドライブをスキャンしていません"],
    "点「扫描」识别光盘；识别后可直接抓整盘":
        ["Press Scan to read the disc; then you can rip the whole album",
         "「スキャン」でディスクを認識します。その後まとめて取得できます"],
    "点「抓整盘」按当前设置输出；「查元数据」只联网不写文件":
        ["Press Rip album to write with the current settings; Refresh metadata only looks online",
         "「アルバムを取得」で現在の設定どおり書き出します。「メタデータ更新」は検索のみ"],
    "当前没有抓轨任务": ["No rip is running", "実行中の取得はありません"],
    "正在执行，等当前操作结束": ["Busy — wait for the current operation to finish",
                                 "実行中です。完了までお待ちください"],
    "先点「扫描」识别光盘": ["Press Scan first to read the disc",
                             "先に「スキャン」でディスクを認識してください"],
    "当前盘不是音频 CD（数据盘不可抓轨）":
        ["This disc is not an audio CD (data discs cannot be ripped)",
         "このディスクは音楽 CD ではありません（データディスクは取得できません）"],
    "非音频盘": ["non-audio disc", "非オーディオディスク"],
    "已有任务在进行": ["Another task is already running", "別の処理を実行中です"],
    "已有抓轨任务在进行": ["A rip is already running", "取得を実行中です"],

    # ── 扫描 / 光驱 ────────────────────────────────────────────────
    "面板已挂载": ["Panel mounted", "パネルを接続しました"],
    "扫描失败：%s": ["Scan failed: %s", "スキャンに失敗しました：%s"],
    "扫描失败: %s": ["Scan failed: %s", "スキャンに失敗しました：%s"],
    "没有检测到可用的光驱：光驱没连接，或驱动器里没有光盘。":
        ["No usable drive: not connected, or no disc in the drive.",
         "利用できるドライブがありません：未接続か、ディスクが入っていません。"],
    "找到了光驱，但读不到光盘信息": ["Drive found, but the disc could not be read",
                                     "ドライブは見つかりましたが、ディスクを読めません"],
    "：可能是空盘或光盘还没转起来": [": the disc may be blank or still spinning up",
                                      "：空のディスクか、回転が安定していない可能性があります"],
    "设置里指定了光驱 %s，但没找到它（已发现: ":
        ["Drive %s is selected in settings but was not found (found: ",
         "設定で指定したドライブ %s が見つかりません（検出: "],
    "请到「设备管理器 → DVD/CD-ROM 驱动器 → 禁用再启用」，或重启后再试。":
        ["Try Device Manager → DVD/CD-ROM drives → disable and re-enable, or reboot.",
         "「デバイスマネージャー → DVD/CD-ROM ドライブ → 無効にして再度有効」か再起動をお試しください。"],
    "光驱里没有光盘": ["No disc in the drive", "ドライブにディスクがありません"],
    "同一张盘放回来了": ["The same disc is back", "同じディスクが戻りました"],
    "已恢复上次的状态；未抓完的可以直接再点抓取（设置里开着断点续抓）":
        ["Previous state restored; an unfinished rip can continue (resume is on in settings)",
         "前回の状態に戻しました。未完了ならそのまま再取得できます（設定でレジューム有効）"],

    # ── SCSI 人话 ──────────────────────────────────────────────────
    "没有错误": ["No error", "エラーなし"],
    "已恢复的错误": ["Recovered error", "回復したエラー"],
    "驱动器未就绪": ["Drive not ready", "ドライブの準備ができていません"],
    "介质读取错误": ["Medium read error", "メディア読み取りエラー"],
    "驱动器硬件错误": ["Drive hardware error", "ドライブのハードウェアエラー"],
    "请求不被接受": ["Request not accepted", "要求が受け付けられません"],
    "驱动器状态已变化": ["Drive state changed", "ドライブの状態が変化しました"],
    "介质写保护": ["Medium is write protected", "メディアは書き込み禁止です"],
    "空白检查失败": ["Blank check failed", "ブランクチェックに失敗しました"],
    "命令被中止": ["Command aborted", "コマンドが中止されました"],
    "系统找不到这个驱动器（盘符可能已经变了）":
        ["Windows cannot find the drive (the letter may have changed)",
         "ドライブが見つかりません（ドライブレターが変わった可能性）"],
    "Windows 拒绝了访问：驱动器可能正被别的程序占用":
        ["Windows denied access: another program may be using the drive",
         "Windows がアクセスを拒否しました。他のプログラムが使用中の可能性があります"],
    "设备不存在": ["Device does not exist", "デバイスが存在しません"],
    "设备还没准备好（驱动器里可能没有光盘）":
        ["Device not ready (there may be no disc)", "デバイスの準備ができていません（ディスク未挿入の可能性）"],
    "设备被其它程序占用": ["Device is used by another program", "他のプログラムが使用中です"],
    "传给驱动器的参数不正确（这是插件的 bug，请把日志发给我们）":
        ["Invalid parameter passed to the drive (a plugin bug — please send us the log)",
         "ドライブに渡したパラメータが不正です（プラグインの不具合。ログをお送りください）"],
    "驱动器 I/O 失败（硬件或线缆问题）": ["Drive I/O failure (hardware or cable)",
                                          "ドライブの I/O に失敗（ハードウェアかケーブル）"],
    "未知状态 key=0x%02X": ["Unknown status key=0x%02X", "不明な状態 key=0x%02X"],
    "%s（ASC 0x%02X / ASCQ 0x%02X）": ["%s (ASC 0x%02X / ASCQ 0x%02X)",
                                       "%s（ASC 0x%02X / ASCQ 0x%02X）"],
    "光驱里没有光盘（或托盘没关上）": ["No disc in the drive (or the tray is open)",
                                       "ドライブにディスクがありません（トレイが開いている可能性）"],
    "光驱还没准备好：光盘可能刚放进去，等一两秒再试":
        ["Drive not ready yet: the disc may have just been inserted — wait a second",
         "まだ準備ができていません。ディスクを入れた直後かもしれません。少し待ってください"],
    "光盘不在正确的位置上": ["The disc is not in the correct position", "ディスクが正しい位置にありません"],
    "光盘已经更换：请点「扫描」重新识别这张盘":
        ["The disc was swapped: press Scan to read it again", "ディスクが交換されました。「スキャン」で再認識してください"],
    "驱动器被复位过：请点「扫描」重新识别": ["The drive was reset: press Scan again",
                                            "ドライブがリセットされました。「スキャン」で再認識してください"],
    "这不是音频 CD（数据盘/视频盘）：抓轨只支持音乐 CD":
        ["This is not an audio CD (data or video disc); only music CDs can be ripped",
         "音楽 CD ではありません（データ／ビデオディスク）。取得は音楽 CD のみ対応です"],
    "驱动器不认这张盘的格式": ["The drive does not recognise this disc format",
                               "このディスクの形式をドライブが認識できません"],
    "这张盘不支持这种读取方式（音轨模式不符）":
        ["This disc does not support that read mode (audio track mode mismatch)",
         "このディスクはこの読み取り方式に対応していません（音声トラックモード不一致）"],
    "驱动器不支持这条命令（读命令可换成 READ(10) 或 0xD8）":
        ["The drive does not support that command (try READ(10) or 0xD8)",
         "ドライブがこのコマンドに対応していません（READ(10) か 0xD8 をお試しください）"],
    "命令里的地址越界：这张盘可能已经换掉了":
        ["Address out of range: the disc may have been swapped", "アドレスが範囲外です。ディスクが交換された可能性"],
    "命令参数无效（CDB 字段不对）": ["Invalid command parameters (bad CDB field)",
                                     "コマンドパラメータが不正です（CDB フィールド）"],
    "音轨有无法纠正的读取错误（L-EC/CIRC）：盘面脏或划伤":
        ["Uncorrectable read error on the track (L-EC/CIRC): the disc is dirty or scratched",
         "訂正できない読み取りエラー（L-EC/CIRC）。ディスクの汚れか傷です"],
    "找不到要读的扇区": ["Sector not found", "読み取るセクタが見つかりません"],
    "驱动器报告通讯故障": ["The drive reports a communication failure",
                           "ドライブが通信障害を報告しました"],

    # ── 元数据 / 查询 ──────────────────────────────────────────────
    "查询失败：": ["Lookup failed: ", "検索に失敗しました："],
    "获取失败：": ["Fetch failed: ", "取得に失敗しました："],
    "没查到专辑信息": ["No album information found", "アルバム情報が見つかりませんでした"],
    "点「手动填写元数据…」自己写歌手/专辑名/歌名，或换一张盘再查":
        ["Press Fill in metadata… to type the artist/album/track names, or try another disc",
         "「メタデータを手入力…」でアーティスト／アルバム／曲名を入力するか、別のディスクでお試しください"],
    "已找到 %s 个候选": ["Found %s candidates", "%s 件の候補が見つかりました"],
    "当前：%s": ["Current: %s", "現在：%s"],
    "正在获取「%s」…": ["Fetching \"%s\"…", "「%s」を取得中…"],
    "已选用：%s - %s": ["Selected: %s - %s", "選択：%s - %s"],
    "已选用「%s - %s」": ["Selected \"%s - %s\"", "「%s - %s」を選択しました"],
    "网络不通：%s —— 可在设置里填 HTTP 代理，或直接手动填写":
        ["Network unreachable: %s — set an HTTP proxy in settings, or fill in metadata manually",
         "ネットワークに接続できません：%s。設定で HTTP プロキシを指定するか、手入力してください"],
    "网络自检失败：%s": ["Network self-check failed: %s", "ネットワーク診断に失敗しました：%s"],
    "%s（代理 %s）": ["%s (proxy %s)", "%s（プロキシ %s）"],
    "CD-TEXT：无": ["CD-TEXT: none", "CD-TEXT：なし"],
    "这张盘没有 CD-TEXT": ["This disc has no CD-TEXT", "このディスクには CD-TEXT がありません"],
    "CD-TEXT：%s 轨": ["CD-TEXT: %s tracks", "CD-TEXT：%s トラック"],
    "CD-TEXT 读取成功：%s 轨": ["CD-TEXT read: %s tracks", "CD-TEXT 読み取り成功：%s トラック"],
    "CD-TEXT 读取失败: %s": ["CD-TEXT read failed: %s", "CD-TEXT の読み取りに失敗：%s"],
    "读 CD-TEXT 失败：": ["Reading CD-TEXT failed: ", "CD-TEXT の読み取りに失敗："],
    "已用 CD-TEXT 填入可用的字段": ["Filled the available fields from CD-TEXT",
                                    "CD-TEXT から入力できる項目を埋めました"],
    "点选封面可换版本；抓轨会用选中版本的信息命名":
        ["Click a cover to switch versions; ripping uses the selected version",
         "カバーをクリックすると版を切り替えます。取得時は選択中の版の情報を使います"],
    "已保存手动元数据": ["Manual metadata saved", "手入力メタデータを保存しました"],
    "已保存手动元数据，抓轨会用这些名字": ["Manual metadata saved; ripping uses these names",
                                          "手入力メタデータを保存しました。取得時にこの名前を使います"],
    "手动填写元数据": ["Fill in metadata", "メタデータを手入力"],
    "已导出元数据模板": ["Metadata template exported", "メタデータのテンプレートを書き出しました"],
    "封面已选：": ["Cover selected: ", "カバーを選択："],

    # ── 设置 / 诊断 / 文件 ────────────────────────────────────────
    "保存失败：": ["Save failed: ", "保存に失敗しました："],
    "保存失败：读不到光盘，文件没有写入":
        ["Save failed: the disc could not be read, nothing was written",
         "保存に失敗しました。ディスクを読めないため、ファイルは書き込まれていません"],
    "打开手动填写失败：": ["Could not open the manual editor: ", "手入力画面を開けませんでした："],
    "读取失败：": ["Read failed: ", "読み取りに失敗しました："],
    "校验失败: %s": ["Verification failed: %s", "検証に失敗しました：%s"],
    "文件自洽，可以直接入库": ["Files are consistent and ready to use",
                               "ファイルは整合しています。そのまま使えます"],
    "无法打开目录: %s": ["Cannot open the folder: %s", "フォルダを開けません：%s"],
    "覆盖文件：%s": ["File to overwrite: %s", "上書きするファイル：%s"],
    "输出目录：%s": ["Output folder: %s", "出力先：%s"],
    "FLAC 编码器：%s": ["FLAC encoder: %s", "FLAC エンコーダ：%s"],
    "导出失败: %s": ["Export failed: %s", "書き出しに失敗しました：%s"],
    "导出诊断失败: %s": ["Diagnostics export failed: %s", "診断情報の書き出しに失敗しました：%s"],
    "间隙预扫失败: %s": ["Gap pre-scan failed: %s", "ギャップの事前スキャンに失敗しました：%s"],
    "计划生成失败: %s": ["Could not build the plan: %s", "計画の生成に失敗しました：%s"],
    "探测失败：%s": ["Probe failed: %s", "検出に失敗しました：%s"],
    "读命令探测完成（%s/%s 可用）": ["Read commands probed (%s/%s available)",
                                     "読み取りコマンドを検出（%s/%s 使用可）"],
    "测试完成：%s 轨，未写任何文件": ["Test finished: %s tracks, nothing written",
                                     "テスト完了：%s トラック、ファイルは書きません"],
    "测试失败：%s": ["Test failed: %s", "テストに失敗しました：%s"],
    "未知设置项: %s": ["Unknown setting: %s", "不明な設定項目：%s"],
    "已清除 %s 个元数据缓存文件": ["Cleared %s metadata cache files",
                                   "メタデータキャッシュ %s 件を削除しました"],

    # ── 设置页页名（左列导航） ────────────────────────────────────
    "输出位置": ["Output", "出力先"],
    "命名模板": ["Naming", "命名テンプレート"],
    "音频格式": ["Audio format", "オーディオ形式"],
    "标签与附属文件": ["Tags & extras", "タグと付属ファイル"],
    "元数据": ["Metadata", "メタデータ"],
    "光驱与校验": ["Drive & verification", "ドライブと検証"],
    "间隙": ["Gaps", "ギャップ"],
    "诊断": ["Diagnostics", "診断"],

    # ── 选项标签 / 下拉值 ─────────────────────────────────────────
    "自动（第一台）": ["Auto (first drive)", "自動（最初のドライブ）"],
    "加 (2) 另存": ["Add (2) and keep", "「(2)」を付けて保存"],
    "覆盖": ["Overwrite", "上書き"],
    "跳过": ["Skip", "スキップ"],
    "0＝自动": ["0 = auto", "0＝自動"],
    "0＝第一个候选": ["0 = first candidate", "0＝最初の候補"],
    "0 最快": ["0 = fastest", "0 最速"],
    "1-5,8；留空＝全部": ["1-5,8; empty = all tracks", "1-5,8；空＝すべて"],
    "16x（约 2400 KB/s）": ["16x (about 2400 KB/s)", "16x（約 2400 KB/s）"],
    "24x（约 3600 KB/s）": ["24x (about 3600 KB/s)", "24x（約 3600 KB/s）"],
    "2x（约 300 KB/s）": ["2x (about 300 KB/s)", "2x（約 300 KB/s）"],
    "4x（约 600 KB/s）": ["4x (about 600 KB/s)", "4x（約 600 KB/s）"],
    "8x（约 1200 KB/s，推荐）": ["8x (about 1200 KB/s, recommended)", "8x（約 1200 KB/s・推奨）"],
    "不干预（全速，最快但抖）": ["Leave alone (full speed, fastest but jittery)",
                                 "介入しない（最高速だが不安定）"],
    "丢弃间隙": ["Drop gaps", "ギャップを捨てる"],
    "中（推荐）": ["Medium (recommended)", "中（推奨）"],
    "低（快速）": ["Low (fast)", "低（高速）"],
    "高（苛刻）": ["High (strict)", "高（厳格）"],
    "仅查询元数据": ["Metadata lookup only", "メタデータ検索のみ"],
    "只测不写": ["Test only, no files", "テストのみ（書き込みなし）"],
    "取消当前抓轨": ["Cancel the rip", "取得を中止"],
    "恢复默认设置": ["Reset to defaults", "既定値に戻す"],
    "打开日志文件夹": ["Open the log folder", "ログフォルダを開く"],
    "扫描驱动器": ["Scan drives", "ドライブをスキャン"],
    "抓轨计划预览（不写盘）": ["Preview the plan (no writes)", "取得計画をプレビュー（書き込みなし）"],
    "探测可用读命令（只读）": ["Probe read commands (read-only)", "読み取りコマンドを検出（読み取りのみ）"],
    "导出元数据模板": ["Export a metadata template", "メタデータのテンプレートを書き出す"],
    "网络自检（音乐库 / CDDB）": ["Network self-check (MusicBrainz / CDDB)", "ネットワーク診断（MusicBrainz / CDDB）"],
    "间隙预扫（只读）": ["Pre-scan gaps (read-only)", "ギャップを事前スキャン（読み取りのみ）"],
    "试写测试（中文路径）": ["Write test (non-ASCII path)", "書き込みテスト（非 ASCII パス）"],
    "试抓第 1 轨": ["Test-rip track 1", "1 トラック目を試し取得"],
    "标准 MMC（0xBE）": ["Standard MMC (0xBE)", "標準 MMC（0xBE）"],
    "厂家 0xD8（实验性）": ["Vendor 0xD8 (experimental)", "ベンダー 0xD8（実験的）"],
    "子通道 Q（EAC 默认）": ["Subchannel Q (EAC default)", "サブチャネル Q（EAC 既定）"],
    "波形静音（Test Gaps On Silence）": ["Silence scan (Test Gaps On Silence)",
                                          "無音スキャン（Test Gaps On Silence）"],
    "附加到上一轨（EAC 默认）": ["Append to previous track (EAC default)", "前のトラックに追加（EAC 既定）"],
    "归入下一轨（非标准）": ["Prepend to next track (non-standard)", "次のトラックに含める（非標準）"],
    "整盘镜像时不能只抓部分音轨，也不能用 prepend/leaveout 间隙":
        ["Image mode cannot rip a subset of tracks, and cannot use prepend/leaveout gaps",
         "イメージ取得では一部トラックのみの取得や prepend/leaveout は使えません"],
    "原图（推荐）": ["Original (recommended)", "原本（推奨）"],
    "WAV（推荐）": ["WAV (recommended)", "WAV（推奨）"],
    "FLAC 没有标签就只剩裸音频": ["FLAC without tags is just bare audio",
                                  "FLAC はタグがないと素の音声だけになります"],
    "外部编码器（自定义命令行）": ["External encoder (custom command line)",
                                   "外部エンコーダ（独自コマンドライン）"],
    "留空走 PATH": ["Empty = use PATH", "空欄なら PATH を使用"],
    "留 flac 走 PATH": ["Empty = use flac from PATH", "空欄なら PATH の flac を使用"],
    "选择 flac.exe…": ["Choose flac.exe…", "flac.exe を選択…"],
    "选择输出目录…": ["Choose output folder…", "出力先を選択…"],
    "默认音乐库": ["Music folder by default", "既定はミュージックフォルダ"],
    "默认 8x：全速时驱动器噪音大、重读更频繁，剩余时间也更抖":
        ["8x by default: full speed is louder, re-reads more, and the ETA jitters",
         "既定は 8x：最高速だと駆動音が大きく、再読み取りも増え、残り時間もぶれます"],
    "正数＝丢弃开头若干采样": ["Positive = drop samples at the start", "正数＝先頭のサンプルを捨てる"],
    "5 默认": ["5 default", "5（既定）"],
    "8 最小": ["8 smallest", "8（最小）"],
    "%s 项": ["%s items", "%s 項目"],
    "已暂停 · 点「继续」接着抓": ["Paused · press Resume to continue", "一時停止 · 「再開」で続行"],
    "正在重读这一块 · 已等 %s 秒": ["Re-reading this chunk · waited %s s",
                                  "このブロックを再読み取り中 · %s 秒経過"],
    "专辑艺术家": ["Album artist", "アルバムアーティスト"],
    "专辑名": ["Album title", "アルバム名"],
    "年份": ["Year", "年"],
    "流派": ["Genre", "ジャンル"],
    "轨号 01": ["Track 01", "トラック 01"],
    "曲名": ["Track title", "曲名"],
    "曲目艺术家": ["Track artist", "曲アーティスト"],
    "碟号": ["Disc number", "ディスク番号"],
    "总碟数": ["Total discs", "ディスク総数"],
}

def main():
    doc = json.load(io.open(PATH, encoding="utf-8"))
    entries = doc["entries"]
    before = len(entries)
    added = 0
    for zh, pair in ADD.items():
        if zh in entries:
            continue
        entries[zh] = pair
        added += 1
    doc["_note"] = ("键是代码里的中文原文；值是 [en, ja]。查不到就原样显示中文。"
                    "新增文案后请跑 I18nTest 检查覆盖率。")
    io.open(PATH, "w", encoding="utf-8", newline="\n").write(
        json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
    print("entries", before, "->", len(entries), "added", added)

if __name__ == "__main__":
    main()
