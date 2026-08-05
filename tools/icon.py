"""Turns the icon source into every icon resource the app ships.

Run it after changing the artwork:

    python3 tools/icon.py

Source: app/icon-src/icona.png. Light and dark launchers get the same icon —
it is a dark plate with a bright mark, which is legible either way, and two
variants of one design would only drift apart.

Two things here are not a plain resize.

The artwork is sized by *the mark*, not by the image. An adaptive icon is 108
units wide, of which the launcher shows 72 and guarantees only the middle 66;
sizing by the image left the wave's ends outside that window and the launcher
cut them off.

And the plate's own colour fills the rest of the canvas — sampled from the
artwork, and also written into colors.xml as the adaptive background. Whatever
mask the launcher applies then cuts into flat colour instead of into the mark.
"""

from PIL import Image
import os

SOURCE = "app/icon-src/icona.png"
RES = "app/src/main/res"

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

FOREGROUND_DP = 108
SAFE_DP = 66
LEGACY_DP = 48


def opaque_only(img):
    """Drops the source's own backdrop, keeping the plate.

    The artwork is a plate over a half-transparent grey field — a checkerboard
    stand-in that reads as nothing on its own and as a grey square the moment it
    is composited onto anything. Everything below full opacity goes; the edge is
    then softened by a pixel so the plate keeps its antialiasing.
    """
    from PIL import ImageFilter

    alpha = img.getchannel("A").point(lambda value: 255 if value > 200 else 0)
    alpha = alpha.filter(ImageFilter.GaussianBlur(0.8))
    out = img.copy()
    out.putalpha(alpha)
    return out


def plate_colour(img):
    """The plate's colour where it meets the background.

    Read around a ring just inside the plate's edge, and taken as a median.
    Not a corner — this artwork is a circle on transparency, so the corners are
    empty — and not a single point either: the plate is shaded, so one sample
    picks up whatever the gradient happens to be doing there and the seam shows.
    The edge is what the adaptive background butts against, so the edge is what
    it should match.
    """
    import math

    radius = min(img.width, img.height) * 0.46
    centre = (img.width / 2, img.height / 2)
    samples = []
    for step in range(24):
        angle = step / 24 * 2 * math.pi
        x = round(centre[0] + radius * math.cos(angle))
        y = round(centre[1] + radius * math.sin(angle))
        r, g, b, a = img.getpixel((x, y))
        if a > 200:
            samples.append((r, g, b))

    channels = [sorted(channel)[len(channel) // 2] for channel in zip(*samples)]
    return (channels[0], channels[1], channels[2], 255)


def mark_bounds(img, plate):
    """The wave's bounding box: what differs from the plate and is opaque."""
    pixels = img.load()
    tolerance = 40
    left, top, right, bottom = img.width, img.height, 0, 0
    for y in range(0, img.height, 2):
        for x in range(0, img.width, 2):
            r, g, b, a = pixels[x, y]
            if a < 200:
                continue
            if abs(r - plate[0]) + abs(g - plate[1]) + abs(b - plate[2]) < tolerance:
                continue
            left, top = min(left, x), min(top, y)
            right, bottom = max(right, x), max(bottom, y)
    return left, top, right, bottom


def build(suffix):
    src = opaque_only(Image.open(SOURCE).convert("RGBA"))
    plate = plate_colour(src)
    left, top, right, bottom = mark_bounds(src, plate)
    mark = max(right - left, bottom - top)

    for density, density_scale in DENSITIES.items():
        canvas_px = round(FOREGROUND_DP * density_scale)
        safe_px = round(SAFE_DP * density_scale)

        scale = safe_px / mark
        art = src.resize(
            (max(1, round(src.width * scale)), max(1, round(src.height * scale))),
            Image.LANCZOS,
        )
        # Centred on the mark rather than on the image: the two need not agree.
        centre = ((left + right) / 2 * scale, (top + bottom) / 2 * scale)

        canvas = Image.new("RGBA", (canvas_px, canvas_px), plate)
        canvas.paste(
            art,
            (round(canvas_px / 2 - centre[0]), round(canvas_px / 2 - centre[1])),
            art,
        )

        out = f"{RES}/mipmap{suffix}-{density}"
        os.makedirs(out, exist_ok=True)
        canvas.save(f"{out}/ic_launcher_foreground.png")

        # The whole icon, for launchers older than adaptive icons. Composited
        # onto the plate: those do not mask, so transparency would show the
        # home screen through the artwork's edges.
        legacy_px = round(LEGACY_DP * density_scale)
        legacy = Image.new("RGBA", (legacy_px, legacy_px), plate)
        scaled = src.resize((legacy_px, legacy_px), Image.LANCZOS)
        legacy.paste(scaled, (0, 0), scaled)
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


plate = build("")
open(f"{RES}/values/colors.xml", "w").write(colours_xml(plate))
print("plate", plate)
