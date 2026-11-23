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


def panel(az, el, az_c, el_c, half_w, half_h, feather, falloff=0.35):
    """A rectangular softbox in (azimuth, elevation) degrees with feathered edges and a
    subtle centre-weighted falloff like a baffled studio softbox, 0..1."""
    d_az = abs((az - az_c + 180) % 360 - 180)
    d_el = abs(el - el_c)
    mask = (1 - smooth(half_w - feather, half_w + feather, d_az)) * \
           (1 - smooth(half_h - feather, half_h + feather, d_el))
    if mask <= 0.0:
        return 0.0
    u = min(1.0, d_az / max(half_w, 1e-3))
    v = min(1.0, d_el / max(half_h, 1e-3))
    core = 1.0 - falloff * (0.55 * u * u + 0.45 * v * v)
    return mask * max(0.0, core)


# (azimuth centre, elevation centre, half width, half height, feather, rgb radiance)
# Azimuth 0 is +Z (the keyway face), 90 is +X (the cutaway side).
LIGHTS = [
    (52, 40, 32, 18, 6, (17.5, 16.4, 14.8)),     # warm key softbox, front-right and high
    (-68, 22, 14, 34, 6, (4.4, 5.2, 6.4)),       # tall cool fill strip on the fluted left side
    (12, 80, 180, 7, 4, (9.2, 9.2, 9.5)),        # overhead boom strip: long clean arch highlights
    (162, 28, 24, 15, 6, (8.5, 7.3, 5.8)),       # warm rear rim kicker for silhouette separation
    (102, 12, 14, 20, 6, (3.0, 2.95, 2.9)),      # cutaway fill card into the pin chambers
    (-12, 34, 12, 14, 6, (3.2, 3.25, 3.4)),      # front keyway catchlight for the brass plug face
]

# Negative-fill black flags: dark vertical cards that carve crisp dark-to-bright specular
# roll-off on cylindrical metals (pins, plug, shackle) instead of flat grey reflections.
FLAGS = [
    (18, 12, 11, 24, 5, 0.72),
    (132, 16, 12, 24, 5, 0.70),
    (-125, 14, 14, 22, 6, 0.65),
]


def radiance(az, el):
    if el < 0:
        # Dark matte studio sweep below the horizon so downward metal bevels hold rich contrast.
        base = 0.08 + 0.24 * smooth(-45, 0, el)
        r, g, b = base * 0.96, base * 0.99, base * 1.05
    else:
        # Calibrated light-tent cyclorama: bright enough to keep bare metal alive, dark enough
        # for the softboxes and flags to read with luxury product-photography contrast.
        base = 0.26 + 0.42 * smooth(0, 72, el)
        # Subtle warm-right / cool-left studio bounce gradient.
        warmth = 0.06 * math.sin(math.radians(az))
        r = base * (0.98 + warmth)
        g = base * 0.99
        b = base * (1.03 - warmth)
        for az_c, el_c, half_w, half_h, feather, strength in FLAGS:
            k = panel(az, el, az_c, el_c, half_w, half_h, feather, falloff=0.0)
            if k > 0:
                atten = 1.0 - strength * k
                r *= atten
                g *= atten
                b *= atten
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
