#!/usr/bin/env python3
"""Launcher icon of the Spanish app (mipmap-*/ic_launcher_foreground.png) and its 512 px store icon
(store/es/icon-512.png), from the picture store/es/icon-source.png.

The picture is a square with rounded black corners: the corners are cut off, and the rest fills the
whole adaptive icon (the launcher draws it in its own shape, a circle or a rounded square).

Usage: python3 tools/build_es_icon.py   (from the repository root)"""
from PIL import Image

SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
# Beyond the rounded black corners of the source picture (1254 px: corners up to 97 px in).
INSET = 100 / 1254
# A launcher shows the middle 72 of the icon's 108 dp, in its own shape: the picture fills a bit more
# than that, so a rounded square shows all of it and a circle cuts only its corners.
FILL = 76 / 108


def main():
    src = Image.open("store/es/icon-source.png").convert("RGB")
    w = src.size[0]
    d = round(w * INSET)
    art = src.crop((d, d, w - d, w - d))
    for name, px in SIZES.items():
        layer = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        side = round(px * FILL)
        layer.paste(art.resize((side, side), Image.LANCZOS), ((px - side) // 2, (px - side) // 2))
        layer.save(f"app/src/main/res/mipmap-{name}/ic_launcher_foreground.png")
    art.resize((512, 512), Image.LANCZOS).save("store/es/icon-512.png")


if __name__ == "__main__":
    main()
