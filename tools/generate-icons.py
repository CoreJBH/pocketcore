#!/usr/bin/env python3
"""Regenerate every launcher asset from the master artwork.

Source artwork: downloads/pocketcore-icon-tesseract2.png (1254 x 1254): an
isometric tesseract - a cyan cube frame around a smaller cube with a glowing
blue core - on the deep navy field #030B27, with nothing else inside. The mark
occupies roughly 41% x 49% of that square, so the artwork is cropped around the
mark and re-scaled so the mark itself covers 50% of the canvas height.

Why 50%: an adaptive icon is a 108dp canvas, the launcher shows only the middle
72dp and the safe zone (never clipped by any mask - squircle, circle or rounded
square) is a 66dp circle. A 50% mark is 54dp tall, i.e. a 27dp radius from the
centre, so it stays 6dp inside the safe circle and 9dp inside the visible tile
on every side - roomy enough for the slight parallax some launchers apply.

The foreground layer is written with a real alpha channel: every pixel keeps its
exact artwork colour and gets the coverage that recreates it over the field, so
compositing the layer on the icon's own background reproduces the artwork to
within one step of 8-bit rounding - the glow of the core included. The adaptive
icon's background layer carries the same #030B27, so the mark grows out of the
same navy instead of sitting on a faintly different square.

Outputs:
    app/src/main/res/mipmap-*/ic_launcher_foreground.png   108dp canvas, alpha
    app/src/main/res/mipmap-*/ic_launcher.png              legacy square icon
    app/src/main/res/mipmap-*/ic_launcher_round.png        legacy round icon
    app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png  themed-icon layer
    app/src/main/res/drawable-nodpi/splash_icon.png        launch-screen tile
    docs/icon.png                                          documentation preview
    docs/monochrome-preview.png                            themed-icon preview
    docs/splash-preview.png                                launch-screen preview
"""

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT.parent.parent / "downloads" / "pocketcore-icon-tesseract2.png"
RES = ROOT / "app" / "src" / "main" / "res"

FIELD = (3, 11, 39)         # #030B27, the artwork field and the icon background
MARK_HEIGHT = 0.50          # share of the 108dp canvas covered by the mark
MARK_IN_TILE = 0.4856       # the mark is 609px of the 1254px artwork tile
TILE_RADIUS = 26 / 96       # matches RoundedCornerShape(26.dp) on the 96dp splash box
ALPHA_FLOOR = 0.012         # the field has +-1 noise; ignore what is below this

# density: (canvas scale, alpha gamma). The tesseract's lines are ~1.5dp wide,
# so every density lands well; mdpi (160dpi) still gets a small lift so the thin
# connectors between the two cubes do not fade out on a 48dp icon.
DENSITIES = {
    "mdpi": (1.0, 0.85),
    "hdpi": (1.5, 1.0),
    "xhdpi": (2.0, 1.0),
    "xxhdpi": (3.0, 1.0),
    "xxxhdpi": (4.0, 1.0),
}
LEGACY_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def load_master() -> Image.Image:
    image = Image.open(SOURCE).convert("RGB")
    if image.size != (1254, 1254):
        image = image.resize((1254, 1254), Image.LANCZOS)
    return image


def measure_mark(image: Image.Image):
    """Bounding box of the visible mark (the cube frames and the core glow)."""
    grey = np.asarray(image.convert("L")).astype(int)
    ys, xs = np.nonzero(grey > 40)
    return int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())


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


def ink_layer(crop: Image.Image) -> Image.Image:
    """Straight-alpha RGBA of the mark: composited over FIELD it is the artwork.

    Coverage is solved against a white ceiling, (pixel - FIELD) / (255 - FIELD)
    per channel: the largest channel decides alpha and the RGB keeps the exact
    artwork colour that is left underneath. No channel is capped, so the cyan
    frame, the gradient of the inner cube and the core glow all come back bit
    for bit instead of being flattened onto a single ink colour.
    """
    art = np.asarray(crop).astype(np.float32)
    field = np.array(FIELD, dtype=np.float32)
    span = 255.0 - field
    delta = art - field

    alpha = np.clip((delta / span).max(axis=2), 0.0, 1.0)
    alpha[alpha < ALPHA_FLOOR] = 0.0

    source = np.where(
        (alpha > 0)[:, :, None],
        field + delta / np.maximum(alpha, 1e-3)[:, :, None],
        field,
    )
    source = np.clip(source, 0, 255)
    rgba = np.dstack([source, alpha * 255.0]).astype(np.uint8)
    return Image.fromarray(rgba, "RGBA")


