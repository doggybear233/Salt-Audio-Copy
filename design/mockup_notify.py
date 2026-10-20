# -*- coding: utf-8 -*-
"""F2：提示（toast）重新设计——现状 + 3 个方案。"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

FONT = "C:/Windows/Fonts/msyh.ttc"
FONT_B = "C:/Windows/Fonts/msyhbd.ttc"
W = 1500
ROW_H = 300
OUT = os.path.join(os.path.dirname(__file__), "mockups")
os.makedirs(OUT, exist_ok=True)


def f(size, bold=False):
    return ImageFont.truetype(FONT_B if bold else FONT, size)


def bg(w, h):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        c = (int(248 + 6 * t), int(246 - 10 * t), int(244 + 14 * t))
        d.line([(0, y), (w, y)], fill=c)
    blob = Image.new("RGB", (w, h), (255, 255, 255))
    b = ImageDraw.Draw(blob)
    b.ellipse((-160, -140, 520, 380), fill=(255, 220, 180))
    b.ellipse((560, -120, 1180, 320), fill=(214, 204, 255))
    blob = blob.filter(ImageFilter.GaussianBlur(80))
    return Image.blend(img, blob, 0.5)


def dock(d, x, y, w, h=92, disabled=False):
    """底部坞：封面 + 专辑 + 进度 + 按钮。"""
    d.rounded_rectangle((x, y, x + w, y + h), radius=18, fill=(255, 255, 255), outline=(0, 0, 0, 20))
    d.rounded_rectangle((x + 18, y + 18, x + 18 + 54, y + 18 + 54), radius=8, fill=(196, 158, 42))
    d.text((x + 88, y + 18), "Def Tech — GREATEST HITS", font=f(16, True), fill=(26, 26, 30))
    d.text((x + 88, y + 40), "20 轨 · 1:18:54 · CD-DA", font=f(12), fill=(120, 120, 132))
    d.rounded_rectangle((x + 88, y + 62, x + 88 + 300, y + 68), radius=3, fill=(232, 226, 242))
    d.rounded_rectangle((x + 88, y + 62, x + 88 + 186, y + 68), radius=3, fill=(96, 78, 200))
    d.text((x + 400, y + 56), "第 7/20 轨 · 62%", font=f(12, True), fill=(40, 40, 46))
    bx = x + w - 300
    for label, filled in (("抓整盘", True), ("暂停", False), ("取消", False), ("设置", False)):
        tw = d.textlength(label, font=f(12, True)) + 24
        if filled:
            d.rounded_rectangle((bx, y + 34, bx + tw, y + 60), radius=13, fill=(96, 78, 200))
            d.text((bx + 12, y + 40), label, font=f(12, True), fill=(255, 255, 255))
        else:
            d.rounded_rectangle((bx, y + 34, bx + tw, y + 60), radius=13, fill=(255, 255, 255), outline=(0, 0, 0, 26))
            d.text((bx + 12, y + 40), label, font=f(12, True), fill=(60, 60, 68))
        bx += tw + 8


def mark(d, box, label, color=(214, 60, 60)):
    x0, y0, x1, y1 = box
    for i in range(0, int(x1 - x0), 12):
        d.line([(x0 + i, y0), (min(x0 + i + 6, x1), y0)], fill=color, width=2)
        d.line([(x0 + i, y1), (min(x0 + i + 6, x1), y1)], fill=color, width=2)
    for i in range(0, int(y1 - y0), 12):
        d.line([(x0, y0 + i), (x0, min(y0 + i + 6, y1))], fill=color, width=2)
        d.line([(x1, y0 + i), (x1, min(y0 + i + 6, y1))], fill=color, width=2)
    d.text((x0 + 6, y0 + 4), label, font=f(11, True), fill=color)


def block(title, sub, scene, badge=None):
    img = bg(W, ROW_H).convert("RGBA")
    d = ImageDraw.Draw(img)
    d.text((26, 14), title, font=f(17, True), fill=(24, 24, 28))
    d.text((26, 40), sub, font=f(12), fill=(110, 110, 124))
    if badge:
        tw = d.textlength(badge, font=f(11, True)) + 16
        d.rounded_rectangle((W - 26 - tw, 14, W - 26, 34), radius=10, fill=(24, 24, 28))
        d.text((W - 26 - tw + 8, 18), badge, font=f(11, True), fill=(255, 255, 255))
    scene(img, d)
    return img


def scene_current(img, d):
    """现状：宿主 toast 全宽贴底，正好压在坞上。"""
    dock(d, 320, 120, 860)
    d.rectangle((0, 196, W, 230), fill=(226, 244, 226))
    d.text((118, 204), "√  继续抓轨 · 第 12 轨 34% · 1.3x · 剩余 1 分 12 秒", font=f(12), fill=(30, 76, 40))
    mark(d, (0, 196, W, 230), "")
    d.text((8, 172), "覆盖坞的下半", font=f(11, True), fill=(214, 60, 60))
    d.text((320, 268), "宿主 WorkshopApi.ui.toast() 固定贴底、整宽，插件无法改位置", font=f(11), fill=(130, 130, 142))


def scene_inline(img, d):
    dock(d, 320, 120, 860)
    x, y, w = 320, 120, 860
    d.rounded_rectangle((x, y - 34, x + w, y - 6), radius=8, fill=(238, 246, 238), outline=(0, 0, 0, 16))
    d.text((x + 12, y - 29), "√  继续抓轨 · 第 12 轨 34%", font=f(12), fill=(30, 76, 40))
    d.text((x + w - 72, y - 29), "4 秒后收起", font=f(11), fill=(120, 130, 120))
    d.text((x, y + 102), "贴在坞上沿、与会话同宽，不压任何按钮；坞收起时消息进状态行", font=f(11), fill=(130, 130, 142))


def scene_bubble(img, d):
    dock(d, 320, 120, 860)
    bx, by = 320 + 860 - 300, 60
    d.rounded_rectangle((bx, by, bx + 300, by + 44), radius=10, fill=(255, 255, 255), outline=(0, 0, 0, 30))
    d.text((bx + 12, by + 8), "已导出 album.json", font=f(12, True), fill=(30, 30, 34))
    d.text((bx + 12, by + 25), "改完保存，再抓整盘即生效", font=f(11), fill=(120, 120, 132))
    d.text((bx + 300 - 18, by + 8), "×", font=f(12), fill=(150, 150, 160))
    d.text((320, 268), "气泡挂右上方、3 秒淡出，可点 × 立刻关；多条时向上堆叠", font=f(11), fill=(130, 130, 142))


def scene_offpanel(img, d):
    dock(d, 320, 120, 860)
    x, y = 26, 70
    d.rounded_rectangle((x, y, x + 270, y + 52), radius=10, fill=(255, 255, 255), outline=(0, 0, 0, 26))
    d.text((x + 12, y + 9), "√ 扫描完成", font=f(12, True), fill=(30, 30, 34))
    d.text((x + 12, y + 27), "HL-DT-ST BD-RE BP71N · 20 轨", font=f(11), fill=(120, 120, 132))
    d.text((320, 268), "通知挪到页面左上角，离坞最远；适合「离开面板也要看到」的完成/失败", font=f(11), fill=(130, 130, 142))


def render():
    rows = [
        block("现状 · 宿主 toast", "WorkshopApi.ui.toast() 贴底整宽 —— 正好盖住坞和播放条",
              scene_current, "问题"),
        block("方案 A · 坞上沿状态条", "通知放在坞的紧上沿，与会话同宽，4 秒后自动收起",
              scene_inline, "推荐"),
        block("方案 B · 右上方气泡", "浮在坞右上角外，可手动关；多条向上堆叠",
              scene_bubble, "备选"),
        block("方案 C · 页面左上角通知", "离坞最远，完成/失败这类「不看着也要知道」的消息",
              scene_offpanel, "备选"),
    ]
    h = sum(r.height for r in rows) + 8 * (len(rows) - 1)
    out = Image.new("RGB", (W, h), (250, 250, 250))
    y = 0
    for r in rows:
        out.paste(r.convert("RGB"), (0, y))
        y += r.height + 8
    path = os.path.join(OUT, "F2-提示方案-三选.png")
    out.save(path)
    print("wrote", path)


if __name__ == "__main__":
    render()
