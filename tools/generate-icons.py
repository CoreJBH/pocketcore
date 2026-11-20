#!/usr/bin/env python3
"""Regenerate every launcher asset from the master artwork.

Source artwork: downloads/pocketcore-icon-blue1.png (1254 x 1254). The hexagon
plus its corner node dot occupies roughly 64% x 74% of that square, so the
artwork is cropped around the mark and re-scaled so the mark itself covers
50% of the canvas height.

Why 50%: an adaptive icon is a 108dp canvas, the launcher shows only the middle
72dp and the safe zone (never clipped by any mask - squircle, circle or rounded
square) is a 66dp circle. A 50% mark is 54dp tall, i.e. a 27dp radius from the
centre, so it stays 6dp inside the safe circle and 9dp inside the visible tile
on every side - roomy enough for the slight parallax some launchers apply.

Outputs:
    app/src/main/res/mipmap-*/ic_launcher_foreground.png   108dp canvas
    app/src/main/res/mipmap-*/ic_launcher.png              legacy square icon
    app/src/main/res/mipmap-*/ic_launcher_round.png        legacy round icon
    app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png  themed-icon layer
    app/src/main/res/drawable-nodpi/splash_icon.png        launch-screen mark
    docs/icon.png                                          documentation preview
"""

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT.parent.parent / "downloads" / "pocketcore-icon-blue1.png"
RES = ROOT / "app" / "src" / "main" / "res"

FIELD = (1, 8, 23)          # #010817, the artwork field and the icon background
MARK_HEIGHT = 0.50          # share of the 108dp canvas covered by the mark

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LEGACY_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def load_master() -> Image.Image:
    image = Image.open(SOURCE).convert("RGB")
    if image.size != (1254, 1254):
        image = image.resize((1254, 1254), Image.LANCZOS)
    return image


def measure_mark(image: Image.Image):
    """Bounding box of the visible mark (hexagon, coin glyph, node dot glow)."""
    grey = image.convert("L")
    width, height = grey.size
    pixels = grey.load()
    left, top, right, bottom = width, height, -1, -1
    for y in range(height):
        for x in range(width):
            if pixels[x, y] > 40:
                left = min(left, x)
                right = max(right, x)
                top = min(top, y)
                bottom = max(bottom, y)
    return left, top, right, bottom


def crop_mark(image: Image.Image, box) -> tuple[Image.Image, float]:
    """Square crop centred on the mark, with a small margin around it."""
    left, top, right, bottom = box
    mark_height = bottom - top + 1
    side = int(round(mark_height * 1.02))
    centre_x = (left + right) / 2.0
    centre_y = (top + bottom) / 2.0
    x0 = int(round(centre_x - side / 2.0))
    y0 = int(round(centre_y - side / 2.0))
    x0 = max(0, min(image.width - side, x0))
    y0 = max(0, min(image.height - side, y0))
    crop = image.crop((x0, y0, x0 + side, y0 + side))
    return crop, mark_height / side


def compose(canvas_size: int, crop: Image.Image, mark_ratio: float) -> Image.Image:
    """Mark centred on an opaque field canvas, mark height = MARK_HEIGHT of it."""
    canvas = Image.new("RGB", (canvas_size, canvas_size), FIELD)
    side = max(1, int(round(canvas_size * MARK_HEIGHT / mark_ratio)))
    mark = crop.resize((side, side), Image.LANCZOS)
    offset = (canvas_size - side) // 2
    canvas.paste(mark, (offset, offset))
    return canvas


def rounded_mask(size: int, radius_ratio: float = 0.5) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    radius = int(round(size * radius_ratio))
    draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    return mask


def monochrome(crop: Image.Image, mark_ratio: float, canvas_size: int) -> Image.Image:
    """Alpha-only themed-icon layer: white mark on transparent, glow kept soft."""
    side = max(1, int(round(canvas_size * MARK_HEIGHT / mark_ratio)))
    mark = crop.convert("L").resize((side, side), Image.LANCZOS)
    # The field sits at luminance ~20; the mark is bright. Ramp between the two
    # so the clean outline stays opaque and the halo fades out instead of
    # turning into a hard-edged square.
    alpha = mark.point(lambda value: max(0, min(255, int((value - 24) * 255 / 46))))
    layer = Image.new("RGBA", (canvas_size, canvas_size), (255, 255, 255, 0))
    white = Image.new("RGBA", (side, side), (255, 255, 255, 255))
    offset = (canvas_size - side) // 2
    layer.paste(white, (offset, offset), alpha)
    return layer


def splash(master: Image.Image, size: int = 512) -> Image.Image:
    """The full artwork as a rounded app tile, corners cut to transparency."""
    image = master.resize((size, size), Image.LANCZOS).convert("RGBA")
    # The artwork bakes its own rounded corners; every pixel outside them is
    # pure black while the field is #010817, so the blue channel separates them
    # and keeps the antialiased tile edge.
    alpha = image.split()[2].point(lambda value: max(0, min(255, value * 255 // 16)))
    image.putalpha(alpha)
    return image


def main() -> None:
    master = load_master()
    box = measure_mark(master)
    crop, mark_ratio = crop_mark(master, box)
    print(f"mark box={box} crop={crop.size} mark_ratio={mark_ratio:.4f}")

    for density, scale in DENSITIES.items():
        canvas = int(round(108 * scale))
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)

        foreground = compose(canvas, crop, mark_ratio)
        foreground.save(folder / "ic_launcher_foreground.png", optimize=True)

        legacy = LEGACY_SIZES[density]
        square = compose(legacy, crop, mark_ratio)
        square.save(folder / "ic_launcher.png", optimize=True)

        round_icon = compose(legacy, crop, mark_ratio).convert("RGBA")
        round_icon.putalpha(rounded_mask(legacy, 0.5))
        round_icon.save(folder / "ic_launcher_round.png", optimize=True)

        if density == "xxxhdpi":
            mono = monochrome(crop, mark_ratio, canvas)
            nodpi = RES / "drawable-nodpi"
            nodpi.mkdir(parents=True, exist_ok=True)
            mono.save(nodpi / "ic_launcher_monochrome.png", optimize=True)
            tile = splash(master)
            tile.save(nodpi / "splash_icon.png", optimize=True)
            tile.save(ROOT / "docs" / "icon.png", optimize=True)


if __name__ == "__main__":
    main()
