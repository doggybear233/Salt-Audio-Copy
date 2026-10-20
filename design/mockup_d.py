"""D 方案（页内区块）设计稿：真正插进宿主页面 Column 的流式排版，浅色/深色玻璃质感。"""

import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1600, 1000
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mockups")
os.makedirs(OUT, exist_ok=True)
FONT = "C:/Windows/Fonts/msyh.ttc"
FONT_B = "C:/Windows/Fonts/msyhbd.ttc"

LIGHT = dict(
    side=(252, 250, 253), line=(226, 218, 234), title=(23, 23, 28),
    body=(88, 88, 100), faint=(146, 143, 156), accent=(108, 75, 182),
    accent_soft=(234, 226, 246), on_accent=(255, 255, 255),
    glass=(255, 255, 255, 132), glass_edge=(255, 255, 255, 190), shadow=(120, 110, 140),
    track=(216, 208, 230), wall=[(250, 196, 150), (196, 168, 224), (160, 200, 230)],
)
DARK = dict(
    side=(28, 28, 33), line=(58, 58, 68), title=(238, 236, 242),
    body=(178, 176, 188), faint=(140, 138, 152), accent=(187, 168, 255),
    accent_soft=(58, 50, 84), on_accent=(28, 24, 40),
    glass=(24, 24, 30, 170), glass_edge=(255, 255, 255, 46), shadow=(0, 0, 0),
    track=(72, 72, 86), wall=[(96, 62, 96), (58, 72, 120), (30, 92, 108)],
)


def f(size, bold=False):
    return ImageFont.truetype(FONT_B if bold else FONT, size)


def wallpaper(pal):
    wall = Image.new("RGB", (W, H), pal["side"])
    wd = ImageDraw.Draw(wall)
    wd.ellipse((-200, 200, 700, 1100), fill=pal["wall"][0])
    wd.ellipse((500, 300, 1300, 1200), fill=pal["wall"][1])
    wd.ellipse((1000, -200, 1800, 700), fill=pal["wall"][2])
    return wall.filter(ImageFilter.GaussianBlur(64)).transpose(Image.FLIP_TOP_BOTTOM)


def glass_panel(img, box, radius, pal):
    """半透明玻璃面板：先画阴影，再用 alpha 合成面板与 1px 高光边。"""
    x0, y0, x1, y1 = box
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rounded_rectangle((x0 + 2, y0 + 8, x1 + 2, y1 + 10), radius=radius,
                         fill=pal["shadow"] + (60,))
    shadow = shadow.filter(ImageFilter.GaussianBlur(14))
    img.alpha_composite(shadow)

    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ld.rounded_rectangle(box, radius=radius, fill=pal["glass"])
    # 顶部一层极淡高光，模拟玻璃反射
    ld.rounded_rectangle((x0 + 1, y0 + 1, x1 - 1, y0 + 34), radius=radius,
                         fill=(255, 255, 255, 26 if pal["side"][0] > 128 else 12))
    ld.rounded_rectangle(box, radius=radius, outline=pal["glass_edge"], width=1)
    img.alpha_composite(layer)


def rx(d, box, r, fill, outline=None, width=1):
    d.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=width)


