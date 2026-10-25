"""Renders store/icon-512.png (the Play Store icon) from the same shapes as the launcher icon
(app/src/main/res/drawable/ic_launcher_foreground.xml). Pure Python, no dependencies.
Run: python store/make_icon.py"""
import math
import os
import struct
import zlib

SIZE = 512
SS = 3                                 # supersampling per axis
ZOOM = 1.3                             # the store icon fills more of the square than the launcher one
BG = (10, 10, 10)
SHAFT = (250, 250, 250)
HEAD = (255, 125, 12)
HALF = 3.5                             # half the 7-unit stroke

def quad(p0, p1, p2, n=16):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]) for t in [i / n for i in range(n + 1)]]

# M30,78 L30,62 Q30,54 38,54 L60,54 Q68,54 68,46 L68,42
pts = [(30, 78), (30, 62)] + quad((30, 62), (30, 54), (38, 54))[1:] + [(60, 54)] + quad((60, 54), (68, 54), (68, 46))[1:] + [(68, 42)]
segs = list(zip(pts, pts[1:]))
tri = [(57, 43), (79, 43), (68, 24)]

def dist_seg(px, py, a, b):
    ax, ay = a; bx, by = b
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))

def in_tri(px, py):
    s = []
    for i in range(3):
        a, b = tri[i], tri[(i + 1) % 3]
        s.append((b[0] - a[0]) * (py - a[1]) - (b[1] - a[1]) * (px - a[0]))
    return all(v >= 0 for v in s) or all(v <= 0 for v in s)

def sample(x, y):
    # screen pixel -> 108 x 108 icon space, zoomed around the centre
    px = (x / SIZE - .5) * 108 / ZOOM + 54
    py = (y / SIZE - .5) * 108 / ZOOM + 54
    if in_tri(px, py):
        return HEAD
    if py > 20 and any(dist_seg(px, py, a, b) <= HALF for a, b in segs):
        return SHAFT
    return BG

rows = []
for y in range(SIZE):
    row = bytearray([0])
    for x in range(SIZE):
        r = g = b = 0
        for sy in range(SS):
            for sx in range(SS):
                c = sample(x + (sx + .5) / SS, y + (sy + .5) / SS)
                r += c[0]; g += c[1]; b += c[2]
        n = SS * SS
        row += bytes((r // n, g // n, b // n))
    rows.append(bytes(row))

def chunk(tag, data):
    c = struct.pack('>I', len(data)) + tag + data
    return c + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)

png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', SIZE, SIZE, 8, 2, 0, 0, 0)) + \
    chunk(b'IDAT', zlib.compress(b''.join(rows), 9)) + chunk(b'IEND', b'')
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'icon-512.png')
open(out, 'wb').write(png)
print('wrote', out, len(png), 'bytes')
