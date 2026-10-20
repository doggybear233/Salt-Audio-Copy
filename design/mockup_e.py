"""E 方案（底部信息坞）设计稿：浅色 + 深色。

约束来源（m05913）：
  · D 方案（真正插进宿主页面流）不可行 —— 宿主 hook 注入的内容落在页面调用者的 Box 里，
    页面自己的 Column 内部无法插槽；因此只能覆盖式定位。
  · E 必须「和谁都不打架」：坞放在播放条正上方，留 14dp 间隙；横向与侧边栏保持 24dp 边距；
    页面自身内容在顶部，因此不与标题/设备行重叠。
"""

import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1600, 1000
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mockups")
os.makedirs(OUT, exist_ok=True)

FONT = "C:/Windows/Fonts/msyh.ttc"
FONT_B = "C:/Windows/Fonts/msyhbd.ttc"

LIGHT = dict(
    bg=(244, 238, 247), side=(250, 248, 252), line=(223, 213, 231),
    card=(252, 250, 253), card2=(243, 238, 247), title=(27, 27, 31),
    body=(90, 90, 102), faint=(140, 138, 150), accent=(108, 75, 182),
    accent_soft=(232, 224, 245), track=(228, 220, 238), on_accent=(255, 255, 255),
    wall=[(250, 196, 150), (196, 168, 224), (160, 200, 230)],
)
DARK = dict(
    bg=(24, 24, 28), side=(29, 29, 34), line=(58, 58, 68),
    card=(38, 38, 44), card2=(48, 48, 56), title=(236, 234, 240),
    body=(176, 174, 184), faint=(140, 138, 150), accent=(187, 168, 255),
    accent_soft=(56, 48, 82), track=(66, 66, 78), on_accent=(28, 24, 40),
    wall=[(96, 62, 96), (58, 72, 120), (30, 92, 108)],
)


def f(size, bold=False):
    return ImageFont.truetype(FONT_B if bold else FONT, size)


def base(pal, title="CD 工具箱"):
    img = Image.new("RGB", (W, H), pal["bg"])
    d = ImageDraw.Draw(img)
    wall = Image.new("RGB", (W, H), pal["bg"])
    wd = ImageDraw.Draw(wall)
    wd.ellipse((-200, 200, 700, 1100), fill=pal["wall"][0])
    wd.ellipse((500, 300, 1300, 1200), fill=pal["wall"][1])
    wd.ellipse((1000, -200, 1800, 700), fill=pal["wall"][2])
    wall = wall.filter(ImageFilter.GaussianBlur(64))
    img.paste(wall.transpose(Image.FLIP_TOP_BOTTOM), (0, 0))

    d.rectangle((0, 0, 152, H), fill=pal["side"])
    d.line((152, 0, 152, H), fill=pal["line"])
    items = ["歌曲", "曲风", "专辑", "艺术家", "文件夹", "音乐库", "设置"]
    y = 96
    for name in items:
        hot = name == "设置"
        if hot:
            d.rounded_rectangle((12, y - 6, 140, y + 26), radius=10, fill=pal["accent_soft"])
        d.ellipse((26, y + 2, 40, y + 16), fill=pal["accent"] if hot else pal["faint"])
        d.text((52, y), name, font=f(17), fill=pal["title"])
        y += 52

    d.text((560, 22), "─  □  ×", font=f(18), fill=pal["faint"])
    d.text((186, 52), "←", font=f(26), fill=pal["title"])
    d.text((222, 50), title, font=f(26, True), fill=pal["title"])

    # 播放条（坞必须避让的对象）
    d.rounded_rectangle((560, 806, 1260, 872), radius=33, fill=pal["side"])
    d.ellipse((586, 823, 618, 855), fill=pal["card2"])
    d.polygon([(597, 830), (597, 848), (610, 839)], fill=pal["body"])
    d.text((634, 820), "Vocaline", font=f(17), fill=pal["title"])
    d.text((634, 844), "Def Tech", font=f(14), fill=pal["faint"])
    return img, d


