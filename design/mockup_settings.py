# -*- coding: utf-8 -*-
"""F1：新设置面板（按 SettingsPages.kt 的尺寸绘制）——浅色 + 深色。"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

FONT = "C:/Windows/Fonts/msyh.ttc"
FONT_B = "C:/Windows/Fonts/msyhbd.ttc"
W, H = 1500, 470
OUT = os.path.join(os.path.dirname(__file__), "mockups")
os.makedirs(OUT, exist_ok=True)


def f(size, bold=False):
    return ImageFont.truetype(FONT_B if bold else FONT, size)


def wallpaper(w, h, colors):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(colors[0][i] + (colors[1][i] - colors[0][i]) * t) for i in range(3))
        d.line([(0, y), (w, y)], fill=c)
    blob = Image.new("RGB", (w, h), (255, 255, 255))
    b = ImageDraw.Draw(blob)
    b.ellipse((-200, -260, 520, 420), fill=(255, 214, 170))
    b.ellipse((520, -220, 1200, 360), fill=(206, 196, 255))
    blob = blob.filter(ImageFilter.GaussianBlur(90))
    return Image.blend(img, blob, 0.55)


LIGHT = dict(
    wall=((255, 246, 238), (236, 240, 255)),
    card=(255, 255, 255, 236), line=(0, 0, 0, 26),
    title=(28, 28, 32), body=(38, 38, 44), faint=(120, 120, 132),
    sel=(0, 0, 0, 16), field=(255, 255, 255), field_line=(0, 0, 0, 40),
    accent=(24, 24, 28), on_accent=(255, 255, 255),
)
DARK = dict(
    wall=((28, 28, 38), (14, 18, 26)),
    card=(28, 28, 34, 240), line=(255, 255, 255, 28),
    title=(240, 240, 246), body=(226, 226, 234), faint=(150, 150, 164),
    sel=(255, 255, 255, 18), field=(38, 38, 46), field_line=(255, 255, 255, 42),
    accent=(236, 236, 242), on_accent=(24, 24, 28),
)

PAGES = ["输出位置", "命名模板", "音频格式", "标签与附属文件", "元数据", "光驱与校验", "间隙", "诊断"]

ROWS = [
    ("switch", "探测 INDEX 00 间隙", "用子通道 Q 往前扫最多 1 秒，检测音轨前的预间隙并写入日志", True),
    ("list", "间隙探测方法", "子通道 Q 看盘上写的索引（EAC 默认）；波形静音看实际有没有声音", "子通道 Q（EAC 默认）"),
    ("list", "间隙处理方式", "决定 INDEX 00 间隙归属哪个音轨文件（EAC 的三种 gap 处理）", "附加到上一轨（EAC 默认）"),
    ("switch", "抓轨前先扫间隙", "整盘开始前一次性算完所有间隙，抓轨时不再临时探测", False),
]


def panel(d, x, y, w, pal, dark):
    """设置面板本体。尺寸对齐 Compose：标题栏 40 + 主体 ≤292 + 底栏 46。"""
    hdr = 40
    body = 292
    foot = 46
    total = hdr + body + foot
    card = Image.new("RGBA", (w, total), (0, 0, 0, 0))
    cd = ImageDraw.Draw(card)
    cd.rounded_rectangle((0, 0, w - 1, total - 1), radius=12, fill=pal["card"], outline=pal["line"], width=1)
    img_rgba = Image.new("RGBA", (w, total), (0, 0, 0, 0))
    # 标题栏
    cd.text((16, 10), "设置", font=f(15), fill=pal["title"])
    t = "收起设置"
    cd.text((w - 16 - cd.textlength(t, font=f(13)), 12), t, font=f(13), fill=pal["faint"])
    cd.line([(0, hdr), (w, hdr)], fill=pal["line"], width=1)

    # 左列导航
    nav_w = 146
    for i, name in enumerate(PAGES):
        ry = hdr + 8 + i * 29
        sel = name == "间隙"
        if sel:
            cd.rounded_rectangle((6, ry, nav_w - 6, ry + 27), radius=7, fill=pal["sel"])
            cd.rounded_rectangle((6, ry + 6, 8, ry + 21), radius=1, fill=pal["accent"])
        cd.text((18, ry + 5), "%d. %s" % (i + 1, name), font=f(13, sel), fill=pal["title"] if sel else pal["faint"])
    cd.line([(nav_w, hdr), (nav_w, hdr + body)], fill=pal["line"], width=1)

    # 右列
    cx = nav_w + 14
    cd.text((cx, hdr + 10), "间隙", font=f(15), fill=pal["title"])
    cd.text((cx + 46, hdr + 13), "4 项", font=f(12), fill=pal["faint"])
    ry = hdr + 36
    for idx, (kind, label, hint, val) in enumerate(ROWS):
        if idx:
            cd.line([(cx, ry - 2), (w - 16, ry - 2)], fill=pal["line"], width=1)
        cd.text((cx, ry + 6), label, font=f(14), fill=pal["body"])
        # 提示：单行、最多两行，11sp
        hint_txt = hint if len(hint) <= 34 else hint[:33] + "…"
        cd.text((cx, ry + 26), hint_txt, font=f(11), fill=pal["faint"])
        if kind == "switch":
            sx = w - 16 - 34
            sy = ry + 16
            on = val
            cd.rounded_rectangle((sx, sy, sx + 34, sy + 18), radius=9,
                                 fill=pal["accent"] if on else (pal["field_line"] if dark else (0, 0, 0, 40)))
            cxx = sx + 25 if on else sx + 9
            cd.ellipse((cxx - 7, sy + 2, cxx + 7, sy + 16), fill=pal["on_accent"] if on else (255, 255, 255))
        else:
            tw = cd.textlength(val, font=f(13))
            bx = w - 16 - 22 - tw - 10
            cd.rounded_rectangle((bx, ry + 10, w - 16, ry + 36), radius=7, fill=pal["sel"])
            cd.text((bx + 8, ry + 15), val, font=f(13), fill=pal["body"])
            cd.text((bx + 8 + tw + 6, ry + 16), "v", font=f(10), fill=pal["faint"])
        ry += 52

    cd.line([(0, hdr + body), (w, hdr + body)], fill=pal["line"], width=1)
    cd.rounded_rectangle((14, hdr + body + 9, 14 + 62, hdr + body + 37), radius=8, fill=pal["accent"])
    cd.text((32, hdr + body + 16), "保存", font=f(14, True), fill=pal["on_accent"])
    cd.text((90, hdr + body + 16), "取消", font=f(14), fill=pal["faint"])

    img_rgba.alpha_composite(card)
    return img_rgba


def frame(pal, dark, note):
    img = wallpaper(W, H, pal["wall"]).convert("RGBA")
    d = ImageDraw.Draw(img)
    d.text((24, 18), note, font=f(14, True), fill=pal["title"])
    img.alpha_composite(panel(d, 24, 52, W - 48, pal, dark), (24, 52))
    return img


def render():
    top = frame(LIGHT, False, "F1 新设置面板（浅色）· 统一 13sp 正文 / 11sp 说明，行高 52，无固定 320dp 空白")
    bottom = frame(DARK, True, "F1 新设置面板（深色）· 同一套尺寸，只换色板")
    gap = 8
    out = Image.new("RGB", (W, H * 2 + gap), (250, 250, 250))
    out.paste(top.convert("RGB"), (0, 0))
    out.paste(bottom.convert("RGB"), (0, H + gap))
    path = os.path.join(OUT, "F1-设置面板-双主题.png")
    out.save(path)
    print("wrote", path)


if __name__ == "__main__":
    render()