def boost_alpha(layer: Image.Image, gamma: float) -> Image.Image:
    """Lift low coverage without touching the ink colour or the mark's box."""
    if gamma == 1.0:
        return layer
    channels = list(layer.split())
    channels[3] = channels[3].point(lambda value: min(255, round((value / 255.0) ** gamma * 255)))
    return Image.merge("RGBA", channels)


def scale_layer(layer: Image.Image, canvas_size: int, mark_ratio: float) -> tuple[Image.Image, tuple[int, int]]:
    side = max(1, int(round(canvas_size * MARK_HEIGHT / mark_ratio)))
    offset = (canvas_size - side) // 2
    return layer.resize((side, side), Image.LANCZOS), (offset, offset)


def foreground(canvas_size: int, layer: Image.Image, mark_ratio: float) -> Image.Image:
    """Transparent canvas with the mark in the middle 50%."""
    canvas = Image.new("RGBA", (canvas_size, canvas_size), FIELD + (0,))
    scaled, offset = scale_layer(layer, canvas_size, mark_ratio)
    canvas.paste(scaled, offset, scaled)
    return canvas


def flat_icon(size: int, layer: Image.Image, mark_ratio: float) -> Image.Image:
    """Opaque icon on the field colour, for API < 26 and the round variant."""
    canvas = Image.new("RGB", (size, size), FIELD)
    scaled, offset = scale_layer(layer, size, mark_ratio)
    canvas.paste(scaled.convert("RGB"), offset, scaled)
    return canvas


def rounded_mask(size: int, radius_ratio: float = 0.5) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    radius = int(round(size * radius_ratio))
    draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    return mask


def monochrome(layer: Image.Image, mark_ratio: float, canvas_size: int) -> Image.Image:
    """Alpha-only themed-icon layer: the tesseract, no field, no glyph."""
    scaled, offset = scale_layer(layer, canvas_size, mark_ratio)
    white = Image.new("RGBA", scaled.size, (255, 255, 255, 255))
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (255, 255, 255, 0))
    canvas.paste(white, offset, scaled.split()[3])
    return canvas


def splash_tile(layer: Image.Image, mark_ratio: float, size: int = 512) -> Image.Image:
    """The launch-screen tile: field-coloured rounded square with the mark."""
    tile = Image.new("RGBA", (size, size), FIELD + (255,))
    side = max(1, int(round(size * MARK_IN_TILE / mark_ratio)))
    scaled = layer.resize((side, side), Image.LANCZOS)
    offset = (size - side) // 2
    tile.paste(scaled, (offset, offset), scaled)
    tile.putalpha(rounded_mask(size, TILE_RADIUS))
    return tile


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    name = "LiberationSans-Bold.ttf" if bold else "LiberationSans-Regular.ttf"
    return ImageFont.truetype(f"/usr/share/fonts/truetype/liberation/{name}", size)


