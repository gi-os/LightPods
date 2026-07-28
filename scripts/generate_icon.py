#!/usr/bin/env python3
"""
Regenerate the LightPods launcher icon.

The mark is a pair of earbuds seen head on: two rings for the drivers, two stems
dropping from them. White line art on black, matching the icon language of the
sibling Light Phone III tools.

Geometry is defined once, in the 108x108 adaptive-icon canvas, and emitted twice —
as Android vector paths and as raster fallbacks. Everything sits inside the 18..90
safe zone so no launcher mask can clip it.

    python3 scripts/generate_icon.py

Needs Pillow. Rewrites app/src/main/res/{drawable,mipmap-*}.
"""

from __future__ import annotations

import os

from PIL import Image, ImageDraw

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

CANVAS = 108
SAFE = (18, 90)
STROKE = 5.0

DRIVER_R = 11.0          # centreline radius of the driver ring
LEFT = (34.0, 38.0)      # driver centres
RIGHT = (74.0, 38.0)
STEM_BOTTOM = 84.0       # where both stems end
STEM_DX = 6.0            # stems splay outward from the driver centre

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SUPERSAMPLE = 8


def stem_ends(cx: float, outward: float) -> tuple[tuple[float, float], tuple[float, float]]:
    """Stem hangs off the bottom of the driver, splayed slightly outward.

    It starts exactly on the ring rather than inside it — a cap disc landing inside
    the circle punches a visible notch out of the ring at small sizes.
    """
    top = (cx + outward * 0.2, LEFT[1] + DRIVER_R)
    bottom = (cx + outward, STEM_BOTTOM)
    return top, bottom


def check_safe_zone() -> None:
    lo, hi = SAFE
    edge = STROKE / 2
    extents = [
        (LEFT[0] - DRIVER_R - edge, LEFT[1] - DRIVER_R - edge),
        (RIGHT[0] + DRIVER_R + edge, RIGHT[1] + DRIVER_R + edge),
        (LEFT[0] - STEM_DX - edge, STEM_BOTTOM + edge),
        (RIGHT[0] + STEM_DX + edge, STEM_BOTTOM + edge),
    ]
    for x, y in extents:
        assert lo <= x <= hi and lo <= y <= hi, f"({x}, {y}) escapes the safe zone"


def ring_path(cx: float, cy: float, r: float) -> str:
    return (
        f"M {cx - r},{cy} A {r},{r} 0 1 0 {cx + r},{cy} "
        f"A {r},{r} 0 1 0 {cx - r},{cy} Z"
    )


def line_path(a: tuple[float, float], b: tuple[float, float]) -> str:
    return f"M {a[0]},{a[1]} L {b[0]},{b[1]}"


def paths() -> list[str]:
    out = [ring_path(*LEFT, DRIVER_R), ring_path(*RIGHT, DRIVER_R)]
    for cx, outward in ((LEFT[0], -STEM_DX), (RIGHT[0], STEM_DX)):
        out.append(line_path(*stem_ends(cx, outward)))
    return out


def write_vectors() -> None:
    os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)

    body = "\n".join(
        f'    <path\n'
        f'        android:pathData="{p}"\n'
        f'        android:strokeColor="#FFFFFF"\n'
        f'        android:strokeWidth="{STROKE}"\n'
        f'        android:strokeLineCap="round" />'
        for p in paths()
    )
    foreground = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{CANVAS}dp"\n'
        f'    android:height="{CANVAS}dp"\n'
        f'    android:viewportWidth="{CANVAS}"\n'
        f'    android:viewportHeight="{CANVAS}">\n'
        f"{body}\n"
        "</vector>\n"
    )
    background = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{CANVAS}dp"\n'
        f'    android:height="{CANVAS}dp"\n'
        f'    android:viewportWidth="{CANVAS}"\n'
        f'    android:viewportHeight="{CANVAS}">\n'
        f'    <path android:pathData="M0,0h{CANVAS}v{CANVAS}h-{CANVAS}z"\n'
        '        android:fillColor="#000000" />\n'
        "</vector>\n"
    )
    _write(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), foreground)
    _write(os.path.join(RES, "drawable", "ic_launcher_background.xml"), background)

    # The status-bar icon is the same mark without the background layer. Android
    # tints it solid white, so only the alpha channel survives — line art is fine.
    _write(os.path.join(RES, "drawable", "ic_notification.xml"), foreground)

    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        "</adaptive-icon>\n"
    )
    os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        _write(os.path.join(RES, "mipmap-anydpi-v26", name), adaptive)


def render(px: int, circular: bool) -> Image.Image:
    """Draw at SUPERSAMPLE scale and shrink, since Pillow has no line antialiasing."""
    s = px * SUPERSAMPLE
    k = s / CANVAS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    if circular:
        draw.ellipse([0, 0, s - 1, s - 1], fill=(0, 0, 0, 255))
    else:
        draw.rectangle([0, 0, s - 1, s - 1], fill=(0, 0, 0, 255))

    w = STROKE * k
    for cx, cy in (LEFT, RIGHT):
        r = DRIVER_R * k
        box = [cx * k - r, cy * k - r, cx * k + r, cy * k + r]
        draw.ellipse(box, outline=(255, 255, 255, 255), width=round(w))

    for cx, outward in ((LEFT[0], -STEM_DX), (RIGHT[0], STEM_DX)):
        (ax, ay), (bx, by) = stem_ends(cx, outward)
        draw.line([ax * k, ay * k, bx * k, by * k], fill=(255, 255, 255, 255), width=round(w))
        # Pillow butt-caps its lines; discs at each end give the rounded cap the
        # vector drawable already has, so raster and vector match.
        for x, y in ((ax, ay), (bx, by)):
            draw.ellipse(
                [x * k - w / 2, y * k - w / 2, x * k + w / 2, y * k + w / 2],
                fill=(255, 255, 255, 255),
            )

    return img.resize((px, px), Image.LANCZOS)


def write_rasters() -> None:
    for density, px in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        render(px, circular=False).save(os.path.join(folder, "ic_launcher.png"))
        render(px, circular=True).save(os.path.join(folder, "ic_launcher_round.png"))
        print(f"mipmap-{density}: {px}px")


def _write(path: str, text: str) -> None:
    with open(path, "w") as fh:
        fh.write(text)
    print(os.path.relpath(path, os.path.join(RES, "..")))


if __name__ == "__main__":
    check_safe_zone()
    write_vectors()
    write_rasters()
