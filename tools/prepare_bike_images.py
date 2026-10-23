#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""整理车型插画：抹掉生成水印 → 统一尺寸 → 输出到 assets/bikes/。

用法：
    python tools/prepare_bike_images.py <源目录>

约定（应用内按以下文件名读取，替换图片时保持同名即可）：
    sr_max.png   Aprilia SR Max 系列（运动大踏板）
    gpr.png      Aprilia GPR 系列（仿赛）
    vespa.png    Vespa 系列（复古踏板）
    beverly.png  Piaggio 大轮踏板系列

要求：横版、深色背景、主体居中。应用侧用径向渐变压暗四周，与卡片背景自然融合。
"""
import os
import sys

from PIL import Image, ImageFilter

# 源文件名关键字 → 目标文件名
MAPPING = [
    ("32-49", "sr_max.png"),
    ("33-12", "gpr.png"),
    ("33-32", "vespa.png"),
    ("33-49", "beverly.png"),
]

MAX_W = 1440


def find_watermark(img: Image.Image):
    """在右下角区域寻找浅色水印的包围盒。"""
    w, h = img.size
    x0, y0 = int(w * 0.85), int(h * 0.85)
    px = img.convert("RGB").load()
    minx, miny, maxx, maxy = w, h, 0, 0
    for y in range(y0, h):
        for x in range(x0, w):
            r, g, b = px[x, y]
            # 浅灰白、低饱和（水印在深色背景上约 120~200）
            if r > 112 and g > 112 and b > 112 and max(r, g, b) - min(r, g, b) < 26:
                if x < minx:
                    minx = x
                if y < miny:
                    miny = y
                if x > maxx:
                    maxx = x
                if y > maxy:
                    maxy = y
    if maxx <= minx or maxy <= miny:
        return None
    return minx, miny, maxx, maxy


def erase_watermark(img: Image.Image):
    """用左右边缘颜色做水平线性插值填补水印区域，边缘羽化消除接缝。"""
    box = find_watermark(img)
    if box is None:
        return img
    w, h = img.size
    x1, y1, x2, y2 = box
    m = 6
    x1 = max(0, x1 - m)
    y1 = max(0, y1 - m)
    x2 = min(w, x2 + m + 1)
    y2 = min(h, y2 + m + 1)

    src = img.load()
    bw, bh = x2 - x1, y2 - y1
    pad = 3
    # 左右参考列（越界时退化为单侧常量填充）
    has_l = x1 - pad - 1 >= 0
    has_r = x2 + pad < w
    for y in range(y1, y2):
        cl = src[x1 - pad - 1, y] if has_l else None
        cr = src[x2 + pad, y] if has_r else None
        if cl is None and cr is None:
            continue
        for x in range(x1, x2):
            if cl is not None and cr is not None:
                t = (x - x1) / max(1, bw - 1)
                c = tuple(round(cl[i] + (cr[i] - cl[i]) * t) for i in range(3))
            else:
                c = cl if cl is not None else cr
            src[x, y] = c

    # 羽化：只对区域外扩的一圈做模糊，避免硬边
    region = img.crop((max(0, x1 - 4), max(0, y1 - 4), min(w, x2 + 4), min(h, y2 + 4)))
    img.paste(region.filter(ImageFilter.GaussianBlur(2)), (max(0, x1 - 4), max(0, y1 - 4)))
    return img


def main():
    src_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    out_dir = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/bikes"
    os.makedirs(out_dir, exist_ok=True)

    files = sorted(f for f in os.listdir(src_dir) if f.lower().endswith(".png"))
    for key, target in MAPPING:
        hit = next((f for f in files if key in f), None)
        if not hit:
            print(f"! 未找到 {key} 对应的源图，跳过 {target}")
            continue
        img = Image.open(os.path.join(src_dir, hit)).convert("RGB")
        img = erase_watermark(img)
        if img.width > MAX_W:
            img = img.resize((MAX_W, round(img.height * MAX_W / img.width)), Image.LANCZOS)
        out = os.path.join(out_dir, target)
        img.save(out, "PNG", optimize=True)
        print(f"✓ {hit} → {target}  {img.size[0]}x{img.size[1]}  {os.path.getsize(out)//1024} KB")


if __name__ == "__main__":
    main()
