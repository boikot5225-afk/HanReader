#!/usr/bin/env python3
"""Launcher icon (mipmap-*/ic_launcher_foreground.png) and the 512 px store icon (store/icon-512.png).
读 «read» with its pinyin dú above, like a word in the reader; Noto Serif SC and Inter (SIL OFL 1.1).
Usage: python3 tools/build_icon.py   (from the repository root)"""
import os
from PIL import Image, ImageDraw, ImageFont

BG = (16, 40, 70, 255)        # @color/launcher_background #102846
INK = (247, 241, 227, 255)
GOLD = (231, 173, 78, 255)


def render(s):
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    han = ImageFont.truetype("app/src/main/res/font/noto_serif_sc.ttf", int(s * 0.34))
    pin = ImageFont.truetype("app/src/main/res/font/inter.ttf", int(s * 0.085))
    cx = s / 2
    d.text((cx, s * 0.33), "dú", font=pin, fill=GOLD, anchor="mm")
    d.text((cx, s * 0.55), "读", font=han, fill=INK, anchor="mm")
    w = s * 0.20
    y = s * 0.745
    d.rounded_rectangle((cx - w / 2, y, cx + w / 2, y + s * 0.012), radius=s * 0.006, fill=GOLD)
    return im


def main():
    for name, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
        os.makedirs(f"app/src/main/res/mipmap-{name}", exist_ok=True)
        render(px * 4).resize((px, px), Image.LANCZOS).save(f"app/src/main/res/mipmap-{name}/ic_launcher_foreground.png")
    # Store icon: the 72 dp visible part of the 108 dp adaptive icon, on its background.
    big = render(2048 * 108 // 72)
    off = (big.size[0] - 2048) // 2
    store = Image.new("RGBA", (2048, 2048), BG)
    store.alpha_composite(big.crop((off, off, off + 2048, off + 2048)))
    os.makedirs("store", exist_ok=True)
    store.resize((512, 512), Image.LANCZOS).convert("RGB").save("store/icon-512.png")


if __name__ == "__main__":
    main()
