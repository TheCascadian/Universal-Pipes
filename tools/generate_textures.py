"""Generates the textures for Universal Pipes.

Each tier has its own pipe texture and upgrade texture, distinguished by hue
and by a pattern that survives dye tinting: stripe rows count the tier on the
arms, and the upgrade chip carries one pip fewer than its tier number. Run from
the repository root:

    python tools/generate_textures.py
"""
from pathlib import Path

from PIL import Image

SIZE = 16
ASSETS = Path("src/main/resources/assets/universal_pipes/textures")

TIER_RAMPS = [
    [(206, 206, 206), (176, 176, 176), (146, 146, 146), (100, 100, 100)],
    [(240, 170, 118), (214, 128, 82), (178, 96, 60), (118, 62, 44)],
    [(255, 236, 130), (240, 200, 60), (204, 156, 32), (140, 100, 28)],
    [(122, 104, 110), (90, 74, 80), (66, 52, 58), (40, 30, 36)],
    [(214, 170, 255), (176, 120, 236), (134, 84, 196), (88, 48, 140)],
]
TIER_ACCENTS = [(244, 244, 244), (255, 222, 170), (255, 255, 214), (230, 110, 70), (255, 255, 255)]
ARM_BANDS = (range(0, 5), range(11, 16))
STRIPE_ROWS = {1: (2,), 2: (1, 3), 3: (0, 2, 4), 4: (0, 1, 3, 4), 5: (0, 1, 2, 3, 4)}
STUD = ((7, 7), (8, 7), (7, 8), (8, 8))
PIPS = ((5, 5), (9, 5), (5, 9), (9, 9))
WRENCH_RAMP = [(214, 224, 232), (160, 176, 196), (112, 122, 160), (76, 70, 110), (52, 40, 72)]


def rgba(color):
    return (*color, 255)


def shaded(ramp, x, y, x0, y0, x1, y1):
    """Top-left light, bottom-right dark, flat steps with no gradients."""
    if x == x0 or y == y0:
        return ramp[0]
    if x == x1 or y == y1:
        return ramp[3]
    return ramp[1] if (x + y) % 7 else ramp[2]


def pipe(tier):
    ramp = TIER_RAMPS[tier - 1]
    image = Image.new("RGBA", (SIZE, SIZE))
    for y in range(SIZE):
        for x in range(SIZE):
            image.putpixel((x, y), rgba(shaded(ramp, x, y, 0, 0, SIZE - 1, SIZE - 1)))
    for band in ARM_BANDS:
        for row in STRIPE_ROWS[tier]:
            for x in range(1, SIZE - 1):
                image.putpixel((x, band.start + row), rgba(ramp[3]))
    for x, y in STUD:
        image.putpixel((x, y), rgba(TIER_ACCENTS[tier - 1]))
    return image


PANEL = (198, 198, 198)
SLOT_FILL = (139, 139, 139)
SLOT_LIGHT = (255, 255, 255)
SLOT_DARK = (55, 55, 55)


def bevel(size, fill, light, dark):
    """Flat fill with a one pixel light edge top left and dark edge bottom right."""
    image = Image.new("RGBA", (size, size), rgba(fill))
    for i in range(size):
        image.putpixel((i, 0), rgba(dark if fill == SLOT_FILL else light))
        image.putpixel((0, i), rgba(dark if fill == SLOT_FILL else light))
        image.putpixel((i, size - 1), rgba(light if fill == SLOT_FILL else dark))
        image.putpixel((size - 1, i), rgba(light if fill == SLOT_FILL else dark))
    return image


def blank():
    return Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))


def diagonal(image, ramp, width):
    for i in range(3, 13):
        for w in range(width):
            x, y = 15 - i, i - w
            if 0 <= y < SIZE:
                image.putpixel((x, y), rgba(ramp[1 if w == 0 else 2]))
        image.putpixel((15 - i, i - width), rgba(ramp[3]))


def wrench():
    image = blank()
    diagonal(image, WRENCH_RAMP, 2)
    for x, y in ((11, 2), (12, 2), (13, 3), (12, 4), (2, 12), (3, 13), (2, 14), (4, 13)):
        image.putpixel((x, y), rgba(WRENCH_RAMP[0]))
    image.putpixel((13, 4), rgba(WRENCH_RAMP[4]))
    return image


def upgrade(tier):
    ramp = TIER_RAMPS[tier - 1]
    image = blank()
    for y in range(3, 13):
        for x in range(3, 13):
            image.putpixel((x, y), rgba(shaded(ramp, x, y, 3, 3, 12, 12)))
    for x, y in PIPS[:tier - 1]:
        for dx in range(2):
            for dy in range(2):
                image.putpixel((x + dx, y + dy), rgba(TIER_ACCENTS[tier - 1]))
    return image


def save(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)


def main():
    for tier in range(1, len(TIER_RAMPS) + 1):
        save(pipe(tier), ASSETS / "block" / f"pipe_tier_{tier}.png")
        if tier > 1:
            save(upgrade(tier), ASSETS / "item" / ("pipe_upgrade.png" if tier == 2 else f"pipe_upgrade_{tier}.png"))
    save(wrench(), ASSETS / "item" / "pipe_wrench.png")
    sprites = ASSETS / "gui" / "sprites"
    save(bevel(16, PANEL, SLOT_LIGHT, SLOT_DARK), sprites / "panel.png")
    save(bevel(18, SLOT_FILL, SLOT_LIGHT, SLOT_DARK), sprites / "slot.png")


if __name__ == "__main__":
    main()
