"""
Paints a product-photography lighting studio as a Radiance .hdr equirect for Filament's IBL:
a light tent, one large key softbox, a cool fill strip, an overhead strip and a rear kicker.
Polished metal has no colour of its own to show, so what it reflects is the whole picture.

    python3 tools/make_studio_hdr.py

Writes app/src/main/assets/environments/studio.hdr (flat RGBE, -Y +X, 1024 x 512).
"""

import math
import os
import struct

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "environments", "studio.hdr")
W, H = 1024, 512


def smooth(edge0, edge1, x):
    t = max(0.0, min(1.0, (x - edge0) / (edge1 - edge0)))
    return t * t * (3 - 2 * t)


def panel(az, el, az_c, el_c, half_w, half_h, feather):
    """A rectangular softbox in (azimuth, elevation) degrees with feathered edges, 0..1."""
    d_az = abs((az - az_c + 180) % 360 - 180)
    d_el = abs(el - el_c)
    return (1 - smooth(half_w - feather, half_w + feather, d_az)) * \
           (1 - smooth(half_h - feather, half_h + feather, d_el))


# (azimuth centre, elevation centre, half width, half height, feather, rgb radiance)
# Azimuth 0 is +Z (the keyway face), 90 is +X (the cutaway side).
LIGHTS = [
    (55, 42, 34, 20, 6, (15.0, 14.4, 13.4)),     # key softbox, front-right and high
    (-70, 18, 16, 30, 7, (3.2, 3.6, 4.2)),       # cool fill strip on the left
    (0, 84, 180, 5, 4, (7.0, 7.0, 7.0)),         # overhead ring strip: long, clean highlights
    (165, 30, 22, 14, 6, (5.5, 5.0, 4.4)),       # warm rear kicker for rim separation
    (115, 8, 10, 22, 5, (2.4, 2.4, 2.5)),        # low side card lighting into the cutaway
]


def radiance(az, el):
    if el < 0:
        # The floor: a mid-grey sweep that brightens toward the horizon.
        base = 0.16 + 0.34 * smooth(-50, 0, el)
        r = g = b = base
    else:
        # Walls and ceiling of a light tent. Bare metal is a mirror: with nothing bright
        # around it, it renders black however it is lit.
        base = 0.55 + 0.75 * smooth(0, 75, el)
        r, g, b = base * 0.97, base * 0.99, base * 1.03
    for az_c, el_c, half_w, half_h, feather, (lr, lg, lb) in LIGHTS:
        k = panel(az, el, az_c, el_c, half_w, half_h, feather)
        if k > 0:
            r += lr * k
            g += lg * k
            b += lb * k
    return r, g, b


def rgbe(r, g, b):
    m = max(r, g, b)
    if m < 1e-32:
        return b"\0\0\0\0"
    f, e = math.frexp(m)
    s = f * 256.0 / m
    return struct.pack("BBBB", min(255, int(r * s)), min(255, int(g * s)), min(255, int(b * s)), e + 128)


os.makedirs(os.path.dirname(OUT), exist_ok=True)
rows = bytearray()
for y in range(H):
    el = 90 - (y + 0.5) / H * 180
    for x in range(W):
        # Filament's equirect convention puts -Z at the image centre; azimuth 0 here is +Z.
        az = (x + 0.5) / W * 360 - 180 + 180
        rows += rgbe(*radiance(az, el))
with open(OUT, "wb") as f:
    f.write(b"#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n")
    f.write(f"-Y {H} +X {W}\n".encode())
    f.write(rows)
print(f"WROTE {OUT} ({os.path.getsize(OUT) / 1024:.0f} KiB)")
