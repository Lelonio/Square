"""Turns app/icon-src/{light,dark}.png into every icon resource the app ships.

Run it after changing either source:

    python3 tools/icon.py

The one thing this does that a plain resize would not is size the artwork by
*the wave*, not by the image. An adaptive icon is 108 units wide, of which the
launcher shows 72 and guarantees only the middle 66; the sources fill their
canvas to different extents, so scaling them alike would leave one cropped and
the other adrift. The wave's bounding box is measured and scaled to the safe
zone, and the rest of the canvas is filled with the plate's own colour, so
whatever mask the launcher applies cuts into flat colour rather than into the
mark.
"""

from PIL import Image
import os

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

FOREGROUND_DP = 108
SAFE_DP = 66
LEGACY_DP = 48

RES = "app/src/main/res"


def plate_colour(img):
    """The plate's colour, read where the mark never reaches.

    The corner is tried first and is right for a source that fills its canvas.
    One drawn as a rounded plate on transparency has nothing there, so the
    fallback reads just inside the top edge instead.
    """
    corner = img.getpixel((4, 4))
    if corner[3] >= 8:
        return corner
    return img.getpixel((img.width // 2, round(img.height * 0.08)))


def wave_bounds(img, plate):
    """The mark's bounding box: everything that is not the plate colour."""
    pixels = img.load()
    tolerance = 30
    left, top, right, bottom = img.width, img.height, 0, 0
    for y in range(0, img.height, 2):
        for x in range(0, img.width, 2):
            r, g, b, a = pixels[x, y]
            if a < 8:
                continue
            if abs(r - plate[0]) + abs(g - plate[1]) + abs(b - plate[2]) < tolerance:
                continue
            left, top = min(left, x), min(top, y)
            right, bottom = max(right, x), max(bottom, y)
    return left, top, right, bottom


def build(variant, suffix):
    src = Image.open(f"app/icon-src/{variant}.png").convert("RGBA")
    plate = plate_colour(src)
    left, top, right, bottom = wave_bounds(src, plate)
    mark = max(right - left, bottom - top)

    for density, density_scale in DENSITIES.items():
        canvas_px = round(FOREGROUND_DP * density_scale)
        safe_px = round(SAFE_DP * density_scale)

        # Scale the whole image by what the *mark* needs, then centre the image
        # on the mark rather than on itself: the sources are not all centred.
        scale = safe_px / mark
        art = src.resize(
            (max(1, round(src.width * scale)), max(1, round(src.height * scale))),
            Image.LANCZOS,
        )
        mark_centre = ((left + right) / 2 * scale, (top + bottom) / 2 * scale)

        canvas = Image.new("RGBA", (canvas_px, canvas_px), plate)
        canvas.paste(
            art,
            (round(canvas_px / 2 - mark_centre[0]), round(canvas_px / 2 - mark_centre[1])),
            art,
        )

        out = f"{RES}/mipmap{suffix}-{density}"
        os.makedirs(out, exist_ok=True)
        canvas.save(f"{out}/ic_launcher_foreground.png")

        # The whole icon, for launchers older than adaptive icons.
        legacy_px = round(LEGACY_DP * density_scale)
        legacy = src.resize((legacy_px, legacy_px), Image.LANCZOS)
        legacy.save(f"{out}/ic_launcher.png")
        legacy.save(f"{out}/ic_launcher_round.png")

    return plate


def colours_xml(colour):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        "    <!-- Sampled from the icon's own plate by tools/icon.py, so the\n"
        "         adaptive icon's background and its artwork meet invisibly. -->\n"
        f'    <color name="ic_launcher_background">#{colour[0]:02X}{colour[1]:02X}{colour[2]:02X}</color>\n'
        "</resources>\n"
    )


light = build("light", "")
dark = build("dark", "-night")

open(f"{RES}/values/colors.xml", "w").write(colours_xml(light))
os.makedirs(f"{RES}/values-night", exist_ok=True)
open(f"{RES}/values-night/colors.xml", "w").write(colours_xml(dark))

print("light plate", light, "dark plate", dark)
