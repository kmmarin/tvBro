"""Render the TV banner (app/src/main/res/drawable/banner.xml, a vector) to PNG for launchers that can't draw
vector banners. 2026-11-20: the Fire TV home row (Fire OS 7.1, API 25) showed a broken-image tile for tvBro.
Writes drawable-xhdpi/banner.png (640x360) and drawable-mdpi/banner.png (320x180); the vector moves to
drawable-anydpi-v26 so newer Android TV keeps using it.  usage: python tools/render_banner.py"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")


def render(scale):
    ss = 4                                   # supersample, then shrink for smooth edges
    k = scale * ss
    im = Image.new("RGBA", (320 * k, 180 * k), "#0E1116")
    d = ImageDraw.Draw(im)
    d.rectangle([40 * k, 40 * k, 280 * k - 1, 140 * k - 1], fill="#171C24")
    d.polygon([(135 * k, 65 * k), (195 * k, 90 * k), (135 * k, 115 * k)], fill="#4FC3F7")
    # arc M100,62 A40,40 to 100,118: circle centre (~127.7, 90), r 40, left side
    for cx, r, col in ((127.7, 40, (79, 195, 247, 255)), (139.4, 62, (79, 195, 247, 128))):
        layer = Image.new("RGBA", im.size, (0, 0, 0, 0))
        ImageDraw.Draw(layer).arc([(cx - r) * k, (90 - r) * k, (cx + r) * k, (90 + r) * k], 135, 225,
                                  fill=col, width=6 * k)
        im = Image.alpha_composite(im, layer)
        d = ImageDraw.Draw(im)
    d.rectangle([210 * k, 76 * k, 260 * k - 1, 84 * k - 1], fill="#E6EAF0")
    d.rectangle([210 * k, 96 * k, 246 * k - 1, 104 * k - 1], fill="#E6EAF0")
    return im.resize((320 * scale, 180 * scale), Image.LANCZOS).convert("RGB")


def render_icon(scale):
    """drawable/ic_launcher.xml (96x96 vector). The Fire TV home row shows a sideloaded app's ICON (TiviMate's
    tile is its icon on grey), so the icon needs PNGs too."""
    import math
    ss = 4
    k = scale * ss
    im = Image.new("RGBA", (96 * k, 96 * k), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle([0, 0, 96 * k - 1, 96 * k - 1], radius=12 * k, fill="#0E1116")
    ImageDraw.Draw(im).polygon([(36 * k, 30 * k), (66 * k, 48 * k), (36 * k, 66 * k)], fill="#4FC3F7")
    # M18,28 A36 to 18,68 and M8,20 A48 to 8,76 (sweep 1: centre to the left, arc bulges right)
    for x0, half, r, col in ((18, 20, 36, (79, 195, 247, 255)), (8, 28, 48, (79, 195, 247, 128))):
        cx = x0 - math.sqrt(r * r - half * half)
        a = math.degrees(math.asin(half / r))
        layer = Image.new("RGBA", im.size, (0, 0, 0, 0))
        ImageDraw.Draw(layer).arc([(cx - r) * k, (48 - r) * k, (cx + r) * k, (48 + r) * k], -a, a, fill=col, width=4 * k)
        im = Image.alpha_composite(im, layer)
    return im.resize((96 * scale, 96 * scale), Image.LANCZOS)


for folder, scale in (("drawable-mdpi", 1), ("drawable-xhdpi", 2), ("drawable-xxhdpi", 3)):
    os.makedirs(os.path.join(RES, folder), exist_ok=True)
    if scale < 3:
        render(scale).save(os.path.join(RES, folder, "banner.png"), optimize=True)
    render_icon(scale).save(os.path.join(RES, folder, "ic_launcher.png"), optimize=True)
    print("wrote", folder)
