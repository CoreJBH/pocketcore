#!/usr/bin/env python3
"""Render a mock-up of the POCKETCORE notification cards.

This is a drawing of the card the app builds in `core/NotificationCard.kt`, not a
screenshot: it exists so the wording, the header row and the colours can be
reviewed without a phone. The real shade is drawn by One UI.

Usage:
    python3 tools/render-notification-preview.py [output.png]
"""

from __future__ import annotations

import os
import sys

from PIL import Image, ImageDraw, ImageFont

FONT_DIR = "/usr/share/fonts/truetype/liberation"
BOLD = os.path.join(FONT_DIR, "LiberationSans-Bold.ttf")
REGULAR = os.path.join(FONT_DIR, "LiberationSans-Regular.ttf")

WIDTH = 1080
HEIGHT = 1200
MARGIN = 32
CARD_HEIGHT = 160

SYNCED = (0x03, 0x69, 0xA1)
SYNCING = (0x1E, 0x3A, 0x8A)
IDLE = (0x0F, 0x17, 0x2A)


def font(path: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size)


def rounded(draw: ImageDraw.ImageDraw, box, radius, fill):
    draw.rounded_rectangle(box, radius=radius, fill=fill)


def mix(base, white_share: float):
    """Blend [base] toward white by [white_share] (PIL does not composite here)."""
    return tuple(int(channel + (255 - channel) * white_share) for channel in base[:3])


def hexagon(draw: ImageDraw.ImageDraw, cx: float, cy: float, r: float, color):
    import math

    points = []
    for index in range(6):
        angle = math.radians(-90 + index * 60)
        points.append((cx + r * math.cos(angle), cy + r * math.sin(angle)))
    draw.line(points + [points[0]], fill=color, width=max(2, int(r * 0.18)), joint="curve")
    inner = r * 0.42
    diamond = [(cx, cy - inner), (cx + inner, cy), (cx, cy + inner), (cx - inner, cy)]
    draw.line(diamond + [diamond[0]], fill=color, width=max(2, int(r * 0.16)), joint="curve")


def card(draw: ImageDraw.ImageDraw, top: int, color, state: str, line: str, progress: float | None):
    left, right = MARGIN, WIDTH - MARGIN
    rounded(draw, (left, top, right, top + CARD_HEIGHT), CARD_HEIGHT // 2, color)

    icon_cx, icon_cy = left + 88, top + CARD_HEIGHT // 2
    hexagon(draw, icon_cx, icon_cy, 34, (255, 255, 255))

    text_x = left + 156
    baseline = top + 40
    name_font, state_font, time_font = font(BOLD, 30), font(REGULAR, 28), font(REGULAR, 26)
    draw.text((text_x, baseline), "POCKETCORE", font=name_font, fill=(255, 255, 255))
    name_width = draw.textlength("POCKETCORE", font=name_font)
    draw.text((text_x + name_width + 18, baseline + 2), state, font=state_font, fill=(235, 240, 250))
    time_text = "8:33 PM"
    draw.text(
        (right - 92 - draw.textlength(time_text, font=time_font), baseline + 4),
        time_text,
        font=time_font,
        fill=(225, 232, 245),
    )
    # Collapse chevron.
    chevron_x, chevron_y = right - 58, top + CARD_HEIGHT // 2
    draw.line([(chevron_x - 11, chevron_y - 6), (chevron_x, chevron_y + 6), (chevron_x + 11, chevron_y - 6)],
              fill=(230, 236, 248), width=4, joint="curve")

    line_font = font(REGULAR, 30)
    draw.text((text_x, top + 86), line, font=line_font, fill=(255, 255, 255))

    if progress is not None:
        bar_left, bar_right = text_x, right - 92
        bar_y = top + 128
        rounded(draw, (bar_left, bar_y, bar_right, bar_y + 8), 4, mix(color, 0.22))
        filled = bar_left + (bar_right - bar_left) * progress
        rounded(draw, (bar_left, bar_y, filled, bar_y + 8), 4, mix(color, 0.95))


def main() -> int:
    output = sys.argv[1] if len(sys.argv) > 1 else "docs/notification-preview.png"

    image = Image.new("RGBA", (WIDTH, HEIGHT), (146, 158, 196, 255))
    draw = ImageDraw.Draw(image, "RGBA")

    title_font = font(BOLD, 32)
    note_font = font(REGULAR, 24)
    draw.text((MARGIN, 44), "POCKETCORE notification cards (mock-up)", font=title_font, fill=(20, 28, 48))
    draw.text(
        (MARGIN, 90),
        "Drawn to the Android shade layout; the phone paints the real card.",
        font=note_font,
        fill=(40, 50, 74),
    )

    card(draw, 146, SYNCED, "Fully synced", "Synced - Block #976,724", None)
    card(draw, 330, SYNCING, "Syncing", "12.34% - Block #123,456", 0.1234)
    card(draw, 514, SYNCING, "Pre-syncing", "42.10% - Header #812,345", 0.4210)
    card(draw, 698, IDLE, "Waiting for Wi-Fi", "Paused - Block #976,724", None)
    card(draw, 882, IDLE, "Starting", "Starting node", None)

    draw.text(
        (MARGIN, 1088),
        "Header row: app name, state, time of the last RPC reply. One content line, no second icon.",
        font=note_font,
        fill=(40, 50, 74),
    )

    image.convert("RGB").save(output, quality=92)
    print(f"wrote {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
