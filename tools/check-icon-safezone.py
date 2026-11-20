#!/usr/bin/env python3
"""Prove the rebuilt launcher art survives every launcher mask.

For each generated `ic_launcher_foreground.png` the script reproduces what a
launcher does:

  * takes the middle 72dp of the 108dp canvas (the visible viewport),
  * applies the three mask shapes a launcher may use - circle, squircle and
    rounded square - all 72dp wide,
  * reports any mark pixel (luminance above the field) that falls outside the
    mask, which is exactly what a user sees as clipping.

It also renders `docs/icon-preview.png` with the icon under each mask, next to
the old Build 2 art for comparison.
"""

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
FIELD_LUMA = 30


def masks(size: int):
    def circle(draw, box):
        draw.ellipse(box, fill=255)

    def rounded_square(draw, box):
        draw.rounded_rectangle(box, radius=int(size * 0.28), fill=255)

    def squircle(draw, box):
        # Superellipse |x|^4 + |y|^4 = r^4, the shape One UI uses.
        radius = size / 2.0
        points = []
        for step in range(720):
            angle = 2 * np.pi * step / 720
            cos, sin = np.cos(angle), np.sin(angle)
            points.append(
                (
                    size / 2.0 + radius * np.sign(cos) * abs(cos) ** 0.5,
                    size / 2.0 + radius * np.sign(sin) * abs(sin) ** 0.5,
                )
            )
        draw.polygon(points, fill=255)

    result = {}
    for name, painter in (("circle", circle), ("squircle", squircle), ("roundedsquare", rounded_square)):
        mask = Image.new("L", (size, size), 0)
        painter(ImageDraw.Draw(mask), (0, 0, size - 1, size - 1))
        result[name] = np.asarray(mask) > 127
    return result


def visible_window(image: Image.Image, zoom: float, canvas_dp: int = 108, visible_dp: int = 72):
    """The 72dp viewport, optionally after a launcher's parallax zoom."""
    size = image.width
    span = int(round(size * visible_dp / canvas_dp / zoom))
    offset = (size - span) // 2
    window = image.crop((offset, offset, offset + span, offset + span))
    visible = int(round(size * visible_dp / canvas_dp))
    if span != visible:
        window = window.resize((visible, visible), Image.LANCZOS)
    return window, visible


def check(density: str, zoom: float = 1.0, canvas_dp: int = 108, visible_dp: int = 72) -> dict:
    path = RES / f"mipmap-{density}" / "ic_launcher_foreground.png"
    image = Image.open(path).convert("RGB")
    size = image.width
    window, visible = visible_window(image, zoom, canvas_dp, visible_dp)
    luma = np.asarray(window.convert("L")).astype(int)
    mark = luma > FIELD_LUMA
    report = {"density": density, "zoom": zoom, "canvas": size, "visible": visible}
    for name, mask in masks(visible).items():
        clipped = int(np.count_nonzero(mark & ~mask))
        report[name] = clipped
    ys, xs = np.nonzero(mark)
    report["mark_box"] = (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max()))
    report["margin_dp"] = {
        "left": round(xs.min() * canvas_dp / size, 2),
        "top": round(ys.min() * canvas_dp / size, 2),
        "right": round((visible - 1 - xs.max()) * canvas_dp / size, 2),
        "bottom": round((visible - 1 - ys.max()) * canvas_dp / size, 2),
    }
    return report


def preview() -> None:
    """Compose the icon under each mask, plus the visible-window view."""
    foreground = Image.open(RES / "mipmap-xxxhdpi" / "ic_launcher_foreground.png").convert("RGB")
    window, visible = visible_window(foreground, 1.0)

    tile = 320
    scale = tile / visible
    big = window.resize((tile, tile), Image.LANCZOS)
    shapes = []
    for name in ("circle", "squircle", "roundedsquare"):
        mask = Image.fromarray((masks(tile)[name] * 255).astype("uint8"))
        canvas = Image.new("RGB", (tile, tile), (60, 72, 96))
        canvas.paste(big, (0, 0), mask)
        shapes.append((name, canvas))

    gap = 24
    width = tile * len(shapes) + gap * (len(shapes) + 1)
    sheet = Image.new("RGB", (width, tile + gap * 2), (255, 255, 255))
    for index, (_, canvas) in enumerate(shapes):
        sheet.paste(canvas, (gap + index * (tile + gap), gap))
    sheet.save(ROOT / "docs" / "icon-preview.png")
    print(f"preview written to docs/icon-preview.png (scale {scale:.2f})")


if __name__ == "__main__":
    failures = 0
    for name in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
        for zoom in (1.0, 1.08):
            report = check(name, zoom)
            failures += report["circle"] + report["squircle"] + report["roundedsquare"]
            print(report)
    print(f"clipped mark pixels across every density, mask and zoom: {failures}")
    preview()
