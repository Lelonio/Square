"""Draws docs/social-preview.png: the name across the top, four phones below.

The phones are full-resolution captures (adb exec-out screencap -p) in
docs/screenshots, card-*.png, in the order of SHOTS. Each is cut below the
status bar, given a screen's rounded corners and a soft shadow, and runs off
the bottom of the card. Replace a file and run this again to change it.

The wordmark is not typeset: it is the same drawing the app puts in its own
header (`ui/components/Wordmark.kt`) — polylines on a 6x10 grid, orthogonal
segments only, square caps and miter joins. Kept as a port rather than a font
so the two cannot drift.
"""

from PIL import Image, ImageDraw, ImageFont

BG = (16, 16, 18)
INK = (247, 248, 250)   # theme Ink
SUB = (150, 150, 158)
FOOT = (110, 110, 118)

OUT = "docs/social-preview.png"


HELV = "/System/Library/Fonts/HelveticaNeue.ttc"

# S Q U A R E, straight from GLYPHS in Wordmark.kt.
GLYPHS = [
    [[(6, 0), (0, 0), (0, 5), (6, 5), (6, 10), (0, 10)]],
    [[(0, 0), (6, 0), (6, 10), (0, 10), (0, 0)], [(4, 8), (4, 12)]],
    [[(0, 0), (0, 10), (6, 10), (6, 0)]],
    [[(0, 10), (0, 0), (6, 0), (6, 10)], [(0, 6), (6, 6)]],
    [[(0, 10), (0, 0), (6, 0), (6, 5), (0, 5)], [(3, 5), (3, 10)]],
    [[(6, 0), (0, 0), (0, 10), (6, 10)], [(0, 5), (4, 5)]],
]
ADVANCE = 9.5   # units between glyph origins
BOX = 12.0      # units of the wordmark's own height


def draw_wordmark(im, x, y, height, color, scale=4):
    """Draws the wordmark with its top-left at (x, y), `height` units tall.

    Every segment is axis-aligned, so a segment is drawn as its bounding box
    grown by half a stroke on all sides: that gives the square caps at the ends
    of a polyline and fills the right-angle miter at every join, which is what
    Compose's Stroke(cap=Square, join=Miter) produces.
    """
    unit = height / BOX * scale
    stroke = unit * 1.15
    half = stroke / 2

    w = int(ADVANCE * len(GLYPHS) * unit + stroke)
    h = int(BOX * unit + stroke)
    layer = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(layer)

    for i, glyph in enumerate(GLYPHS):
        origin = i * ADVANCE * unit + half
        for line in glyph:
            for (ax, ay), (bx, by) in zip(line, line[1:]):
                x0, x1 = sorted((origin + ax * unit, origin + bx * unit))
                y0, y1 = sorted((ay * unit + half, by * unit + half))
                d.rectangle([x0 - half, y0 - half, x1 + half, y1 + half], fill=255)

    mask = layer.resize((w // scale, h // scale), Image.LANCZOS)
    im.paste(Image.new("RGB", mask.size, color), (int(x), int(y)), mask)

from PIL import ImageFilter

W, H = 1280, 640

# Left to right.
SHOTS = [
    "docs/screenshots/card-album.png",
    "docs/screenshots/card-player.png",
    "docs/screenshots/card-artist.png",
    "docs/screenshots/card-lyrics.png",
]
SHOT_Y = 222      # where the phones' tops sit
SHOT_MARGIN = 64  # card edge to the outer phones
SHOT_GAP = 34
SHOT_TOP = 120    # px of a 2392-tall capture cut off the top: the status bar
SHOT_RADIUS = 0.08  # corner radius, as a share of the width

im = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(im)

# The name, centred: the icon, then the wordmark beside it, and the line under.
icon_size = 84
mark_height = 44 / 10 * BOX
mark_width = ADVANCE * len(GLYPHS) * (mark_height / BOX)
gap = 28
row = icon_size + gap + mark_width
x0 = (W - row) / 2
icon = Image.open("docs/square.png").convert("RGBA").resize((icon_size, icon_size), Image.LANCZOS)
im.paste(icon, (round(x0), 40), icon)
draw_wordmark(im, x0 + icon_size + gap, 40 + (icon_size - 44 * 1.2) / 2, height=mark_height, color=INK)

f_sub = ImageFont.truetype(HELV, 27, index=0)
line = "Spotify and YouTube Music on Android, in Liquid Glass."
d.text(((W - d.textlength(line, font=f_sub)) / 2, 152), line, font=f_sub, fill=SUB)

width = round((W - 2 * SHOT_MARGIN - 3 * SHOT_GAP) / len(SHOTS))
radius = round(width * SHOT_RADIUS)
for i, path in enumerate(SHOTS):
    left = SHOT_MARGIN + i * (width + SHOT_GAP)
    shot = Image.open(path).convert("RGB")
    shot = shot.crop((0, round(SHOT_TOP * shot.height / 2392), shot.width, shot.height))
    shot = shot.resize((width, round(shot.height * width / shot.width)), Image.LANCZOS)
    shot = shot.crop((0, 0, width, H - SHOT_Y))
    # Every phone runs off the bottom, so only its top corners are round.
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, width - 1, shot.height + radius], radius=radius, fill=255)
    shadow = Image.new("L", im.size, 0)
    ImageDraw.Draw(shadow).rounded_rectangle([left, SHOT_Y + 10, left + width, H + radius], radius=radius, fill=150)
    shadow = shadow.filter(ImageFilter.GaussianBlur(16))
    im.paste(Image.new("RGB", im.size, (0, 0, 0)), (0, 0), shadow)
    im.paste(shot, (left, SHOT_Y), mask)

im.save(OUT)
print("wrote", OUT, im.size)
