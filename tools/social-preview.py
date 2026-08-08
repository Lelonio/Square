"""Repaints the left panel of docs/social-preview.png with new copy.

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

SRC = "docs/social-preview.png"
OUT = "docs/social-preview.png"
PANEL = 833  # where the phone screenshot starts

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


im = Image.open(SRC).convert("RGB")
d = ImageDraw.Draw(im)
d.rectangle([0, 0, PANEL - 1, im.height], fill=BG)

# The icon, same place and size as before.
icon = Image.open("docs/square.png").convert("RGBA").resize((130, 130), Image.LANCZOS)
im.paste(icon, (85, 97), icon)

# Cap height 54, matching the Helvetica wordmark this replaces.
draw_wordmark(im, 85, 272, height=54 / 10 * BOX, color=INK)

f_sub = ImageFont.truetype(HELV, 31, index=0)
f_foot = ImageFont.truetype(HELV, 23, index=0)

d.text((85, 372), "Spotify and YouTube Music", font=f_sub, fill=SUB)
d.text((85, 416), "on Android, in Liquid Glass.", font=f_sub, fill=SUB)

x = 85
for i, part in enumerate(["Two sources", "Audio effects", "Video mode"]):
    if i:
        d.text((x, 500), "·", font=f_foot, fill=FOOT)
        x += d.textlength("·", font=f_foot) + 22
    d.text((x, 500), part, font=f_foot, fill=FOOT)
    x += d.textlength(part, font=f_foot) + 22

im.save(OUT)
print("wrote", OUT, im.size)
