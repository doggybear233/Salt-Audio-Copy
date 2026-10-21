"""生成盐专辑（Salt Audio Copy）的界面设计稿。

只做设计表达：1600x900 的宿主模拟底图 + 每种方案的关键区域。
不追求像素级还原宿主，目的是让用户一眼看懂「入口在哪、和谁抢位置」。
"""

import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1600, 1000
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mockups")
os.makedirs(OUT, exist_ok=True)

FONT = "C:/Windows/Fonts/msyh.ttc"
FONT_B = "C:/Windows/Fonts/msyhbd.ttc"


def f(size, bold=False):
    return ImageFont.truetype(FONT_B if bold else FONT, size)


# 宿主配色的近似值（取自用户截图：淡紫底 + 紫强调）
BG = (244, 238, 247)
CARD = (252, 250, 253)
CARD2 = (243, 238, 247)
LINE = (223, 213, 231)
TITLE = (27, 27, 31)
BODY = (90, 90, 102)
FAINT = (140, 138, 150)
ACCENT = (108, 75, 182)
ACCENT_SOFT = (232, 224, 245)
WHITE = (255, 255, 255)


def base(title):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    # 模糊壁纸（让浮层有对比度，也模拟宿主的半透明观感）
    wall = Image.new("RGB", (W, H), (226, 232, 240))
    wd = ImageDraw.Draw(wall)
    wd.ellipse((-200, 200, 700, 1100), fill=(250, 196, 150))
    wd.ellipse((500, 300, 1300, 1200), fill=(196, 168, 224))
    wd.ellipse((1000, -200, 1800, 700), fill=(160, 200, 230))
    wall = wall.filter(ImageFilter.GaussianBlur(60))
    img.paste(wall.transpose(Image.FLIP_TOP_BOTTOM), (0, 0))

    # 侧边栏
    d.rectangle((0, 0, 152, H), fill=(250, 248, 252))
    d.line((152, 0, 152, H), fill=LINE)
    items = ["歌曲", "曲风", "专辑", "艺术家", "文件夹", "音乐库", "设置"]
    y = 96
    for i, name in enumerate(items):
        hot = name == "设置"
        if hot:
            d.rounded_rectangle((12, y - 6, 140, y + 26), radius=10, fill=ACCENT_SOFT)
        d.ellipse((26, y + 2, 40, y + 16), fill=ACCENT if hot else (150, 146, 158))
        d.text((52, y), name, font=f(17), fill=TITLE)
        y += 52

    # 顶栏（窗口按钮）
    d.text((560, 22), "─  □  ×", font=f(18), fill=FAINT)
    # 返回 + 页面标题
    d.text((186, 52), "←", font=f(26), fill=TITLE)
    d.text((222, 50), title, font=f(26, True), fill=TITLE)

    # 底部播放条
    d.rounded_rectangle((560, 806, 1260, 872), radius=33, fill=(250, 249, 252))
    d.ellipse((586, 823, 618, 855), fill=(240, 240, 245))
    d.polygon([(597, 830), (597, 848), (610, 839)], fill=BODY)
    d.text((634, 820), "Vocaline", font=f(17), fill=TITLE)
    d.text((634, 844), "Def Tech", font=f(14), fill=FAINT)
    return img, d


