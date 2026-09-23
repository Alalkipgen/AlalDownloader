#!/usr/bin/env python3
"""Alal Downloader launcher icon "Neon Outline": a glowing download arrow inside a neon square.

The mark is stroke-only geometry, so the glow is built by drawing each stroke several
times with a growing width and a falling alpha. The adaptive background, the adaptive
foreground, the Android 13 monochrome layer and the pre-API-26 vector all come from the
same numbers.

Writes app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml and
app/src/main/res/mipmap-anydpi/ic_launcher{,_round}.xml. Pure standard library.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"

SAFE_CENTER, SAFE_RADIUS = 54.0, 33.0   # Android adaptive-icon safe circle

FRAME = (30.0, 30.0, 78.0, 78.0)        # neon square bounds
FRAME_RADIUS = 15.0
STEM_TOP, STEM_BOTTOM = 40.0, 64.0      # arrow shaft
HEAD_HALF, HEAD_TOP, HEAD_TIP = 10.5, 54.0, 65.6

# Glow is four passes of the same path: haze, bloom, neon core, hot centre line.
GLOW = (
    ("#1CC084FC", 11.0),
    ("#45C084FC", 6.6),
    ("#FFA855F7", 4.0),
    ("#FFF1E4FF", 1.6),
)
MONO = "#FFFFFFFF"

BACKDROP = (("0", "#FF1B1033"), ("0.55", "#FF0C0913"), ("1", "#FF050507"))


def number(value):
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def frame_path():
    x0, y0, x1, y1 = FRAME
    r = FRAME_RADIUS
    n = number
    return (
        f"M{n(x0 + r)},{n(y0)}H{n(x1 - r)}"
        f"A{n(r)},{n(r)} 0 0 1 {n(x1)},{n(y0 + r)}V{n(y1 - r)}"
        f"A{n(r)},{n(r)} 0 0 1 {n(x1 - r)},{n(y1)}H{n(x0 + r)}"
        f"A{n(r)},{n(r)} 0 0 1 {n(x0)},{n(y1 - r)}V{n(y0 + r)}"
        f"A{n(r)},{n(r)} 0 0 1 {n(x0 + r)},{n(y0)}Z"
    )


def stem_path():
    n = number
    return f"M{n(SAFE_CENTER)},{n(STEM_TOP)}V{n(STEM_BOTTOM)}"


def head_path():
    n = number
    return (f"M{n(SAFE_CENTER - HEAD_HALF)},{n(HEAD_TOP)}"
            f"L{n(SAFE_CENTER)},{n(HEAD_TIP)}L{n(SAFE_CENTER + HEAD_HALF)},{n(HEAD_TOP)}")


SHAPES = (frame_path(), stem_path(), head_path())

# Every extreme point of the mark, used to prove the art stays in the safe circle.
CORNERS = [
    (FRAME[0] + FRAME_RADIUS, FRAME[1] + FRAME_RADIUS), (FRAME[2] - FRAME_RADIUS, FRAME[1] + FRAME_RADIUS),
    (FRAME[0] + FRAME_RADIUS, FRAME[3] - FRAME_RADIUS), (FRAME[2] - FRAME_RADIUS, FRAME[3] - FRAME_RADIUS),
    (FRAME[0], SAFE_CENTER), (FRAME[2], SAFE_CENTER), (SAFE_CENTER, FRAME[1]), (SAFE_CENTER, FRAME[3]),
    (SAFE_CENTER - HEAD_HALF, HEAD_TOP), (SAFE_CENTER + HEAD_HALF, HEAD_TOP), (SAFE_CENTER, HEAD_TIP),
]
assert all((x - SAFE_CENTER) ** 2 + (y - SAFE_CENTER) ** 2 < SAFE_RADIUS ** 2 for x, y in CORNERS)

HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"%s\n'
        '    android:width="108dp" android:height="108dp"\n'
        '    android:viewportWidth="108" android:viewportHeight="108">\n')


def stroke(data, colour, width):
    return (f'    <path android:pathData="{data}" android:strokeColor="{colour}"'
            f' android:strokeWidth="{number(width)}" android:strokeLineCap="round"'
            f' android:strokeLineJoin="round" />\n')


def neon_layers(passes=GLOW):
    return "".join(stroke(data, colour, width) for colour, width in passes for data in SHAPES)


def main():
    drawable = RES / "drawable"
    drawable.mkdir(parents=True, exist_ok=True)

    stops = "".join(f'                <item android:offset="{offset}" android:color="{colour}" />\n'
                    for offset, colour in BACKDROP)
    (drawable / "ic_launcher_background.xml").write_text(
        (HEAD % ' xmlns:aapt="http://schemas.android.com/aapt"') +
        '    <path android:pathData="M0,0h108v108h-108z">\n'
        '        <aapt:attr name="android:fillColor">\n'
        '            <gradient android:type="radial" android:centerX="54" android:centerY="48" android:gradientRadius="72">\n'
        + stops +
        '            </gradient>\n'
        '        </aapt:attr>\n'
        '    </path>\n</vector>\n')

    (drawable / "ic_launcher_foreground.xml").write_text((HEAD % "") + neon_layers() + "</vector>\n")
    (drawable / "ic_launcher_monochrome.xml").write_text(
        (HEAD % "") + neon_layers(((MONO, 5.0), (MONO, 2.0))) + "</vector>\n")

    # Pre-API-26 launchers get the same mark on its own black rounded square.
    legacy = ((HEAD % "") +
              '    <path android:fillColor="#FF08070C"\n'
              '        android:pathData="M25,3H83A22,22 0 0 1 105,25V83A22,22 0 0 1 83,105H25A22,22 0 0 1 3,83V25A22,22 0 0 1 25,3Z" />\n'
              '    <group android:pivotX="54" android:pivotY="54" android:scaleX="1.12" android:scaleY="1.12">\n'
              + neon_layers().replace("    <path", "        <path") +
              '    </group>\n</vector>\n')
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (RES / "mipmap-anydpi" / name).write_text(legacy)

    reach = max(((x - SAFE_CENTER) ** 2 + (y - SAFE_CENTER) ** 2) ** 0.5 for x, y in CORNERS)
    print(f"Wrote 3 adaptive vectors and 2 legacy vectors; safe circle PASS (reach {reach:.1f} of {SAFE_RADIUS:.0f})")


if __name__ == "__main__":
    main()
