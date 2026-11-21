#!/usr/bin/env python3
"""Turn raw phone screenshots into the images the README shows.

The captures come from a 1080 x 2640 Samsung device scaled to 838 x 2048 and
carry the system status bar on top and the three-button navigation bar at the
bottom. Neither belongs in a project screenshot, so every tab image is cut to
the app's own window (y = 58 .. 1950 of the 2048 px frame) and its corners are
rounded the way the app's cards are.

The notification shade capture is used only for the colorized status card: the
blue pill (`<percent> - Block #<height>`) is cut out on its own, and the Korean
AM/PM marker the phone's locale adds to the timestamp is painted back to card
background so the document stays English-only.

Usage:
    python3 tools/make-screenshots.py --node node.jpg --sync sync.jpg \\
        --explore explore.jpg --settings settings.jpg --shade shade.jpg \\
        --icon pocketcore-icon-tesseract2.png --out docs
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

# The app window inside a 838 x 2048 system screenshot of this device.
APP_TOP = 58
APP_BOTTOM = 1950
SCREEN_RADIUS = 26

# The colorized status card inside the same sized shade capture.
CARD_BOX = (28, 795, 810, 920)
# Columns of the card that hold the locale timestamp (after `Syncing`, before the
# chevron), in card coordinates.
TIMESTAMP_BOX = (539, 652)


def rounded(image: Image.Image, radius: int) -> Image.Image:
    image = image.convert("RGBA")
    mask = Image.new("L", image.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, image.width - 1, image.height - 1), radius=radius, fill=255
    )
    image.putalpha(mask)
    return image


def screen(path: Path) -> Image.Image:
    image = Image.open(path).convert("RGB")
    if image.size != (838, 2048):
        raise SystemExit(f"{path}: expected an 838 x 2048 capture, got {image.size}")
    return rounded(image.crop((0, APP_TOP, image.width, APP_BOTTOM)), SCREEN_RADIUS)


def erase_timestamp(card: Image.Image) -> Image.Image:
    """Paint the locale timestamp back to card background.

    The title line reads `<percent> - Block #<height>  Syncing  <locale> HH:MM`;
    only the `Syncing` marker and everything left of it is kept, and the
    timestamp between it and the chevron is removed. The text sits on a nearly
    flat blue field, so each column is refilled by interpolating between a clean
    band above and below it, which keeps the card's vertical gradient intact.
    """
    array = np.array(card.convert("RGB")).astype(np.float32)
    left, right = TIMESTAMP_BOX
    window = array[20:80, left:right]
    bright = window.sum(axis=2) > 300
    if not bright.any():
        return card
    rows = np.nonzero(bright.any(axis=1))[0]
    top = 20 + rows.min() - 5
    bottom = 20 + rows.max() + 5

    above = array[top - 9:top - 1, left:right].mean(axis=0)
    below = array[bottom + 1:bottom + 9, left:right].mean(axis=0)
    span = np.linspace(0.0, 1.0, bottom - top + 1)[:, None, None]
    fill = above[None, :, :] * (1 - span) + below[None, :, :] * span
    array[top:bottom + 1, left:right] = fill
    return Image.fromarray(np.clip(array, 0, 255).astype(np.uint8))


def status_card(path: Path) -> Image.Image:
    shade = Image.open(path).convert("RGB")
    if shade.size != (838, 2048):
        raise SystemExit(f"{path}: expected an 838 x 2048 capture, got {shade.size}")
    card = shade.crop(CARD_BOX)
    card = erase_timestamp(card)
    return rounded(card, card.height // 2)


def logo(path: Path, side: int = 384) -> Image.Image:
    """Crop the master artwork to the mark plus a small margin.

    The master is a 1254 px square with the tesseract drawn at roughly 41% of
    its width; the README only needs the mark, so the dead field around it is
    cut down to a 9% margin. 384 px is still 2.5x the 150 px the README draws
    it at, without the megabyte a full 1254 px field would add to the repository.
    """
    master = Image.open(path).convert("RGB")
    field = np.array(master).astype(int)
    difference = np.abs(field - field[0, 0]).sum(axis=2)
    ys, xs = np.nonzero(difference > 20)
    left, right, top, bottom = xs.min(), xs.max(), ys.min(), ys.max()
    extent = max(right - left, bottom - top)
    margin = round(extent * 0.09)
    centre_x, centre_y = (left + right) // 2, (top + bottom) // 2
    half = extent // 2 + margin
    box = (centre_x - half, centre_y - half, centre_x + half, centre_y + half)
    tile = master.crop(box).resize((side, side), Image.LANCZOS)
    return rounded(tile, round(side * 0.25))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--node", type=Path, required=True)
    parser.add_argument("--sync", type=Path, required=True)
    parser.add_argument("--explore", type=Path, required=True)
    parser.add_argument("--settings", type=Path, required=True)
    parser.add_argument("--shade", type=Path, required=True)
    parser.add_argument("--icon", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("docs"))
    arguments = parser.parse_args()

    arguments.out.mkdir(parents=True, exist_ok=True)
    jobs = {
        "preview.png": screen(arguments.node),
        "screen-sync.png": screen(arguments.sync),
        "screen-explore.png": screen(arguments.explore),
        "screen-settings.png": screen(arguments.settings),
        "screen-notification.png": status_card(arguments.shade),
        "logo.png": logo(arguments.icon),
    }
    for name, image in jobs.items():
        target = arguments.out / name
        image.save(target, optimize=True)
        print(f"{target}  {image.width}x{image.height}")


if __name__ == "__main__":
    main()