def chip(d, x, y, text, pad=12, h=30, bg=CARD2, fg=BODY, outline=None, size=14, bold=False):
    w = d.textlength(text, font=f(size, bold)) + pad * 2
    if outline:
        d.rounded_rectangle((x, y, x + w, y + h), radius=h // 2, fill=bg, outline=outline)
    else:
        d.rounded_rectangle((x, y, x + w, y + h), radius=h // 2, fill=bg)
    d.text((x + pad, y + (h - size) // 2 - 2), text, font=f(size, bold), fill=fg)
    return w


def button(d, x, y, text, primary=False, w=None, h=34, size=14):
    tw = d.textlength(text, font=f(size)) + 34
    w = w or tw
    if primary:
        d.rounded_rectangle((x, y, x + w, y + h), radius=h // 2, fill=ACCENT)
        d.text((x + (w - d.textlength(text, font=f(size))) / 2, y + 8), text,
               font=f(size), fill=WHITE)
    else:
        d.rounded_rectangle((x, y, x + w, y + h), radius=h // 2, fill=CARD2)
        d.text((x + (w - d.textlength(text, font=f(size))) / 2, y + 8), text,
               font=f(size), fill=ACCENT)
    return w


def card(d, box, radius=18, fill=CARD, outline=LINE, shadow=True):
    if shadow:
        for i in range(6, 0, -1):
            d.rounded_rectangle((box[0] - i, box[1] - i + 2, box[2] + i, box[3] + i + 2),
                                radius=radius + i, fill=(236, 232, 242))
    d.rounded_rectangle(box, radius=radius, fill=fill, outline=outline)


def progress(d, x, y, w, pct=0.62, h=6):
    d.rounded_rectangle((x, y, x + w, y + h), radius=h // 2, fill=(226, 219, 235))
    d.rounded_rectangle((x, y, x + int(w * pct), y + h), radius=h // 2, fill=ACCENT)


def cover(d, box, label=""):
    d.rounded_rectangle(box, radius=8, fill=(196, 150, 70))
    d.rectangle((box[0] + 8, box[1] + 8, box[2] - 8, box[1] + 26), fill=(214, 170, 90))
    if label:
        d.text((box[0] + 10, box[3] - 26), label, font=f(12, True), fill=(60, 38, 10))


def caption(d, lines, x=186, y=None):
    y = y or (H - 88)
    for i, (text, size, bold, color) in enumerate(lines):
        d.text((x, y), text, font=f(size, bold), fill=color)
        y += size + 10


# ----------------------------------------------------------------- 方案 A
def design_a():
    img, d = base("CD 工具箱")
    d.text((186, 106), "● HL-DT-ST BD-RE BP71N (F:)", font=f(16), fill=BODY)
    d.text((210, 132), "4234 KB/s", font=f(13), fill=FAINT)

    box = (1160, 620, 1560, 792)
    card(d, box)
    d.text((1182, 640), "盐专辑", font=f(17, True), fill=TITLE)
    chip(d, 1256, 636, "CD-DA", pad=8, h=22, bg=ACCENT_SOFT, fg=ACCENT, size=12)
    d.text((1496, 641), "设置", font=f(13), fill=ACCENT)
    d.text((1182, 668), "20 轨 · 1:18:54 · 已找到 1 个候选", font=f(13), fill=BODY)
    d.text((1182, 690), "抓取第 7/20 轨 · 62%", font=f(13), fill=TITLE)
    progress(d, 1182, 716, 356)
    d.text((1182, 728), "5.1x · 剩余约 1 分 12 秒", font=f(12), fill=FAINT)
    x = 1182
    for t, p in (("暂停", False), ("取消", False)):
        x += button(d, x, 750, t, primary=False, h=30, size=13) + 8

    caption(d, [("A. 右下角浮卡（紧凑版）", 24, True, TITLE),
                ("只留状态 + 进度 + 暂停/取消；扫描、抓整盘、设置搬进 CD 工具箱页内的工具栏", 16, False, BODY)])
    return img


# ----------------------------------------------------------------- 方案 B
def design_b():
    img, d = base("盐专辑")
    d.text((186, 100), "● HL-DT-ST BD-RE BP71N (F:)   CD-DA   20 轨 · 1:18:54", font=f(16), fill=BODY)
    d.line((186, 134, 1560, 134), fill=LINE)

    # 左：封面 + 候选
    cover(d, (186, 154, 426, 394), "DEF TECH")
    d.text((186, 408), "Def Tech — GREATEST HITS", font=f(15, True), fill=TITLE)
    d.text((186, 430), "2008 · J-Pop · 1 张封面", font=f(13), fill=FAINT)
    for i, name in enumerate(["A", "B", "C"]):
        x = 186 + i * 76
        d.rounded_rectangle((x, 456, x + 64, 520), radius=8,
                            fill=(214, 178, 110) if i else (196, 150, 70),
                            outline=ACCENT if i == 0 else LINE, width=2 if i == 0 else 1)
        d.text((x + 6, 500), name, font=f(12, True), fill=(60, 38, 10))
    d.text((186, 532), "点封面换版本", font=f(12), fill=FAINT)

    # 右：音轨表
    tx = 470
    d.text((tx, 154), "轨", font=f(13), fill=FAINT)
    d.text((tx + 46, 154), "标题", font=f(13), fill=FAINT)
    d.text((tx + 720, 154), "时长", font=f(13), fill=FAINT)
    d.text((tx + 830, 154), "状态", font=f(13), fill=FAINT)
    rows = [("01", "Vocaline", "4:21", "完成"), ("02", "My Way", "4:02", "完成"),
            ("03", "Broken Hearts", "3:57", "完成"), ("04", "Quality Time", "4:12", "完成"),
            ("05", "Lovin' You", "4:35", "读取中"), ("06", "A New Day", "4:00", ""),
            ("07", "U Love Me", "3:48", ""), ("08", "Step 4 Step", "4:10", "")]
    y = 182
    for i, (n, t, dur, st) in enumerate(rows):
        if i == 4:
            d.rounded_rectangle((tx - 12, y - 8, 1548, y + 30), radius=10, fill=ACCENT_SOFT)
        d.text((tx, y), n, font=f(14), fill=BODY)
        d.text((tx + 46, y), t, font=f(14), fill=TITLE)
        d.text((tx + 720, y), dur, font=f(14), fill=BODY)
        if st:
            d.text((tx + 830, y), st, font=f(13), fill=ACCENT if st == "读取中" else FAINT)
        if i == 4:
            progress(d, 1230, y + 4, 200, 0.62)
            d.text((1444, y), "62%", font=f(13), fill=ACCENT)
        y += 40

    # 底部操作栏
    d.line((186, 726, 1560, 726), fill=LINE)
    x = 186
    x += button(d, x, 750, "抓整盘", primary=True) + 10
    x += button(d, x, 750, "只测不写") + 10
    x += button(d, x, 750, "暂停") + 10
    x += button(d, x, 750, "取消") + 10
    x += button(d, x, 750, "打开输出目录") + 10
    d.text((1220, 760), "输出：C:\\Users\\user\\Music\\Def Tech — GREATEST HITS", font=f(13), fill=FAINT)

    caption(d, [("B. 侧边栏独立页面「盐专辑」", 24, True, TITLE),
                ("最接近 EAC 的布局：左封面候选、右音轨表、底部操作栏；与 CD 工具箱并列，互不遮挡", 16, False, BODY)])
    return img


# ----------------------------------------------------------------- 方案 C
def design_c():
    img, d = base("CD 工具箱")
    d.text((186, 106), "● HL-DT-ST BD-RE BP71N (F:)", font=f(16), fill=BODY)

    # 独立窗口（可移动/可最小化）
    box = (820, 210, 1420, 640)
    for i in range(10, 0, -1):
        d.rounded_rectangle((box[0] - i, box[1] - i + 4, box[2] + i, box[3] + i + 4),
                            radius=16 + i, fill=(232, 228, 238))
    d.rounded_rectangle(box, radius=16, fill=CARD, outline=LINE)
    d.text((846, 228), "盐专辑 — Salt Audio Copy", font=f(15, True), fill=TITLE)
    d.text((1352, 226), "—  □  x", font=f(16), fill=FAINT)
    d.line((820, 258, 1420, 258), fill=LINE)

    cover(d, (846, 280, 966, 400), "DEF TECH")
    d.text((986, 284), "Def Tech — GREATEST HITS", font=f(16, True), fill=TITLE)
    d.text((986, 308), "20 轨 · 1:18:54 · CD-DA", font=f(13), fill=BODY)
    d.text((986, 330), "第 7/20 轨 · 62% · 5.1x", font=f(13), fill=TITLE)
    progress(d, 986, 356, 400)
    d.text((986, 368), "剩余约 1 分 12 秒", font=f(12), fill=FAINT)

    d.line((846, 416, 1394, 416), fill=LINE)
    d.text((846, 432), "输出目录", font=f(13), fill=BODY)
    d.text((940, 432), "C:\\Users\\user\\Music", font=f(13), fill=TITLE)
    d.text((1300, 430), "选择", font=f(13), fill=ACCENT)

    d.text((846, 466), "专辑目录模板", font=f(12), fill=FAINT)
    d.rounded_rectangle((846, 484, 1394, 516), radius=8, fill=CARD2, outline=LINE)
    d.text((858, 492), "%albumartist% / %albumtitle%", font=f(13), fill=TITLE)
    d.text((846, 528), "文件名模板", font=f(12), fill=FAINT)
    d.rounded_rectangle((846, 546, 1394, 578), radius=8, fill=CARD2, outline=LINE)
    d.text((858, 554), "%tracknr2% %title%", font=f(13), fill=TITLE)
    d.text((846, 590), "把光标放进上面的框，再点下面的变量块即可插入", font=f(12), fill=FAINT)
    for i, t in enumerate(["专辑艺术家", "专辑名", "年份", "轨号（1,2,3）", "轨号（01,02）"]):
        chip(d, 846 + i * 104, 610, t, pad=8, h=26, size=12)

    # 收起态胶囊
    d.rounded_rectangle((1240, 700, 1560, 752), radius=26, fill=CARD, outline=LINE)
    d.text((1262, 714), "盐专辑 · 62%  >", font=f(14, True), fill=ACCENT)

    caption(d, [("C. 独立可隐藏浮窗 + 收起胶囊", 24, True, TITLE),
                ("插件自己开一个窗口（像桌面歌词插件那样）：可移动、可最小化到右下胶囊；完全不占宿主布局", 16, False, BODY)])
    return img


# ----------------------------------------------------------------- 方案 D
def design_d():
    img, d = base("CD 工具箱")
    d.text((186, 100), "● HL-DT-ST BD-RE BP71N (F:)   4234 KB/s", font=f(16), fill=BODY)

    box = (186, 168, 1560, 470)
    card(d, box, radius=16)
    d.text((210, 190), "盐专辑", font=f(18, True), fill=TITLE)
    chip(d, 286, 186, "CD-DA", pad=8, h=22, bg=ACCENT_SOFT, fg=ACCENT, size=12)
    d.text((1500, 192), "^", font=f(18), fill=FAINT)

    cover(d, (210, 224, 310, 324), "DEF TECH")
    d.text((330, 228), "Def Tech — GREATEST HITS", font=f(17, True), fill=TITLE)
    d.text((330, 254), "20 轨 · 1:18:54 · 已找到 1 个候选", font=f(13), fill=BODY)
    d.text((330, 278), "抓取第 7/20 轨 · 62% · 5.1x · 剩余约 1 分 12 秒", font=f(13), fill=TITLE)
    progress(d, 330, 304, 1180)
    x = 330
    for t, p in (("暂停", False), ("取消", False), ("刷新元数据", False)):
        x += button(d, x, 326, t, primary=p, h=30, size=13) + 8

    d.line((186, 492, 1560, 492), fill=LINE)
    d.text((186, 512), "曲目（点一行可单独抓取）", font=f(14, True), fill=TITLE)
    rows = [("01", "Vocaline", "4:21", "完成"), ("02", "My Way", "4:02", "完成"),
            ("03", "Broken Hearts", "3:57", "完成"), ("04", "Quality Time", "4:12", "读取中")]
    y = 546
    for n, t, dur, st in rows:
        d.text((186, y), n, font=f(14), fill=BODY)
        d.text((232, y), t, font=f(14), fill=TITLE)
        d.text((900, y), dur, font=f(14), fill=BODY)
        d.text((1010, y), st, font=f(13), fill=ACCENT if st == "读取中" else FAINT)
        y += 38

    caption(d, [("D. CD 工具箱页内嵌区块（可折叠）", 24, True, TITLE),
                ("像页面上的一张卡：标题行可折叠，展开后显示封面、进度与曲目表；与宿主页面同宽同风格", 16, False, BODY)])
    return img


# ----------------------------------------------------------------- 方案 E
def design_e():
    img, d = base("CD 工具箱")
    d.text((186, 100), "● HL-DT-ST BD-RE BP71N (F:)   20 轨 · 1:18:54", font=f(16), fill=BODY)

    # 底部 dock（横跨内容区，紧贴播放条上方）
    box = (186, 700, 1560, 786)
    card(d, box, radius=14)
    d.text((210, 716), "盐专辑", font=f(15, True), fill=TITLE)
    d.text((272, 716), "第 7/20 轨 · 62% · 5.1x · 剩余约 1 分 12 秒", font=f(13), fill=BODY)
    progress(d, 210, 742, 620)
    x = 860
    for t, p in (("扫描", False), ("抓整盘", True), ("暂停", False), ("取消", False), ("设置", False)):
        x += button(d, x, 730, t, primary=p, h=32, size=13) + 8
    d.text((1380, 738), "打开目录", font=f(13), fill=ACCENT)

    caption(d, [("E. 底部 Dock 控制条", 24, True, TITLE),
                ("横跨整个内容区、紧贴播放条上方；像播放器那样常驻，信息一行看完，最省垂直空间", 16, False, BODY)])
    return img


# ----------------------------------------------------------------- 方案 F
def design_f():
    img, d = base("设置")
    d.text((186, 100), "插件", font=f(16, True), fill=TITLE)

    box = (186, 140, 1180, 700)
    card(d, box, radius=16)
    d.text((214, 164), "盐专辑 (Salt Audio Copy)", font=f(17, True), fill=TITLE)
    d.text((214, 190), "版本 0.28.0 · 已启用 · 权限：class-transform", font=f(13), fill=FAINT)
    d.line((186, 216, 1180, 216), fill=LINE)

    d.text((214, 236), "抓轨", font=f(15, True), fill=TITLE)
    x = 214
    for t in ("开始抓整盘", "只测不写", "暂停", "取消"):
        x += button(d, x, 262, t, primary=(t == "开始抓整盘"), h=32, size=13) + 8
    d.text((214, 306), "上次：Def Tech — GREATEST HITS · 62% · 剩余约 1 分 12 秒", font=f(13), fill=BODY)
    progress(d, 214, 330, 800)

    d.text((214, 366), "输出与命名", font=f(15, True), fill=TITLE)
    d.text((214, 394), "输出目录", font=f(12), fill=FAINT)
    d.rounded_rectangle((214, 412, 1140, 446), radius=8, fill=CARD2, outline=LINE)
    d.text((226, 420), "C:\\Users\\user\\Music", font=f(13), fill=TITLE)
    d.text((300, 462), "专辑目录模板  %albumartist% / %albumtitle%", font=f(13), fill=BODY)
    d.text((300, 488), "文件名模板    %tracknr2% %title%", font=f(13), fill=BODY)

    d.text((214, 528), "高级（默认收起）", font=f(15, True), fill=TITLE)
    d.text((214, 556), "▸ 编码 / 校验 / 间隙 / 测试动作 / 诊断", font=f(13), fill=ACCENT)

    # toast
    d.rounded_rectangle((1240, 640, 1560, 700), radius=12, fill=(52, 50, 58))
    d.text((1262, 656), "正在抓取 第 7/20 轨 · 62%", font=f(14), fill=WHITE)
    d.text((1262, 676), "点这里查看详情", font=f(12), fill=(198, 190, 210))

    caption(d, [("F. 全部收进插件配置页（无面板）", 24, True, TITLE),
                ("宿主设置页里就是完整界面；CD 页只在需要时冒一条 toast。改成这样等于放弃「面板」", 16, False, BODY)])
    return img


# ----------------------------------------------------------------- 方案 G
def design_g():
    img, d = base("CD 工具箱")
    d.text((186, 106), "● HL-DT-ST BD-RE BP71N (F:)", font=f(16), fill=BODY)

    # 右上悬浮按钮
    d.ellipse((1470, 96, 1546, 172), fill=CARD, outline=LINE)
    d.polygon([(1500, 126), (1500, 146), (1518, 136)], fill=ACCENT)

    # 展开后的抽屉（从右侧滑出）
    box = (1080, 190, 1560, 640)
    card(d, box)
    d.text((1102, 208), "盐专辑", font=f(16, True), fill=TITLE)
    d.text((1508, 208), "x", font=f(16), fill=FAINT)
    d.text((1102, 236), "20 轨 · 1:18:54 · CD-DA", font=f(13), fill=BODY)
    d.text((1102, 262), "第 7/20 轨 · 62% · 5.1x", font=f(13), fill=TITLE)
    progress(d, 1102, 288, 436)
    d.text((1102, 300), "剩余约 1 分 12 秒", font=f(12), fill=FAINT)
    d.line((1102, 330, 1540, 330), fill=LINE)
    d.text((1102, 344), "抓整盘前的准备", font=f(13, True), fill=TITLE)
    for i, t in enumerate(["扫描光盘", "刷新元数据", "选封面版本", "预览抓轨计划"]):
        d.rounded_rectangle((1102, 368 + i * 40, 1540, 400 + i * 40), radius=8, fill=CARD2)
        d.text((1116, 376 + i * 40), t, font=f(13), fill=TITLE)
        d.text((1516, 376 + i * 40), ">", font=f(14), fill=FAINT)

    caption(d, [("G. 页面右上悬浮按钮 + 侧边抽屉", 24, True, TITLE),
                ("默认只有一个圆按钮，点开是右侧抽屉；适合「偶尔用一次」，但操作路径比面板多一次点击", 16, False, BODY)])
    return img


# ----------------------------------------------------------------- 方案 H
def design_h():
    img, d = base("盐专辑")
    d.text((186, 100), "● HL-DT-ST BD-RE BP71N (F:)   CD-DA   20 轨 · 1:18:54", font=f(16), fill=BODY)
    d.line((186, 134, 1560, 134), fill=LINE)

    cover(d, (186, 154, 386, 354), "DEF TECH")
    d.text((186, 368), "Def Tech", font=f(15), fill=BODY)
    d.text((186, 390), "GREATEST HITS", font=f(20, True), fill=TITLE)
    d.text((186, 420), "2008 · J-Pop · 20 轨 · 1:18:54", font=f(13), fill=FAINT)

    # 步骤条：EAC 式向导
    steps = ["1 识别光盘", "2 选择版本", "3 检查设置", "4 抓取", "5 完成"]
    x = 460
    for i, s in enumerate(steps):
        done = i < 2
        cur = i == 3
        col = ACCENT if (done or cur) else FAINT
        d.ellipse((x, 158, x + 22, 180), fill=ACCENT if cur else (ACCENT_SOFT if done else CARD2))
        d.text((x + 6, 161), str(i + 1), font=f(12, True), fill=WHITE if cur else col)
        d.text((x + 30, 160), s, font=f(13, True if cur else False), fill=col)
        if i < len(steps) - 1:
            d.line((x + 118, 169, x + 160, 169), fill=LINE)
        x += 190

    d.text((460, 214), "正在抓取：第 7 轨 / 20", font=f(18, True), fill=TITLE)
    progress(d, 460, 248, 1060, 0.62, h=10)
    d.text((460, 268), "5.1x · 已用 6 分 12 秒 · 剩余约 1 分 12 秒 · 0 错误", font=f(13), fill=BODY)

    # 曲目网格
    y = 306
    for row in range(3):
        for col in range(4):
            i = row * 4 + col
            if i >= 8:
                break
            bx = 460 + col * 268
            by = y + row * 116
            d.rounded_rectangle((bx, by, bx + 248, by + 100), radius=10, fill=CARD, outline=LINE)
            d.text((bx + 12, by + 12), f"{i + 1:02d}", font=f(12), fill=FAINT)
            d.text((bx + 12, by + 34), ["Vocaline", "My Way", "Broken Hearts", "Quality Time",
                                        "Lovin' You", "A New Day", "U Love Me", "Step 4 Step"][i],
                   font=f(14, True), fill=TITLE)
            if i < 5:
                d.text((bx + 12, by + 62), "✓ 完成 · CRC 一致", font=f(12), fill=(60, 150, 110))
            elif i == 5:
                d.text((bx + 12, by + 62), "读取中 62%", font=f(12), fill=ACCENT)
                progress(d, bx + 12, by + 82, 224, 0.62, h=4)
            else:
                d.text((bx + 12, by + 62), "等待中", font=f(12), fill=FAINT)

    x = 460
    for t, p in (("暂停", False), ("取消本次", False), ("打开输出目录", False)):
        x += button(d, x, 720, t, primary=p, h=34, size=14) + 10

    caption(d, [("H. 「盐专辑」页 + EAC 式步骤向导", 24, True, TITLE),
                ("用步骤条把「识别 → 选版本 → 检查 → 抓取 → 完成」显式化，曲目用卡片网格；最像 EAC 的向导流程", 16, False, BODY)])
    return img


DESIGNS = {
    "A-右下浮卡": design_a,
    "B-侧边栏页面": design_b,
    "C-独立浮窗": design_c,
    "D-页内区块": design_d,
    "E-底部Dock": design_e,
    "F-仅配置页": design_f,
    "G-悬浮按钮抽屉": design_g,
    "H-向导式页面": design_h,
}

if __name__ == "__main__":
    made = []
    for name, fn in DESIGNS.items():
        im = fn()
        path = os.path.join(OUT, name + ".png")
        im.save(path)
        made.append(path)
        print("wrote", path)
    print(len(made), "designs")
    contact_sheet()


def contact_sheet():
    names = list(DESIGNS.keys())
    thumbs = []
    for n in names:
        im = Image.open(os.path.join(OUT, n + ".png"))
        thumbs.append((n, im.resize((640, 400), Image.LANCZOS)))
    cols, rows = 2, 4
    sheet = Image.new("RGB", (cols * 660 + 20, rows * 440 + 20), (238, 234, 242))
    d = ImageDraw.Draw(sheet)
    for i, (n, im) in enumerate(thumbs):
        x = 20 + (i % cols) * 660
        y = 20 + (i // cols) * 440
        sheet.paste(im, (x, y))
        d.text((x + 6, y + 404), n, font=f(15, True), fill=(40, 40, 48))
    path = os.path.join(OUT, "全部方案.png")
    sheet.save(path)
    print("wrote", path)
