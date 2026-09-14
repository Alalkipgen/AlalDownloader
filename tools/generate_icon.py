#!/usr/bin/env python3
"""Alal Downloader launcher icon: white download arrow dropping into an amber tray."""
from pathlib import Path
import math

OUT = Path(__file__).resolve().parent
BLUE, WHITE, ORANGE = "#1C32A1", "#FFFFFF", "#FD6818"

SAFE_CENTER, SAFE_RADIUS = 54.0, 33.0

# arrow
SHAFT = (46.5, 23.0, 61.5, 45.0, 4.0)          # x0, y0, x1, y1, radius
HEAD = [(29.0, 40.5), (79.0, 40.5), (54.0, 64.0)]
HEAD_RADIUS = 5.0
# tray
TRAY = (33.0, 56.5, 75.0, 79.0)                # x0, ytop, x1, ybottom
TRAY_THICKNESS = 7.5
TRAY_RADIUS = 10.0


def n(v):
    t = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if t in ("", "-0") else t


def round_rect(geometry):
    x0, y0, x1, y1, r = geometry
    return (f"M{n(x0 + r)},{n(y0)}H{n(x1 - r)}A{n(r)},{n(r)} 0 0 1 {n(x1)},{n(y0 + r)}"
            f"V{n(y1 - r)}A{n(r)},{n(r)} 0 0 1 {n(x1 - r)},{n(y1)}H{n(x0 + r)}"
            f"A{n(r)},{n(r)} 0 0 1 {n(x0)},{n(y1 - r)}V{n(y0 + r)}A{n(r)},{n(r)} 0 0 1 {n(x0 + r)},{n(y0)}Z")


def rounded_polygon(points, radius):
    parts = []
    count = len(points)
    for index, (x, y) in enumerate(points):
        px, py = points[(index - 1) % count]
        nx, ny = points[(index + 1) % count]
        def step(ax, ay):
            dx, dy = ax - x, ay - y
            length = math.hypot(dx, dy)
            take = min(radius, length / 2)
            return x + dx / length * take, y + dy / length * take
        ix, iy = step(px, py)
        ox, oy = step(nx, ny)
        parts.append((ix, iy, x, y, ox, oy))
    data = f"M{n(parts[0][0])},{n(parts[0][1])}"
    for index, (ix, iy, vx, vy, ox, oy) in enumerate(parts):
        if index:
            data += f"L{n(ix)},{n(iy)}"
        data += f"Q{n(vx)},{n(vy)} {n(ox)},{n(oy)}"
    return data + "Z"


def tray_path():
    x0, ytop, x1, ybot = TRAY
    t, r = TRAY_THICKNESS, TRAY_RADIUS
    ir = max(r - t, 2.0)
    ix0, ix1, iybot = x0 + t, x1 - t, ybot - t
    return (
        f"M{n(x0)},{n(ytop)}"
        f"V{n(ybot - r)}A{n(r)},{n(r)} 0 0 0 {n(x0 + r)},{n(ybot)}"
        f"H{n(x1 - r)}A{n(r)},{n(r)} 0 0 0 {n(x1)},{n(ybot - r)}"
        f"V{n(ytop)}H{n(ix1)}"
        f"V{n(iybot - ir)}A{n(ir)},{n(ir)} 0 0 1 {n(ix1 - ir)},{n(iybot)}"
        f"H{n(ix0 + ir)}A{n(ir)},{n(ir)} 0 0 1 {n(ix0)},{n(iybot - ir)}"
        f"V{n(ytop)}Z"
    )


def shapes():
    return [
        (WHITE, round_rect(SHAFT), None),
        (WHITE, rounded_polygon(HEAD, HEAD_RADIUS), None),
        (ORANGE, tray_path(), "evenOdd"),
    ]


def check():
    corners = [(SHAFT[0], SHAFT[1]), (SHAFT[2], SHAFT[1]), (SHAFT[0], SHAFT[3]), (SHAFT[2], SHAFT[3])]
    corners += HEAD
    x0, ytop, x1, ybot = TRAY
    corners += [(x0, ytop), (x1, ytop), (x0, ybot), (x1, ybot)]
    for x, y in corners:
        assert (x - SAFE_CENTER) ** 2 + (y - SAFE_CENTER) ** 2 < SAFE_RADIUS ** 2, (x, y)


def main():
    check()
    body = "".join(
        f'    <path android:fillColor="{colour}"' + (f' android:fillType="{fill}"' if fill else "") +
        f'\n        android:pathData="{data}" />\n'
        for colour, data, fill in shapes()
    )
    (OUT / "ic_launcher_foreground.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp" android:height="108dp"\n'
        '    android:viewportWidth="108" android:viewportHeight="108">\n' + body + '</vector>\n')
    (OUT / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp" android:height="108dp"\n'
        '    android:viewportWidth="108" android:viewportHeight="108">\n'
        f'    <path android:fillColor="{BLUE}" android:pathData="M0,0h108v108h-108z" />\n'
        '</vector>\n')
    (OUT / "legacy_ic_launcher.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Pre-API-26 launcher icon: the adaptive mark on its own rounded blue square. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp" android:height="108dp"\n'
        '    android:viewportWidth="108" android:viewportHeight="108">\n'
        f'    <path android:fillColor="{BLUE}"\n'
        '        android:pathData="' + round_rect((3.0, 3.0, 105.0, 105.0, 22.0)) + '" />\n'
        '    <group android:pivotX="54" android:pivotY="54" android:scaleX="1.34" android:scaleY="1.34">\n'
        + "".join(
            f'        <path android:fillColor="{colour}"' + (f' android:fillType="{fill}"' if fill else "") +
            f'\n            android:pathData="{data}" />\n'
            for colour, data, fill in shapes()) +
        '    </group>\n</vector>\n')

    svg = ['<svg xmlns="http://www.w3.org/2000/svg" width="1296" height="432" viewBox="0 0 324 108">']
    svg.append('<defs><clipPath id="sq"><rect x="0" y="0" width="108" height="108" rx="24"/></clipPath>'
               '<clipPath id="ci"><circle cx="162" cy="54" r="54"/></clipPath>'
               '<clipPath id="none"><rect x="216" y="0" width="108" height="108"/></clipPath></defs>')
    for offset, clip in ((0, "sq"), (108, "ci"), (216, "none")):
        svg.append(f'<g clip-path="url(#{clip})"><g transform="translate({offset},0)">')
        legacy = clip == "none"
        if legacy:
            svg.append(f'<path fill="{BLUE}" d="{round_rect((3.0, 3.0, 105.0, 105.0, 22.0))}"/>')
            svg.append('<g transform="translate(54,54) scale(1.34) translate(-54,-54)">')
        else:
            svg.append(f'<rect x="0" y="0" width="108" height="108" fill="{BLUE}"/>')
        for colour, data, fill in shapes():
            rule = ' fill-rule="evenodd"' if fill else ""
            svg.append(f'<path fill="{colour}"{rule} d="{data}"/>')
        if legacy:
            svg.append('</g>')
        svg.append('</g></g>')
    svg.append('</svg>')
    (OUT / "preview.svg").write_text("\n".join(svg))
    print("written")


if __name__ == "__main__":
    main()
