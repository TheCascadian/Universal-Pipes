"""Generates the textures for Universal Pipes.

The pipe texture is grayscale so the client block and item colour handlers can
tint it per tier. Run from the repository root:

    python tools/generate_textures.py
"""
from pathlib import Path

from PIL import Image

SIZE = 16
ASSETS = Path("src/main/resources/assets/universal_pipes/textures")

PIPE_RAMP = [(214, 214, 214), (178, 178, 178), (142, 142, 142), (106, 106, 106), (74, 74, 74)]
WRENCH_RAMP = [(214, 224, 232), (160, 176, 196), (112, 122, 160), (76, 70, 110), (52, 40, 72)]
UPGRADE_RAMP = [(250, 226, 150), (232, 176, 84), (196, 120, 62), (140, 76, 60), (88, 48, 56)]


def rgba(color):
    return (*color, 255)


def shaded(ramp, x, y, x0, y0, x1, y1):
    """Top-left light, bottom-right dark, flat steps with no gradients."""
    if x == x0 or y == y0:
        return ramp[0]
    if x == x1 or y == y1:
        return ramp[3]
    return ramp[1] if (x + y) % 7 else ramp[2]


def pipe():
    image = Image.new("RGBA", (SIZE, SIZE), rgba(PIPE_RAMP[4]))
    for y in range(SIZE):
        for x in range(SIZE):
            image.putpixel((x, y), rgba(shaded(PIPE_RAMP, x, y, 0, 0, SIZE - 1, SIZE - 1)))
    for x in range(SIZE):
        image.putpixel((x, 7), rgba(PIPE_RAMP[3]))
        image.putpixel((x, 8), rgba(PIPE_RAMP[0]))
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


def upgrade():
    image = blank()
    for y in range(3, 13):
        for x in range(3, 13):
            image.putpixel((x, y), rgba(shaded(UPGRADE_RAMP, x, y, 3, 3, 12, 12)))
    for i in range(5, 11):
        image.putpixel((7, i), rgba(UPGRADE_RAMP[0]))
        image.putpixel((i, 7), rgba(UPGRADE_RAMP[0]))
    return image


def save(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)


def main():
    save(pipe(), ASSETS / "block" / "pipe.png")
    save(wrench(), ASSETS / "item" / "pipe_wrench.png")
    save(upgrade(), ASSETS / "item" / "pipe_upgrade.png")
    sprites = ASSETS / "gui" / "sprites"
    save(bevel(16, PANEL, SLOT_LIGHT, SLOT_DARK), sprites / "panel.png")
    save(bevel(18, SLOT_FILL, SLOT_LIGHT, SLOT_DARK), sprites / "slot.png")


if __name__ == "__main__":
    main()