def splash_preview(tile: Image.Image) -> Image.Image:
    """The launch screen as SplashScreen.kt draws it, on a 360dp x 780dp phone."""
    scale = 3.0
    width, height = int(360 * scale), int(780 * scale)
    canvas = Image.new("RGB", (width, height), (0x05, 0x09, 0x12))
    draw = ImageDraw.Draw(canvas, "RGBA")
    box = int(96 * scale)
    x0 = (width - box) // 2
    y0 = int(height * 0.42) - box // 2
    cx, cy = x0 + box / 2, y0 + box / 2
    # Cyan halo behind the mark (SplashScreen draws the same radial gradient).
    radius = int(box * 0.82)
    for step in range(radius, 0, -1):
        alpha = int(0.22 * 255 * (1 - step / radius) ** 2)
        draw.ellipse((cx - step, cy - step, cx + step, cy + step), fill=(0x38, 0xBD, 0xF8, alpha))
    tile = tile.resize((box, box), Image.LANCZOS)
    canvas.paste(tile, (x0, y0), tile)
    draw.text((cx, y0 + box + 26 * scale), "POCKETCORE", font=font(int(24 * scale), True),
              fill=(255, 255, 255), anchor="ma")
    draw.text((cx, y0 + box + 56 * scale), "Your mobile Bitcoin full node", font=font(int(12 * scale)),
              fill=(0x94, 0xA3, 0xB8), anchor="ma")
    return canvas


def monochrome_preview(mono: Image.Image) -> Image.Image:
    """The themed layer as Android 13+ tints it: on white and on a dark shade."""
    tile = 200
    sheet = Image.new("RGB", (tile * 2 + 72, tile + 48), (255, 255, 255))
    for index, (bg, tint) in enumerate((((255, 255, 255), (60, 72, 96)), ((28, 30, 34), (200, 220, 255)))):
        canvas = Image.new("RGB", (tile, tile), bg)
        layer = mono.resize((tile, tile), Image.LANCZOS)
        solid = Image.new("RGB", (tile, tile), tint)
        canvas.paste(solid, (0, 0), layer.split()[3])
        sheet.paste(canvas, (24 + index * (tile + 24), 24))
    return sheet


def main() -> None:
    master = load_master()
    box = measure_mark(master)
    crop, mark_ratio = crop_mark(master, box)
    layer = ink_layer(crop)
    print(f"mark box={box} crop={crop.size} mark_ratio={mark_ratio:.4f}")

    # Prove the reconstruction: alpha * ink over the field must equal the artwork.
    # Pixels darker than the field are the generator's specks; they are reported
    # separately instead of being turned into a visible artefact.
    art = np.asarray(crop).astype(int)
    rgba = np.asarray(layer).astype(int)
    a = rgba[:, :, 3:4] / 255.0
    rebuilt = rgba[:, :, :3] * a + np.array(FIELD) * (1 - a)
    error = np.abs(rebuilt - art)
    darker = (art - np.array(FIELD)).min(axis=2) < -2
    kept = error[~darker]
    print(
        f"alpha reconstruction over the field: mean {kept.mean():.2f}, "
        f"p99.9 {np.percentile(kept, 99.9):.1f}, over 5/255 {int((kept > 5).sum())} "
        f"of {kept.size} channels; {int(darker.sum())} darker-than-field specks dropped"
    )

    for density, (scale, gamma) in DENSITIES.items():
        canvas_size = int(round(108 * scale))
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        density_layer = boost_alpha(layer, gamma)

        foreground(canvas_size, density_layer, mark_ratio).save(
            folder / "ic_launcher_foreground.png", optimize=True
        )

        legacy = LEGACY_SIZES[density]
        flat_icon(legacy, density_layer, mark_ratio).save(folder / "ic_launcher.png", optimize=True)

        round_icon = flat_icon(legacy, density_layer, mark_ratio).convert("RGBA")
        round_icon.putalpha(rounded_mask(legacy, 0.5))
        round_icon.save(folder / "ic_launcher_round.png", optimize=True)

        if density == "xxxhdpi":
            nodpi = RES / "drawable-nodpi"
            nodpi.mkdir(parents=True, exist_ok=True)
            mono = monochrome(density_layer, mark_ratio, canvas_size)
            mono.save(nodpi / "ic_launcher_monochrome.png", optimize=True)
            tile = splash_tile(density_layer, mark_ratio)
            tile.save(nodpi / "splash_icon.png", optimize=True)
            tile.save(ROOT / "docs" / "icon.png", optimize=True)
            monochrome_preview(mono).save(ROOT / "docs" / "monochrome-preview.png", optimize=True)
            splash_preview(tile).save(ROOT / "docs" / "splash-preview.png", optimize=True)


if __name__ == "__main__":
    main()