def rrect(d, box, radius, fill, outline=None, width=1):
    d.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def pill(d, x, y, text, pal, filled=True, size=14, h=34, pad=18):
    w = d.textlength(text, font=f(size, True)) + pad * 2
    if filled:
        rrect(d, (x, y, x + w, y + h), h // 2, pal["accent"])
        d.text((x + pad, y + (h - size) // 2 - 1), text, font=f(size, True), fill=pal["on_accent"])
    else:
        rrect(d, (x, y, x + w, y + h), h // 2, pal["card2"], outline=pal["line"])
        d.text((x + pad, y + (h - size) // 2 - 1), text, font=f(size, True), fill=pal["body"])
    return x + w


def dock(pal, label):
    img, d = base(pal)
    top, bottom = 676, 792          # 播放条上边缘 806，留 14dp 间隙
    left, right = 176, 1560
    rrect(d, (left, top, right, bottom), 20, pal["card"], outline=pal["line"], width=1)

    # 封面 + 专辑
    rrect(d, (left + 20, top + 18, left + 92, top + 90), 12, (196, 158, 58))
    d.text((left + 30, top + 46), "DEF TECH", font=f(11, True), fill=(255, 250, 235))

    x = left + 108
    d.text((x, top + 18), "Def Tech — GREATEST HITS", font=f(18, True), fill=pal["title"])
    d.text((x, top + 44), "HL-DT-ST BD-RE BP71N (F:) · 20 轨 · 1:18:54 · CD-DA",
           font=f(13), fill=pal["body"])

    # 进度条（独占一行，右侧接百分比，避免压住文字）
    bar = (x, top + 66, x + 420, top + 72)
    rrect(d, bar, 3, pal["track"])
    rrect(d, (bar[0], bar[1], bar[0] + int(420 * 0.62), bar[3]), 3, pal["accent"])
    d.text((x + 432, top + 60), "第 7/20 轨 · 62%", font=f(13, True), fill=pal["title"])

    # 输出预览
    d.text((x, top + 84), "C:\\Users\\62426\\Music\\Def Tech — GREATEST HITS\\05 Lovin' You.wav",
           font=f(12), fill=pal["faint"])

    # 右侧：速度/剩余 + 按钮
    d.text((right - 250, top + 22), "5.1x · 剩余 1 分 12 秒", font=f(13, True), fill=pal["body"])
    bx = right - 406
    bx = pill(d, bx, top + 44, "抓整盘", pal, filled=True) + 10
    bx = pill(d, bx, top + 44, "暂停", pal, filled=False) + 10
    bx = pill(d, bx, top + 44, "取消", pal, filled=False) + 10
    bx = pill(d, bx, top + 44, "设置", pal, filled=False) + 10
    pill(d, bx, top + 44, "候选 3", pal, filled=False)

    # 锚点标注
    d.line((left, bottom + 7, left + 60, bottom + 7), fill=(230, 90, 90), width=2)
    d.text((left + 68, bottom), "14dp 安全间隙（不压播放条）", font=f(12), fill=(200, 80, 80))
    d.text((left, 640), label, font=f(15, True), fill=pal["body"])
    return img


def stack(images, path, gap=16):
    w = images[0].width
    h = sum(i.height for i in images) + gap * (len(images) - 1)
    out = Image.new("RGB", (w, h), (32, 32, 36))
    y = 0
    for i in images:
        out.paste(i, (0, y))
        y += i.height + gap
    out.save(path)
    print("wrote", path)


if __name__ == "__main__":
    light = dock(LIGHT, "E′ 底部信息坞（浅色）—— 单行 4 段：封面 / 盘与专辑 / 进度与输出预览 / 动作")
    dark = dock(DARK, "E′ 底部信息坞（深色）—— 同一布局，仅换 palette，强调色改为浅紫")
    light.save(os.path.join(OUT, "E2-底部坞-浅色.png"))
    dark.save(os.path.join(OUT, "E2-底部坞-深色.png"))
    print("wrote", os.path.join(OUT, "E2-底部坞-浅色.png"))
    print("wrote", os.path.join(OUT, "E2-底部坞-深色.png"))
    stack([light, dark], os.path.join(OUT, "E2-底部坞-双主题.png"))
