# Builds the "get tracked" black & white app icons for every Android screen density.
import os
from PIL import Image, ImageDraw, ImageFont

def font(size):
    for p in ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
              "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"]:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default(size)

def art(size, shape, safe):
    """shape: 'round' | 'square' (black icon) | 'fg' (white art on transparent, for adaptive icons)"""
    S = size * 4                                   # draw big, then shrink for smooth edges
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if shape == "round":
        d.ellipse([0, 0, S - 1, S - 1], fill="black")
    elif shape == "square":
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill="black")
    w = S * safe
    cx, cy = S / 2, S / 2
    s = 10                                         # largest font where "tracked" fits the safe width
    while font(s + 2).getlength("tracked") < w * 0.90:
        s += 2
    f = font(s)
    asc, desc = f.getmetrics()
    lh = asc + desc
    gap = lh * 0.02
    top = cy - (lh * 2 + gap) / 2
    for i, word in enumerate(("get", "tracked")):
        tw = f.getlength(word)
        d.text((cx - tw / 2, top + i * (lh + gap)), word, font=f, fill="white")
    bar_y = top + 2 * lh + gap + lh * 0.20         # thin underline bar
    d.rounded_rectangle([cx - w * 0.17, bar_y, cx + w * 0.17, bar_y + max(4, lh * 0.07)], radius=6, fill="white")
    return im.resize((size, size), Image.LANCZOS)

res = "app/src/main/res"
legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
adapt = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
for dens in legacy:
    d = f"{res}/mipmap-{dens}"
    os.makedirs(d, exist_ok=True)
    art(legacy[dens], "square", 0.80).save(f"{d}/ic_launcher.png")
    art(legacy[dens], "round", 0.68).save(f"{d}/ic_launcher_round.png")
    art(adapt[dens], "fg", 0.60).save(f"{d}/ic_launcher_foreground.png")
os.makedirs(f"{res}/mipmap-anydpi-v26", exist_ok=True)
xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/black"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
'''
for n in ("ic_launcher", "ic_launcher_round"):
    open(f"{res}/mipmap-anydpi-v26/{n}.xml", "w").write(xml)
print("icons created")