def pill(d, x, y, text, pal, filled=False, size=13, h=32, pad=16):
    w = d.textlength(text, font=f(size, True)) + pad * 2
    if filled:
        rx(d, (x, y, x + w, y + h), h // 2, pal["accent"])
        d.text((x + pad, y + (h - size) // 2 - 1), text, font=f(size, True), fill=pal["on_accent"])
    else:
        rx(d, (x, y, x + w, y + h), h // 2, pal["accent_soft"] if pal["side"][0] > 128 else (255, 255, 255, 22),
           outline=pal["line"])
        d.text((x + pad, y + (h - size) // 2 - 1), text, font=f(size, True), fill=pal["body"])
    return x + w


def scene(pal, label):
    img = wallpaper(pal).convert("RGBA")
    img.alpha_composite(Image.new("RGBA", (W, H), pal["side"] + (0,)) )
    d = ImageDraw.Draw(img)

    # 侧边栏
    d.rectangle((0, 0, 152, H), fill=pal["side"])
    d.line((152, 0, 152, H), fill=pal["line"])
    y = 96
    for name in ["歌曲", "曲风", "专辑", "艺术家", "文件夹", "音乐库", "设置"]:
        hot = name == "设置"
        if hot:
            d.rounded_rectangle((12, y - 6, 140, y + 26), radius=10, fill=pal["accent_soft"])
        d.ellipse((26, y + 2, 40, y + 16), fill=pal["accent"] if hot else pal["faint"])
        d.text((52, y), name, font=f(17), fill=pal["title"])
        y += 52

    # 宿主页面头（返回 + 标题 + 设备行）
    d.text((186, 52), "←", font=f(26), fill=pal["title"])
    d.text((222, 50), "CD 工具箱", font=f(26, True), fill=pal["title"])
    d.text((190, 104), "●", font=f(14), fill=pal["accent"])
    d.text((210, 100), "HL-DT-ST BD-RE BP71N (F:)", font=f(15), fill=pal["body"])
    d.text((210, 124), "4234 KB/s", font=f(13), fill=pal["faint"])

    # 插件区块：位于页面 Column 流中，紧接设备行之后
    box = (176, 164, 1560, 300)
    glass_panel(img, box, 20, pal)
    d = ImageDraw.Draw(img)
    x = box[0] + 22
    y = box[1] + 20
    rx(d, (x, y, x + 64, y + 64), 12, (198, 160, 60))
    d.text((x + 8, y + 24), "DEF TECH", font=f(10, True), fill=(255, 252, 240))

    tx = x + 82
    d.text((tx, y - 2), "Def Tech — GREATEST HITS", font=f(18, True), fill=pal["title"])
    d.text((tx, y + 24), "20 轨 · 1:18:54 · CD-DA · 已找到 1 个候选", font=f(13), fill=pal["body"])

    bar = (tx, y + 48, tx + 300, y + 54)
    rx(d, bar, 3, pal["track"])
    rx(d, (bar[0], bar[1], bar[0] + 186, bar[3]), 3, pal["accent"])
    d.text((tx + 312, y + 42), "第 7/20 轨 · 62%", font=f(13, True), fill=pal["title"])
    d.text((tx + 460, y + 43), "5.1x · 剩余 1 分 12 秒", font=f(13), fill=pal["body"])

    d.text((tx, y + 68), "C:\\Users\\62426\\Music\\Def Tech — GREATEST HITS\\05 Lovin' You.wav",
           font=f(12), fill=pal["faint"])

    # 右侧动作
    bx = box[2] - 22 - 430
    d.text((box[2] - 22 - 120, box[1] + 20), "候选 1 / 1", font=f(12), fill=pal["faint"])
    bx = pill(d, bx, box[1] + 46, "抓整盘", pal, filled=True) + 8
    bx = pill(d, bx, box[1] + 46, "暂停", pal) + 8
    bx = pill(d, bx, box[1] + 46, "取消", pal) + 8
    pill(d, bx, box[1] + 46, "设置", pal)

    # 说明注记（不遮挡内容）
    # 页面下方内容占位（表示宿主自己的区域被顺延而不是被盖住）
    veil = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    vd = ImageDraw.Draw(veil)
    vd.rounded_rectangle((176, 356, 1560, 700), radius=14,
                         fill=(255, 255, 255, 96) if pal["side"][0] > 128 else (255, 255, 255, 18),
                         outline=(255, 255, 255, 120) if pal["side"][0] > 128 else (255, 255, 255, 30))
    img.alpha_composite(veil)
    d = ImageDraw.Draw(img)
    d.text((200, 380), "（宿主页面原有内容/音轨表占位——被区块自然下移，而不是被遮挡）",
           font=f(13), fill=pal["faint"])

    # 播放条
    d.rounded_rectangle((560, 806, 1260, 872), radius=33, fill=pal["side"])
    d.ellipse((586, 823, 618, 855), fill=pal["accent_soft"] if pal["side"][0] > 128 else (255, 255, 255, 20))
    d.polygon([(597, 830), (597, 848), (610, 839)], fill=pal["body"])
    d.text((634, 820), "Vocaline", font=f(17), fill=pal["title"])
    d.text((634, 844), "Def Tech", font=f(14), fill=pal["faint"])

    d.text((176, 930), label, font=f(15, True), fill=pal["body"])
    return img.convert("RGB")


def stack(images, path, gap=14):
    w, h = images[0].size
    out = Image.new("RGB", (w, sum(i.height for i in images) + gap * (len(images) - 1)), (30, 30, 34))
    y = 0
    for i in images:
        out.paste(i, (0, y))
        y += i.height + gap
    out.save(path)
    print("wrote", path)


if __name__ == "__main__":
    light = scene(LIGHT, "D′ 页内区块（浅色 / 玻璃）—— 插入页面 Column，流式排版")
    dark = scene(DARK, "D′ 页内区块（深色 / 玻璃）—— 同结构，透明度与描边随主题切换")
    light.save(os.path.join(OUT, "D2-页内区块-浅色.png"))
    dark.save(os.path.join(OUT, "D2-页内区块-深色.png"))
    print("wrote D2-页内区块-浅色/深色")
    stack([light, dark], os.path.join(OUT, "D2-页内区块-双主题.png"))
