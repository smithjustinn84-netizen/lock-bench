"""
Generates the Lock Bench padlocks: one cutaway .glb per lock, with procedural PBR surface maps.

    trainer        cutaway_padlock.glb        anodized training block: 4 pins, two spools
    master3        master_no3_padlock.glb     laminated steel in the style of a Master Lock No. 3:
                                              4 standard pins, nothing to trip on
    american1100   american_1100_padlock.glb  solid aluminium in the style of an American Lock 1100:
                                              5 pins, serrated and spool drivers

Run headless. With no names it builds every lock:
    /Applications/Blender.app/Contents/MacOS/Blender --background --python tools/build_padlock.py
    /Applications/Blender.app/Contents/MacOS/Blender --background --python tools/build_padlock.py -- master3

Add `--previews DIR` after `--` to render studio stills of each lock using studio.hdr.

Or build one into a running Blender, to look at it and turn it over. It goes into a scene of
its own ("LockBench master3") and leaves whatever else is open alone:
    import runpy
    lockbench = runpy.run_path("/path/to/tools/build_padlock.py")
    lockbench["build"]("master3", live=True)

Everything below is authored in glTF space so the numbers match what the Kotlin side sees:
    +Y up, +Z out of the keyway face (toward the viewer), +X is the cutaway side.
    1 unit = 0.1 m, so 0.01 units = 1 mm.

Node contract (names are looked up from Kotlin, see LockRig.kt). N is the lock's pin count:
    Body_Cutaway                      housing, sectioned on the +X side
    Shackle                           origin on the heel axis at the top of the body
    Core_Plug                         origin on the cylinder axis; rotates about Z
      KeyPin_1..N                     children of the plug; origin at the tip; lift along Y
      TensionWrench                   child of the plug; rides the rotation
    DriverPin_1..N                    origin at the base; lift along Y
    Spring_1..N                       origin at the top of the bible; scale Y to compress
    HookPick                          origin at the hook tip
    Bench                             shadow-catching locksmith pinning mat

The mechanical numbers Kotlin needs travel in glTF node `extras`, so this file is the only
place they are written down.
"""

import copy
import json
import math
import os
import struct
import sys

import bmesh
import bpy
import numpy as np
from mathutils import Matrix, Vector

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS = os.path.join(ROOT, "app", "src", "main", "assets", "models")
STUDIO_HDR = os.path.join(ROOT, "app", "src", "main", "assets", "environments", "studio.hdr")

# glTF (x, y, z) -> Blender (x, -z, y). A proper rotation, so winding survives.
TO_BLENDER = Matrix(((1, 0, 0, 0), (0, 0, -1, 0), (0, 1, 0, 0), (0, 0, 0, 1)))
FROM_BLENDER = TO_BLENDER.inverted()


def B(v):
    return Vector((v[0], -v[2], v[1]))


# ── Dimensions ────────────────────────────────────────────────────────────────────────────────
#
# These are the trainer's. `configure(lock)` lays a lock's own numbers from LOCKS over them
# before anything is built, so every builder below just reads the names.

FILE = "cutaway_padlock.glb"
BODY_STYLE = "trainer"                 # which housing to build: see BODY_BUILDERS
BODY_X, BODY_Z = 0.22, 0.13            # half extents
BODY_Y0, BODY_Y1 = -0.11, 0.29
PLUG_R = 0.070
BORE_R = 0.0715
PLUG_Z0, PLUG_Z1 = -0.125, 0.135
PIN_R = 0.016
CHAMBER_R = 0.0175
CHAMBER_TOP = 0.25
PIN_Z = [0.066, 0.022, -0.022, -0.066]  # pin 1 sits nearest the keyway face
PIN_REST_Y = -0.030                    # key pin tip height with no pick under it
KEY_PIN_LEN = [0.068, 0.052, 0.078, 0.060]
DRIVER_LEN = 0.060
SPOOLS = {2, 3}                        # 1-based pins with a spool driver
SERRATED = set()                       # 1-based pins with a serrated driver
KEYWAY_HALF_W = 0.011
KEYWAY_Y0, KEYWAY_Y1 = -0.064, -0.012
# Stepped paracentric-style keyway, (x, y). Whatever the warding, the slot |x| < 0.009 stays
# clear from KEYWAY_Y0 to KEYWAY_Y1: the wrench and the pick live there.
KEYWAY_OUTLINE = [
    (-KEYWAY_HALF_W, KEYWAY_Y0),
    (KEYWAY_HALF_W, KEYWAY_Y0),
    (KEYWAY_HALF_W, -0.052),
    (0.018, -0.050),
    (0.018, -0.041),
    (0.009, -0.039),
    (0.009, KEYWAY_Y1),
    (-0.009, KEYWAY_Y1),
    (-0.009, -0.022),
    (-0.019, -0.024),
    (-0.019, -0.034),
    (-KEYWAY_HALF_W, -0.036),
]
WINDOW_Z = 0.100                       # cutaway window half length
WINDOW_TOP = CHAMBER_TOP + 0.015       # how high the window is cut
SHACKLE_X = 0.15
SHACKLE_R = 0.032
SHACKLE_ARC_Y = 0.40
SHACKLE_HEEL_Y = 0.05
SHACKLE_TOE_Y = 0.235
PICK_EXTRA = 0.0                       # extra shank on the pick, for a longer keyway

_TRAINER = {k: copy.deepcopy(v) for k, v in dict(globals()).items()
            if k.isupper() and k not in ("ROOT", "MODELS", "STUDIO_HDR", "TO_BLENDER", "FROM_BLENDER")}

LOCKS = {
    "trainer": {},
    # Laminated steel plates riveted into a stack, a short shackle, and four plain pin stacks
    # in an open keyway. Nothing in it fights back: the lock to learn on.
    "master3": dict(
        FILE="master_no3_padlock.glb",
        BODY_STYLE="laminated",
        BODY_X=0.235, BODY_Z=0.13,
        KEY_PIN_LEN=[0.058, 0.070, 0.050, 0.064],
        SPOOLS=set(),
        SHACKLE_X=0.145,
        SHACKLE_R=0.034,
        SHACKLE_ARC_Y=0.335,
        KEYWAY_OUTLINE=[
            (-KEYWAY_HALF_W, KEYWAY_Y0),
            (KEYWAY_HALF_W, KEYWAY_Y0),
            (KEYWAY_HALF_W, -0.046),
            (0.016, -0.044),
            (0.016, -0.036),
            (0.0105, -0.034),
            (0.0105, KEYWAY_Y1),
            (-0.0105, KEYWAY_Y1),
            (-0.0105, -0.030),
            (-KEYWAY_HALF_W, -0.032),
        ],
    ),
    # A solid block of red anodized aluminium, taller than it is wide, with five stacks packed
    # closer together. Three serrated drivers and two spools: every one of them lies.
    "american1100": dict(
        FILE="american_1100_padlock.glb",
        BODY_STYLE="block",
        BODY_X=0.19, BODY_Z=0.15,
        BODY_Y1=0.34,
        PLUG_Z0=-0.145, PLUG_Z1=0.155,
        PIN_Z=[0.080, 0.040, 0.0, -0.040, -0.080],
        KEY_PIN_LEN=[0.060, 0.074, 0.052, 0.068, 0.046],
        SPOOLS={2, 4},
        SERRATED={1, 3, 5},
        WINDOW_Z=0.118,
        WINDOW_TOP=0.300,
        SHACKLE_X=0.125,
        SHACKLE_ARC_Y=0.47,
        SHACKLE_HEEL_Y=0.10,
        SHACKLE_TOE_Y=0.285,
        PICK_EXTRA=0.040,
        KEYWAY_OUTLINE=[
            (-KEYWAY_HALF_W, KEYWAY_Y0),
            (KEYWAY_HALF_W, KEYWAY_Y0),
            (KEYWAY_HALF_W, -0.056),
            (0.019, -0.054),
            (0.019, -0.047),
            (0.009, -0.045),
            (0.009, -0.030),
            (0.015, -0.028),
            (0.015, -0.021),
            (0.009, -0.019),
            (0.009, KEYWAY_Y1),
            (-0.009, KEYWAY_Y1),
            (-0.009, -0.024),
            (-0.018, -0.026),
            (-0.018, -0.036),
            (-0.009, -0.038),
            (-0.009, -0.046),
            (-KEYWAY_HALF_W, -0.048),
        ],
    ),
}

# Derived from the above by configure().
SHEAR_Y = PLUG_R                       # top of the plug, where plug meets bible
PICK_FULCRUM_Z = PLUG_Z1               # the pick levers on the keyway mouth

# The scene being built into, and the datablocks that were there before it was.
SCENE = None
_EXISTING = {}


def configure(lock):
    """Lays `lock`'s dimensions over the trainer's."""
    global SHEAR_Y, PICK_FULCRUM_Z
    if lock not in LOCKS:
        raise SystemExit(f"no lock called '{lock}': choose from {', '.join(LOCKS)}")
    globals().update(copy.deepcopy(_TRAINER))
    globals().update(copy.deepcopy(LOCKS[lock]))
    SHEAR_Y = PLUG_R
    PICK_FULCRUM_Z = PLUG_Z1
    assert len(PIN_Z) == len(KEY_PIN_LEN), "every pin needs a position and a key pin"
    assert not SPOOLS & SERRATED, "a driver is a spool or serrated, not both"


# ── Scenes: a clean file when headless, a scene of its own in a live session ──────────────────

_DATA = ("objects", "meshes", "materials", "images", "lights")


def _stashed(lock, name):
    return f"{lock}/{name}"


def begin(lock, live):
    """Opens the scene to build into. Headless that is a fresh file. Live it is a new scene,
    and nothing that was open is touched."""
    global SCENE, _EXISTING
    if not live:
        bpy.ops.wm.read_factory_settings(use_empty=True)
        SCENE = bpy.context.scene
        _EXISTING = {}
        return
    name = f"LockBench {lock}"
    old = bpy.data.scenes.get(name)
    if old is not None:
        for obj in list(old.collection.all_objects):
            bpy.data.objects.remove(obj)
        bpy.data.scenes.remove(old)
    # What the last build of this lock left behind once its objects went.
    for kind in _DATA[1:]:
        coll = getattr(bpy.data, kind)
        for block in list(coll):
            if block.name.startswith(f"{lock}/") and block.users == 0:
                coll.remove(block)
    SCENE = bpy.data.scenes.new(name)
    window = bpy.context.window or next(iter(bpy.context.window_manager.windows), None)
    if window is not None:
        window.scene = SCENE
    _EXISTING = {kind: {b.name for b in getattr(bpy.data, kind)} for kind in _DATA}


def stash(lock):
    """Datablock names are one namespace per file, and the exporter needs each node to have
    exactly its contract name. Once a live build is exported its blocks are renamed out of the
    way ("master3/Core_Plug"), so the next lock can use the bare names."""
    for kind in _DATA:
        for block in getattr(bpy.data, kind):
            if block.name not in _EXISTING.get(kind, ()) and "/" not in block.name:
                block.name = _stashed(lock, block.name)


def _override(**kw):
    """A context for operators that works headless and from a live session's timer."""
    ctx = dict(scene=SCENE, view_layer=SCENE.view_layers[0])
    window = bpy.context.window or next(iter(bpy.context.window_manager.windows), None)
    if window is not None:
        ctx["window"] = window
    ctx.update(kw)
    return bpy.context.temp_override(**ctx)



# ── Procedural PBR texture generation (numpy -> packed Blender images) ────────────────────────

def _tile_smooth(arr, radius_y=1, radius_x=1, passes=2):
    """Separable periodic box blur on a 2D numpy array so every texture tiles seamlessly."""
    out = arr.astype(np.float32, copy=True)
    for _ in range(passes):
        if radius_y > 0:
            acc = np.zeros_like(out)
            for k in range(-radius_y, radius_y + 1):
                acc += np.roll(out, k, axis=0)
            out = acc / (2 * radius_y + 1)
        if radius_x > 0:
            acc = np.zeros_like(out)
            for k in range(-radius_x, radius_x + 1):
                acc += np.roll(out, k, axis=1)
            out = acc / (2 * radius_x + 1)
    return out


def _height_to_normal(h, strength=1.0):
    """Convert a periodic 2D heightfield in [0, 1] to an OpenGL tangent-space normal map [0, 1]."""
    dx = (np.roll(h, -1, axis=1) - np.roll(h, 1, axis=1)) * 0.5 * strength
    dy = (np.roll(h, -1, axis=0) - np.roll(h, 1, axis=0)) * 0.5 * strength
    nx = -dx
    ny = -dy
    nz = np.ones_like(h)
    inv = 1.0 / np.sqrt(nx * nx + ny * ny + nz * nz + 1e-12)
    return np.stack((nx * inv * 0.5 + 0.5, ny * inv * 0.5 + 0.5, nz * inv * 0.5 + 0.5), axis=-1)


def _make_image(name, rgb, colorspace="Non-Color"):
    """Create a packed Blender Image from an (H, W, 3) float32 array in [0, 1]."""
    h, w, _ = rgb.shape
    rgba = np.ones((h, w, 4), dtype=np.float32)
    rgba[..., :3] = np.clip(rgb, 0.0, 1.0)
    img = bpy.data.images.new(name, width=w, height=h, alpha=False, float_buffer=False)
    img.colorspace_settings.name = colorspace
    img.pixels.foreach_set(rgba.ravel())
    img.pack()
    return img


def make_anodized_maps(size=256):
    """Bead-blasted Type-III anodized aluminum: fine isotropic micro-grain."""
    rng = np.random.default_rng(101)
    fine = rng.standard_normal((size, size)).astype(np.float32)
    med = _tile_smooth(rng.standard_normal((size, size)).astype(np.float32), 2, 2, passes=2)
    h = 0.55 * fine + 0.45 * med
    h = (h - h.min()) / (h.max() - h.min() + 1e-8)
    normal = _height_to_normal(h, strength=1.05)
    # Normalized roughness map in [0.68, 1.0] multiplied by material roughnessFactor in glTF.
    rough = np.clip(0.84 + (h - 0.5) * 0.28, 0.65, 1.0)
    gray = np.stack((rough, rough, rough), axis=-1)
    return _make_image("tex_anodized_rgh", gray), _make_image("tex_anodized_nrm", normal)


def make_milled_maps(size=256):
    """CNC-milled raw aluminum: crisp fine directional fly-cutter / end-mill machining lines."""
    rng = np.random.default_rng(202)
    # Tight horizontal CNC feed lines + fine isotropic micro-tooth, no low-frequency waviness.
    streaks = _tile_smooth(rng.standard_normal((size, size)).astype(np.float32), 0, 12, passes=3)
    bands = _tile_smooth(rng.standard_normal((size, 1)).astype(np.float32), 0, 0, passes=1)
    bands = np.repeat(bands, size, axis=1)
    fine = rng.standard_normal((size, size)).astype(np.float32) * 0.14
    h = 0.62 * streaks + 0.24 * bands + fine
    h = (h - h.min()) / (h.max() - h.min() + 1e-8)
    normal = _height_to_normal(h, strength=1.25)
    rough = np.clip(0.82 + (h - 0.5) * 0.30, 0.64, 1.0)
    gray = np.stack((rough, rough, rough), axis=-1)
    return _make_image("tex_milled_rgh", gray), _make_image("tex_milled_nrm", normal)


def make_brushed_metal_maps(size=256):
    """Shared lathe-turned / brushed metal grain (streaks along U, subtle turning bands along V).
    Shared across brass, pins, nickel silver, hardened steel, and tool steel using each
    material's glTF roughnessFactor and UV scale to differentiate polish."""
    rng = np.random.default_rng(301)
    streaks = _tile_smooth(rng.standard_normal((size, size)).astype(np.float32), 0, 14, passes=3)
    bands = _tile_smooth(rng.standard_normal((size, 1)).astype(np.float32), 1, 0, passes=1)
    bands = np.repeat(bands, size, axis=1)
    micro = rng.standard_normal((size, size)).astype(np.float32) * 0.10
    h = 0.58 * streaks + 0.32 * bands + micro
    h = (h - h.min()) / (h.max() - h.min() + 1e-8)
    normal = _height_to_normal(h, strength=1.15)
    rough = np.clip(0.80 + (h - 0.5) * 0.36, 0.58, 1.0)
    gray = np.stack((rough, rough, rough), axis=-1)
    return _make_image("tex_brushed_rgh", gray), _make_image("tex_brushed_nrm", normal)


def make_grip_maps(size=256):
    """Fine stippled polymer grip texture for the hook pick handle."""
    rng = np.random.default_rng(505)
    pebble = _tile_smooth(rng.standard_normal((size, size)).astype(np.float32), 1, 1, passes=2)
    fine = rng.standard_normal((size, size)).astype(np.float32) * 0.35
    h = pebble + fine
    h = (h - h.min()) / (h.max() - h.min() + 1e-8)
    normal = _height_to_normal(h, strength=2.0)
    rough = np.clip(0.85 + (h - 0.5) * 0.24, 0.70, 1.0)
    gray = np.stack((rough, rough, rough), axis=-1)
    return _make_image("tex_grip_rgh", gray), _make_image("tex_grip_nrm", normal)


def make_bench_maps(size=512):
    """Locksmith silicone pinning mat: deep obsidian-charcoal rubber with subtle technical grid,
    concentric calibration rings, brass cardinal ticks, and a soft perimeter vignette."""
    rng = np.random.default_rng(606)
    pebble = _tile_smooth(rng.standard_normal((size, size)).astype(np.float32), 1, 1, passes=1)
    pebble = (pebble - pebble.min()) / (pebble.max() - pebble.min() + 1e-8)

    lin = np.linspace(-0.75, 0.75, size, dtype=np.float32)
    gx, gz = np.meshgrid(lin, lin)
    r = np.sqrt(gx * gx + gz * gz)
    ang = np.arctan2(gz, gx)

    # Deep matte charcoal rubber fading smoothly toward the dark #0B0D10 backdrop near the rim.
    vig = 1.0 - np.clip((r - 0.18) / 0.56, 0.0, 1.0) ** 1.45
    base_v = 0.008 + 0.018 * vig + (pebble - 0.5) * 0.003
    col = np.stack((base_v * 0.90, base_v * 0.96, base_v * 1.12), axis=-1)

    # Subtle 10mm (0.10 unit) precision grid inside r < 0.62.
    inside = np.clip((0.62 - r) / 0.10, 0.0, 1.0)
    dx10 = np.abs(((gx + 0.05) % 0.10) - 0.05)
    dz10 = np.abs(((gz + 0.05) % 0.10) - 0.05)
    line_w = 0.0022
    grid = np.maximum(
        np.clip(1.0 - dx10 / line_w, 0.0, 1.0),
        np.clip(1.0 - dz10 / line_w, 0.0, 1.0),
    ) * inside

    axis_mask = np.maximum(
        np.clip(1.0 - np.abs(gx) / 0.0028, 0.0, 1.0),
        np.clip(1.0 - np.abs(gz) / 0.0028, 0.0, 1.0),
    ) * inside

    # Concentric technical reticle rings around the padlock.
    ring1 = np.clip(1.0 - np.abs(r - 0.34) / 0.0025, 0.0, 1.0)
    ring2 = np.clip(1.0 - np.abs(r - 0.54) / 0.0023, 0.0, 1.0)
    tick_ang = np.abs(((ang + math.pi / 24.0) % (math.pi / 12.0)) - math.pi / 24.0) * r
    ticks = np.clip(1.0 - tick_ang / 0.0024, 0.0, 1.0) * (r > 0.515) * (r < 0.542)
    card_ang = np.abs(((ang + math.pi / 4.0) % (math.pi / 2.0)) - math.pi / 4.0) * r
    cardinal = np.clip(1.0 - card_ang / 0.0038, 0.0, 1.0) * (r > 0.498) * (r < 0.546)

    hairline = np.clip(grid * 0.45 + axis_mask * 0.42 + ring1 * 0.72 + ring2 * 0.78 + ticks * 0.72, 0.0, 1.0)
    hair_rgb = np.array([0.095, 0.110, 0.138], dtype=np.float32)
    brass_rgb = np.array([0.42, 0.31, 0.13], dtype=np.float32)
    col = col * (1.0 - hairline[..., None] * 0.62) + hair_rgb * (hairline[..., None] * 0.62)
    col = col * (1.0 - cardinal[..., None] * 0.72) + brass_rgb * (cardinal[..., None] * 0.72)

    h = pebble * 0.85 - (ring1 + ring2) * 0.15
    normal = _height_to_normal(h, strength=1.10)
    rough = np.clip(0.96 + (pebble - 0.5) * 0.08 - hairline * 0.08, 0.86, 1.0)
    gray = np.stack((rough, rough, rough), axis=-1)

    return (
        _make_image("tex_bench_base", col, colorspace="sRGB"),
        _make_image("tex_bench_rgh", gray, colorspace="Non-Color"),
        _make_image("tex_bench_nrm", normal, colorspace="Non-Color"),
    )


# ── Mesh helpers (all in glTF space) ──────────────────────────────────────────────────────────

def lathe(bm, profile, segments, center=(0.0, 0.0, 0.0)):
    """Revolve [(radius, y), ...] around the Y axis through `center`. Caps both ends."""
    cx, cy, cz = center
    rings = []
    for r, y in profile:
        ring = []
        for k in range(segments):
            a = 2 * math.pi * k / segments
            ring.append(bm.verts.new((cx + r * math.cos(a), cy + y, cz + r * math.sin(a))))
        rings.append(ring)
    for lo, hi in zip(rings, rings[1:]):
        for k in range(segments):
            n = (k + 1) % segments
            bm.faces.new((lo[k], lo[n], hi[n], hi[k]))
    bm.faces.new(rings[0])
    bm.faces.new(list(reversed(rings[-1])))


def lathe_z(bm, profile, segments, center_xy=(0.0, 0.0)):
    """Revolve [(radius, z), ...] around the Z axis through `center_xy`. Caps both ends."""
    cx, cy = center_xy
    rings = []
    for r, z in profile:
        ring = []
        for k in range(segments):
            a = 2 * math.pi * k / segments
            ring.append(bm.verts.new((cx + r * math.cos(a), cy + r * math.sin(a), z)))
        rings.append(ring)
    for lo, hi in zip(rings, rings[1:]):
        for k in range(segments):
            n = (k + 1) % segments
            bm.faces.new((lo[k], lo[n], hi[n], hi[k]))
    bm.faces.new(rings[0])
    bm.faces.new(list(reversed(rings[-1])))


def cylinder_z(bm, radius, z0, z1, segments, center_xy=(0.0, 0.0)):
    """A capped cylinder whose axis runs along Z."""
    lathe_z(bm, [(radius, z0), (radius, z1)], segments, center_xy)


def box(bm, lo, hi):
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    v = [bm.verts.new(p) for p in (
        (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
        (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1))]
    faces = [bm.faces.new([v[i] for i in f]) for f in (
        (0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (2, 3, 7, 6), (1, 2, 6, 5), (0, 4, 7, 3))]
    return faces


def prism_yz(bm, outline, x0, x1):
    """Extrude a (z, y) outline along X."""
    a = [bm.verts.new((x0, y, z)) for z, y in outline]
    b = [bm.verts.new((x1, y, z)) for z, y in outline]
    n = len(outline)
    for k in range(n):
        m = (k + 1) % n
        bm.faces.new((a[k], a[m], b[m], b[k]))
    bm.faces.new(a)
    bm.faces.new(list(reversed(b)))


def prism_xy(bm, outline, z0, z1):
    """Extrude an (x, y) outline along Z."""
    a = [bm.verts.new((x, y, z0)) for x, y in outline]
    b = [bm.verts.new((x, y, z1)) for x, y in outline]
    n = len(outline)
    for k in range(n):
        m = (k + 1) % n
        bm.faces.new((a[k], a[m], b[m], b[k]))
    bm.faces.new(a)
    bm.faces.new(list(reversed(b)))


def tube(bm, path, radii, sides, ref_hint=None):
    """Sweep a circle (constant or per-point radius) along a polyline with parallel-transport
    frames. Caps both ends."""
    pts = [Vector(p) for p in path]
    if isinstance(radii, (int, float)):
        radii = [float(radii)] * len(pts)
    tangents = []
    for i in range(len(pts)):
        a = pts[max(i - 1, 0)]
        b = pts[min(i + 1, len(pts) - 1)]
        tangents.append((b - a).normalized())
    t0 = tangents[0]
    if ref_hint is not None:
        ref = Vector(ref_hint)
    else:
        ref = Vector((0, 0, 1)) if abs(t0.z) < 0.9 else Vector((1, 0, 0))
    normal = (ref - t0 * ref.dot(t0)).normalized()
    rings = []
    for i, (p, t, rad) in enumerate(zip(pts, tangents, radii)):
        if i > 0:
            normal = (normal - t * normal.dot(t)).normalized()
        binormal = t.cross(normal)
        ring = []
        for k in range(sides):
            a = 2 * math.pi * k / sides
            ring.append(bm.verts.new(p + rad * (math.cos(a) * normal + math.sin(a) * binormal)))
        rings.append(ring)
    for lo, hi in zip(rings, rings[1:]):
        for k in range(sides):
            n = (k + 1) % sides
            bm.faces.new((lo[k], lo[n], hi[n], hi[k]))
    bm.faces.new(rings[0])
    bm.faces.new(list(reversed(rings[-1])))


def sweep_section(bm, frames, section_fn):
    """Sweep a custom 2D cross-section [(u, v), ...] along [(pos, right, up, t), ...]."""
    rings = []
    for pos, right, up, t in frames:
        sec = section_fn(t)
        ring = [bm.verts.new(pos + u * right + v * up) for u, v in sec]
        rings.append(ring)
    sides = len(rings[0])
    for lo, hi in zip(rings, rings[1:]):
        for k in range(sides):
            n = (k + 1) % sides
            bm.faces.new((lo[k], lo[n], hi[n], hi[k]))
    bm.faces.new(rings[0])
    bm.faces.new(list(reversed(rings[-1])))


def chamfer_rect(hw, hh, ch):
    """8-point beveled rectangle cross-section."""
    return [
        (-hw + ch, -hh), (hw - ch, -hh),
        (hw, -hh + ch), (hw, hh - ch),
        (hw - ch, hh), (-hw + ch, hh),
        (-hw, hh - ch), (-hw, -hh + ch),
    ]


# ── Analytical UV projection (applied cleanly in glTF space after triangulation) ──────────────

def apply_uvs(obj, mode="box", scale=4.0, center=(0.0, 0.0, 0.0)):
    """Assign deterministic, distortion-free UVs in glTF space so tangent-space normal maps
    and metallic-roughness maps wrap cleanly with zero degenerate tangents."""
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    uv_layer = bm.loops.layers.uv.verify()
    cx, cy, cz = center

    for f in bm.faces:
        gn = FROM_BLENDER.to_3x3() @ f.normal
        ax, ay, az = abs(gn.x), abs(gn.y), abs(gn.z)

        if mode == "planar_bench":
            for loop in f.loops:
                p = FROM_BLENDER @ loop.vert.co
                loop[uv_layer].uv = ((p.x + 0.75) / 1.50, (p.z + 0.75) / 1.50)
        elif mode == "cyl_y":
            if ay > 0.82:
                for loop in f.loops:
                    p = FROM_BLENDER @ loop.vert.co
                    loop[uv_layer].uv = ((p.x - cx) * scale, (p.z - cz) * scale)
            else:
                fc = FROM_BLENDER @ f.calc_center_median()
                ref_a = math.atan2(fc.z - cz, fc.x - cx)
                for loop in f.loops:
                    p = FROM_BLENDER @ loop.vert.co
                    a = math.atan2(p.z - cz, p.x - cx)
                    da = (a - ref_a + math.pi) % (2 * math.pi) - math.pi
                    loop[uv_layer].uv = ((ref_a + da) / (2 * math.pi) * 2.0, (p.y - cy) * scale)
        elif mode == "cyl_z":
            if az > 0.82:
                for loop in f.loops:
                    p = FROM_BLENDER @ loop.vert.co
                    loop[uv_layer].uv = ((p.x - cx) * scale, (p.y - cy) * scale)
            else:
                fc = FROM_BLENDER @ f.calc_center_median()
                ref_a = math.atan2(fc.y - cy, fc.x - cx)
                for loop in f.loops:
                    p = FROM_BLENDER @ loop.vert.co
                    a = math.atan2(p.y - cy, p.x - cx)
                    da = (a - ref_a + math.pi) % (2 * math.pi) - math.pi
                    loop[uv_layer].uv = ((ref_a + da) / (2 * math.pi) * 2.5, (p.z - cz) * scale)
        elif mode == "shackle":
            fc = FROM_BLENDER @ f.calc_center_median()
            wx0, wy0 = fc.x - SHACKLE_X, fc.y + BODY_Y1
            if wy0 >= SHACKLE_ARC_Y:
                ref_s = math.atan2(wy0 - SHACKLE_ARC_Y, wx0)
                c_rad = Vector((SHACKLE_X * math.cos(ref_s), SHACKLE_ARC_Y + SHACKLE_X * math.sin(ref_s), 0.0))
                ref_rel = Vector((wx0, wy0, fc.z)) - c_rad
                ref_rad_dir = Vector((math.cos(ref_s), math.sin(ref_s), 0.0))
                ref_phi = math.atan2(ref_rel.z, ref_rel.dot(ref_rad_dir))
                for loop in f.loops:
                    p = FROM_BLENDER @ loop.vert.co
                    wx, wy, wz = p.x - SHACKLE_X, p.y + BODY_Y1, p.z
                    s = math.atan2(wy - SHACKLE_ARC_Y, wx)
                    ds = (s - ref_s + math.pi) % (2 * math.pi) - math.pi
                    sc = Vector((SHACKLE_X * math.cos(s), SHACKLE_ARC_Y + SHACKLE_X * math.sin(s), 0.0))
                    rel = Vector((wx, wy, wz)) - sc
                    rdir = Vector((math.cos(s), math.sin(s), 0.0))
                    phi = math.atan2(rel.z, rel.dot(rdir))
                    dphi = (phi - ref_phi + math.pi) % (2 * math.pi) - math.pi
                    loop[uv_layer].uv = ((ref_s + ds) * SHACKLE_X * scale, (ref_phi + dphi) / (2 * math.pi) * 1.5)
            else:
                leg_x = -SHACKLE_X if wx0 < 0.0 else SHACKLE_X
                ref_phi = math.atan2(fc.z, wx0 - leg_x)
                for loop in f.loops:
                    p = FROM_BLENDER @ loop.vert.co
                    wx, wy, wz = p.x - SHACKLE_X, p.y + BODY_Y1, p.z
                    phi = math.atan2(wz, wx - leg_x)
                    dphi = (phi - ref_phi + math.pi) % (2 * math.pi) - math.pi
                    loop[uv_layer].uv = (wy * scale, (ref_phi + dphi) / (2 * math.pi) * 1.5)
        else:
            for loop in f.loops:
                p = FROM_BLENDER @ loop.vert.co
                if ax >= ay and ax >= az:
                    u, v = (p.z - cz) * scale, (p.y - cy) * scale
                elif ay >= ax and ay >= az:
                    u, v = (p.x - cx) * scale, (p.z - cz) * scale
                else:
                    u, v = (p.x - cx) * scale, (p.y - cy) * scale
                loop[uv_layer].uv = (u, v)

    bm.to_mesh(obj.data)
    bm.free()


# ── Object helpers ────────────────────────────────────────────────────────────────────────────

def make_object(name, build, materials, material_index=0):
    """Build a mesh in glTF space and wrap it in an identity-transform Blender object."""
    bm = bmesh.new()
    build(bm)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    for f in bm.faces:
        f.material_index = material_index
    bm.transform(TO_BLENDER)
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    for m in materials:
        mesh.materials.append(m)
    obj = bpy.data.objects.new(name, mesh)
    SCENE.collection.objects.link(obj)
    return obj


def _bool_op(target, build, operation, material_index=0):
    cutter = make_object("_cutter", build, list(target.data.materials), material_index)
    mod = target.modifiers.new("bool", "BOOLEAN")
    mod.operation = operation
    mod.solver = "EXACT"
    mod.material_mode = "INDEX"
    mod.object = cutter
    SCENE.view_layers[0].objects.active = target
    with _override(object=target, active_object=target, selected_objects=[target]):
        bpy.ops.object.modifier_apply(modifier=mod.name)
    mesh = cutter.data
    bpy.data.objects.remove(cutter)
    bpy.data.meshes.remove(mesh)


def cut(target, build, material_index=0):
    """Boolean-subtract a cutter built in glTF space. Cut faces take `material_index`."""
    _bool_op(target, build, "DIFFERENCE", material_index)


def join_objects(target, extra):
    """Join an existing Blender object into `target`."""
    with _override(
        object=target, active_object=target,
        selected_objects=[target, extra], selected_editable_objects=[target, extra],
    ):
        bpy.ops.object.join()


def join_part(target, build, material_index=0):
    """Join an additional solid mesh part directly into `target`."""
    part = make_object("_part", build, list(target.data.materials), material_index)
    join_objects(target, part)


def clean(obj):
    """Tidy what the boolean solver leaves behind.

    Each cut splits flat faces along the intersection and strands vertices in the middle of
    straight edges. Triangulated as-is those become zero-area slivers, which shade as NaN and
    get smeared into blocks by bloom. Limited dissolve re-merges coplanar faces and drops the
    stranded vertices; triangulating here, rather than leaving it to the exporter, makes the
    result checkable (see tools/inspect_glb.py).
    """
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    bmesh.ops.remove_doubles(bm, verts=bm.verts[:], dist=1e-6)
    bmesh.ops.dissolve_limit(
        bm, angle_limit=math.radians(0.45), verts=bm.verts[:], edges=bm.edges[:],
        delimit={"MATERIAL"},
    )
    bmesh.ops.triangulate(bm, faces=bm.faces[:], quad_method="BEAUTY", ngon_method="BEAUTY")
    bmesh.ops.dissolve_degenerate(bm, dist=1e-6, edges=bm.edges[:])
    bmesh.ops.triangulate(bm, faces=[f for f in bm.faces if len(f.verts) > 3])

    def is_sliver(face):
        longest = max(e.calc_length() for e in face.edges)
        return longest < 1e-7 or 2.0 * face.calc_area() / longest < 1e-6

    slivers = [f for f in bm.faces if is_sliver(f)]
    if slivers:
        bmesh.ops.delete(bm, geom=slivers, context="FACES_ONLY")
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    bm.to_mesh(obj.data)
    bm.free()


def finish(obj, origin, sharp_degrees=40.0, parent=None, parent_origin=(0.0, 0.0, 0.0),
           uv_mode="box", uv_scale=4.0):
    """Move the object origin to `origin` (glTF space), clean, project UVs, shade, and parent."""
    clean(obj)
    o = B(origin)
    obj.data.transform(Matrix.Translation(-o))
    obj.location = o - B(parent_origin) if parent else o
    if parent:
        obj.parent = parent
    apply_uvs(obj, mode=uv_mode, scale=uv_scale)
    for poly in obj.data.polygons:
        poly.use_smooth = True
    obj.data.set_sharp_from_angle(angle=math.radians(sharp_degrees))
    obj.data.update()
    return obj


def material(name, rgb, metallic, roughness, alpha=1.0,
             base_tex=None, rough_tex=None, normal_tex=None, normal_strength=0.20):
    m = bpy.data.materials.new(name)
    assert m.name == name, f"a material called '{name}' is already open: materials are looked up by name"
    nt = m.node_tree
    bsdf = next((n for n in nt.nodes if n.type == "BSDF_PRINCIPLED"), None)
    if bsdf is None:
        bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled")
        out = nt.nodes.new("ShaderNodeOutputMaterial")
        nt.links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])

    bsdf.inputs["Base Color"].default_value = (*rgb, 1.0)
    bsdf.inputs["Metallic"].default_value = metallic
    bsdf.inputs["Roughness"].default_value = roughness
    bsdf.inputs["Alpha"].default_value = alpha

    if base_tex is not None:
        tex = nt.nodes.new("ShaderNodeTexImage")
        tex.image = base_tex
        nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])

    if rough_tex is not None:
        tex = nt.nodes.new("ShaderNodeTexImage")
        tex.image = rough_tex
        # Multiply normalized roughness texture by per-material roughnessFactor so metals can
        # share one compact texture while keeping distinct polish levels in glTF / Filament.
        mul = nt.nodes.new("ShaderNodeMath")
        mul.operation = "MULTIPLY"
        mul.inputs[1].default_value = roughness
        nt.links.new(tex.outputs["Color"], mul.inputs[0])
        nt.links.new(mul.outputs["Value"], bsdf.inputs["Roughness"])

    if normal_tex is not None:
        tex = nt.nodes.new("ShaderNodeTexImage")
        tex.image = normal_tex
        nmap = nt.nodes.new("ShaderNodeNormalMap")
        nmap.inputs["Strength"].default_value = normal_strength
        nt.links.new(tex.outputs["Color"], nmap.inputs["Color"])
        nt.links.new(nmap.outputs["Normal"], bsdf.inputs["Normal"])

    return m


# ── Materials ─────────────────────────────────────────────────────────────────────────────────

def make_materials():
    global M_ANODIZED, M_RAW_ALU, M_BIBLE_CAP, M_BRASS, M_PIN_BRASS, M_STEEL, M_NICKEL, M_BRONZE, M_TOOL, M_GRIP, M_BENCH
    global M_LAMINATE, M_CUT_STEEL, M_RIVET, M_ANODIZED_RED
    # Generate compact deterministic procedural PBR textures (shared across metals to keep GLB small).
    TEX_ANOD_RGH, TEX_ANOD_NRM = make_anodized_maps()
    TEX_MILL_RGH, TEX_MILL_NRM = make_milled_maps()
    TEX_BRUSH_RGH, TEX_BRUSH_NRM = make_brushed_metal_maps()
    TEX_GRIP_RGH, TEX_GRIP_NRM = make_grip_maps()
    TEX_BENCH_BASE, TEX_BENCH_RGH, TEX_BENCH_NRM = make_bench_maps()

    # Linear-space base colours. Metals carry their colour in F0, so these are reflectances.
    M_ANODIZED = material(
        "Anodized_Aluminum", (0.075, 0.094, 0.140), 0.94, 0.44,
        rough_tex=TEX_ANOD_RGH, normal_tex=TEX_ANOD_NRM, normal_strength=0.15,
    )
    M_RAW_ALU = material(
        "Milled_Aluminum", (0.64, 0.66, 0.69), 1.0, 0.40,
        rough_tex=TEX_MILL_RGH, normal_tex=TEX_MILL_NRM, normal_strength=0.22,
    )
    # Only the housing a lock is built with is exported; the others cost nothing in the file.
    M_LAMINATE = material(
        "Laminated_Steel", (0.56, 0.57, 0.58), 1.0, 0.46,
        rough_tex=TEX_BRUSH_RGH, normal_tex=TEX_BRUSH_NRM, normal_strength=0.24,
    )
    M_CUT_STEEL = material(
        "Cut_Steel", (0.70, 0.71, 0.73), 1.0, 0.36,
        rough_tex=TEX_MILL_RGH, normal_tex=TEX_MILL_NRM, normal_strength=0.20,
    )
    M_RIVET = material(
        "Rivet_Steel", (0.46, 0.47, 0.49), 1.0, 0.52,
        rough_tex=TEX_ANOD_RGH, normal_tex=TEX_ANOD_NRM, normal_strength=0.12,
    )
    M_ANODIZED_RED = material(
        "Anodized_Red", (0.50, 0.016, 0.022), 0.94, 0.42,
        rough_tex=TEX_ANOD_RGH, normal_tex=TEX_ANOD_NRM, normal_strength=0.15,
    )
    M_BIBLE_CAP = material(
        "Bible_Cap_Brass", (0.76, 0.56, 0.24), 1.0, 0.34,
        rough_tex=TEX_BRUSH_RGH, normal_tex=TEX_BRUSH_NRM, normal_strength=0.16,
    )
    M_BRASS = material(
        "Brushed_Brass", (0.935, 0.745, 0.350), 1.0, 0.30,
        rough_tex=TEX_BRUSH_RGH, normal_tex=TEX_BRUSH_NRM, normal_strength=0.18,
    )
    M_PIN_BRASS = material(
        "Polished_Brass", (0.985, 0.710, 0.265), 1.0, 0.18,
        rough_tex=TEX_BRUSH_RGH, normal_tex=TEX_BRUSH_NRM, normal_strength=0.11,
    )
    M_STEEL = material(
        "Hardened_Steel", (0.78, 0.80, 0.83), 1.0, 0.19,
        rough_tex=TEX_BRUSH_RGH, normal_tex=TEX_BRUSH_NRM, normal_strength=0.13,
    )
    M_NICKEL = material(
        "Nickel_Silver", (0.78, 0.75, 0.69), 1.0, 0.25,
        rough_tex=TEX_BRUSH_RGH, normal_tex=TEX_BRUSH_NRM, normal_strength=0.14,
    )
    M_BRONZE = material(
        "Phosphor_Bronze", (0.86, 0.45, 0.18), 1.0, 0.25,
    )
    M_TOOL = material(
        "Tool_Steel", (0.68, 0.70, 0.73), 1.0, 0.28,
        rough_tex=TEX_BRUSH_RGH, normal_tex=TEX_BRUSH_NRM, normal_strength=0.16,
    )
    M_GRIP = material(
        "Tool_Grip", (0.025, 0.027, 0.033), 0.0, 0.76,
        rough_tex=TEX_GRIP_RGH, normal_tex=TEX_GRIP_NRM, normal_strength=0.28,
    )
    M_BENCH = material(
        "Bench_Mat", (1.0, 1.0, 1.0), 0.0, 0.98,
        base_tex=TEX_BENCH_BASE, rough_tex=TEX_BENCH_RGH, normal_tex=TEX_BENCH_NRM, normal_strength=0.14,
    )


# ── Body_Cutaway: the trainer's anodized block ────────────────────────────────────────────────

def machine_body(body, bezel=True, caps=True, countersink=True):
    """Everything a housing has cut into it, whatever its outside looks like: the plug bore, the
    pin chambers, the shackle bores and the cutaway window. `body` must be one watertight
    solid whose materials are [outside, sectioned faces, bible caps]."""
    # Plug bore straight through, plus a crisp 45-degree beveled mouth on the keyway face.
    plug_bore_profile = [
        (BORE_R, -BODY_Z - 0.05),
        (BORE_R, BODY_Z - 0.006),
        (BORE_R + 0.006, BODY_Z + 0.002),
        (BORE_R + 0.006, BODY_Z + 0.04),
    ]
    cut(body, lambda bm: lathe_z(bm, plug_bore_profile, 40), material_index=0)

    # Subtle machined bezel groove on the front keyway face around the cylinder bore.
    def build_front_bezel_groove(bm):
        segs = 40
        r_in, r_out = BORE_R + 0.015, BORE_R + 0.021
        z_back, z_front = BODY_Z - 0.0035, BODY_Z + 0.010
        ring_pts = [(r_in, z_front), (r_in, z_back), (r_out, z_back), (r_out, z_front)]
        rings = []
        for k in range(segs):
            a = 2 * math.pi * k / segs
            ca, sa = math.cos(a), math.sin(a)
            rings.append([bm.verts.new((rad * ca, rad * sa, zz)) for rad, zz in ring_pts])
        for k in range(segs):
            n = (k + 1) % segs
            for j in range(4):
                jn = (j + 1) % 4
                bm.faces.new((rings[k][j], rings[n][j], rings[n][jn], rings[k][jn]))


    if bezel:
        cut(body, build_front_bezel_groove, material_index=1)

    # Pin chambers: from inside the bore up into the bible, plus stepped top recess for bible grub screws.
    for z in PIN_Z:
        chamber_profile = [
            (CHAMBER_R, 0.0),
            (CHAMBER_R, CHAMBER_TOP),
        ]
        if caps:
            chamber_profile += [
                (CHAMBER_R + 0.0024, CHAMBER_TOP + 0.0005),
                (CHAMBER_R + 0.0024, CHAMBER_TOP + 0.0145),
            ]
        cut(body, lambda bm, p=chamber_profile, z=z: lathe(bm, p, 20, (0, 0, z)), material_index=0)

    # Shackle bores with clean countersunk collar entry chamfers.
    # With collars the countersink is cut in their rim; without, in the top face itself.
    lip = BODY_Y1 + 0.004 if countersink else BODY_Y1 - 0.005
    heel_bore = [
        (SHACKLE_R + 0.003, SHACKLE_HEEL_Y - 0.008),
        (SHACKLE_R + 0.003, lip),
        (SHACKLE_R + 0.007, lip + 0.005),
        (SHACKLE_R + 0.007, BODY_Y1 + 0.05),
    ]
    toe_bore = [
        (SHACKLE_R + 0.003, SHACKLE_TOE_Y - 0.008),
        (SHACKLE_R + 0.003, lip),
        (SHACKLE_R + 0.007, lip + 0.005),
        (SHACKLE_R + 0.007, BODY_Y1 + 0.05),
    ]
    cut(body, lambda bm: lathe(bm, heel_bore, 22, (-SHACKLE_X, 0, 0)), material_index=0)
    cut(body, lambda bm: lathe(bm, toe_bore, 22, (SHACKLE_X, 0, 0)), material_index=0)

    # The cutaway: section the +X half through the pin centreline while `body` is a single watertight
    # manifold, open to the keyway face so the stacks, bible grub screws, and shackle toe notch are
    # clearly visible from both ISO and SHEAR views.
    cut(
        body,
        lambda bm: box(bm, (0.0, BODY_Y0 + 0.028, -WINDOW_Z), (BODY_X + 0.05, WINDOW_TOP, BODY_Z + 0.05)),
        material_index=1,
    )

    # Full 3D threaded brass bible grub-screw plugs seated right atop each spring (y = CHAMBER_TOP..+0.013),
    # matching the full-3D presentation of the springs and pin stacks in the cutaway window.
    for z in (PIN_Z if caps else ()):
        cap_profile = [
            (PIN_R - 0.0022, CHAMBER_TOP),
            (PIN_R + 0.0006, CHAMBER_TOP + 0.0018),
            (PIN_R + 0.0006, CHAMBER_TOP + 0.0040),
            (PIN_R - 0.0012, CHAMBER_TOP + 0.0054),
            (PIN_R + 0.0006, CHAMBER_TOP + 0.0068),
            (PIN_R + 0.0006, CHAMBER_TOP + 0.0094),
            (PIN_R - 0.0012, CHAMBER_TOP + 0.0108),
            (PIN_R + 0.0006, CHAMBER_TOP + 0.0122),
            (PIN_R - 0.0018, CHAMBER_TOP + 0.0135),
        ]
        join_part(body, lambda bm, p=cap_profile, z=z: lathe(bm, p, 18, (0, 0, z)), material_index=2)


def build_body_trainer():
    def build_body(bm):
        box(bm, (-BODY_X, BODY_Y0, -BODY_Z), (BODY_X, BODY_Y1, BODY_Z))
        bmesh.ops.bevel(bm, geom=bm.edges[:], offset=0.020, segments=4, profile=0.55, affect="EDGES")


    body = make_object("Body_Cutaway", build_body, [M_ANODIZED, M_RAW_ALU, M_BIBLE_CAP])

    # Subtle machined shackle collars rising 8mm on top of the housing (shackle clears by 14mm when open).
    for sx in (-SHACKLE_X, SHACKLE_X):
        collar_profile = [
            (SHACKLE_R + 0.022, BODY_Y1 - 0.008),
            (SHACKLE_R + 0.022, BODY_Y1 + 0.001),
            (SHACKLE_R + 0.015, BODY_Y1 + 0.008),
            (SHACKLE_R + 0.006, BODY_Y1 + 0.008),
        ]
        _bool_op(body, lambda bm, p=collar_profile, x=sx: lathe(bm, p, 22, (x, 0.0, 0.0)), "UNION", material_index=0)

    # 4 horizontal CNC grip flutes machined into the -X side of the housing.
    for fy in (-0.018, 0.050, 0.118, 0.186):
        def build_flute(bm, fy=fy):
            box(bm, (-BODY_X - 0.015, fy - 0.011, -BODY_Z + 0.026), (-BODY_X + 0.010, fy + 0.011, BODY_Z - 0.026))
            bmesh.ops.bevel(bm, geom=bm.edges[:], offset=0.004, segments=2, profile=0.5, affect="EDGES")
        cut(body, build_flute, material_index=0)

    machine_body(body)
    finish(body, (0.0, 0.0, 0.0), sharp_degrees=38.0, uv_mode="box", uv_scale=4.2)
    body["role"] = "housing"
    return body


# ── Body_Cutaway: laminated steel, in the style of a Master Lock No. 3 ────────────────────────

def rounded_rect(hx, hz, r, corner_segments):
    """(x, z) outline of a rectangle with half extents (hx, hz) and corners of radius r."""
    pts = []
    for cx, cz, start in ((hx - r, hz - r, 0.0), (-hx + r, hz - r, 0.5), (-hx + r, -hz + r, 1.0), (hx - r, -hz + r, 1.5)):
        for k in range(corner_segments + 1):
            a = math.pi * (start + 0.5 * k / corner_segments)
            pts.append((cx + r * math.cos(a), cz + r * math.sin(a)))
    return pts


def stack(bm, hx, hz, r, levels, corner_segments=6):
    """A solid whose plan is a rounded rectangle, drawn in at each of `levels` [(inset, y), ...]
    by that level's inset. Caps both ends."""
    rings = []
    for inset, y in levels:
        outline = rounded_rect(hx - inset, hz - inset, max(r - inset, 0.002), corner_segments)
        rings.append([bm.verts.new((x, y, z)) for x, z in outline])
    n = len(rings[0])
    for lo, hi in zip(rings, rings[1:]):
        for k in range(n):
            m = (k + 1) % n
            bm.faces.new((lo[k], lo[m], hi[m], hi[k]))
    bm.faces.new(rings[0])
    bm.faces.new(list(reversed(rings[-1])))


def build_body_laminated():
    plates = 14
    corner = 0.058
    groove = 0.0034                    # how far the joint between two plates is drawn in
    land = 0.0030                      # height of the bevel on each plate's edge
    pitch = (BODY_Y1 - BODY_Y0) / plates

    def build_body(bm):
        levels = [(groove, BODY_Y0)]
        for k in range(plates):
            y = BODY_Y0 + k * pitch
            levels += [(0.0, y + land), (0.0, y + pitch - land), (groove, y + pitch)]
        stack(bm, BODY_X, BODY_Z, corner, levels)

    body = make_object("Body_Cutaway", build_body, [M_LAMINATE, M_CUT_STEEL, M_RIVET])
    # The stack is the bible: the top plates close the chambers, so there are no caps to fit.
    machine_body(body, bezel=False, caps=False, countersink=False)

    # Four rivets hold the stack together. Their peened heads stand proud of the top plate.
    for sx in (-1, 1):
        for sz in (-1, 1):
            head = [
                (0.0165, BODY_Y1 - 0.002),
                (0.0165, BODY_Y1 + 0.0018),
                (0.0135, BODY_Y1 + 0.0052),
                (0.0070, BODY_Y1 + 0.0072),
            ]
            center = (sx * (BODY_X - corner + 0.012), 0.0, sz * (BODY_Z - corner + 0.012))
            join_part(body, lambda bm, p=head, c=center: lathe(bm, p, 16, c), material_index=2)

    finish(body, (0.0, 0.0, 0.0), sharp_degrees=32.0, uv_mode="box", uv_scale=4.2)
    body["role"] = "housing"
    return body


# ── Body_Cutaway: a solid aluminium block, in the style of an American Lock 1100 ──────────────

def build_body_block():
    corner = 0.030
    chamfer = 0.011

    def build_body(bm):
        levels = [
            (chamfer, BODY_Y0),
            (0.0, BODY_Y0 + chamfer),
            (0.0, BODY_Y1 - chamfer),
            (chamfer, BODY_Y1),
        ]
        stack(bm, BODY_X, BODY_Z, corner, levels, corner_segments=7)

    body = make_object("Body_Cutaway", build_body, [M_ANODIZED_RED, M_RAW_ALU, M_BIBLE_CAP])

    # A shallow pocket milled into the solid side, where a lockout label would go.
    def build_label_pocket(bm):
        box(bm, (-BODY_X - 0.02, 0.085, -BODY_Z + 0.060), (-BODY_X + 0.0035, 0.245, BODY_Z - 0.060))
        bmesh.ops.bevel(bm, geom=bm.edges[:], offset=0.003, segments=2, profile=0.5, affect="EDGES")

    cut(body, build_label_pocket, material_index=0)
    machine_body(body, countersink=False)
    finish(body, (0.0, 0.0, 0.0), sharp_degrees=38.0, uv_mode="box", uv_scale=4.2)
    body["role"] = "housing"
    return body


BODY_BUILDERS = {
    "trainer": build_body_trainer,
    "laminated": build_body_laminated,
    "block": build_body_block,
}


# ── Core_Plug ─────────────────────────────────────────────────────────────────────────────────

def build_plug():
    plug_profile = [
        (PLUG_R - 0.005, PLUG_Z0),
        (PLUG_R, PLUG_Z0 + 0.003),
        (PLUG_R, PLUG_Z0 + 0.009),
        (PLUG_R - 0.007, PLUG_Z0 + 0.010),      # rear retaining C-clip groove
        (PLUG_R - 0.007, PLUG_Z0 + 0.017),
        (PLUG_R, PLUG_Z0 + 0.018),
        (PLUG_R, PLUG_Z1 - 0.005),              # main cylinder barrel (exact PLUG_R along pin zone)
        (PLUG_R - 0.005, PLUG_Z1),              # crisp beveled front face flange
    ]
    plug = make_object("Core_Plug", lambda bm: lathe_z(bm, plug_profile, 42), [M_BRASS])

    # Subtle concentric turned groove on the front face of the plug.
    def build_plug_face_ring(bm):
        segs = 36
        r_in, r_out = 0.047, 0.052
        z_back, z_front = PLUG_Z1 - 0.0025, PLUG_Z1 + 0.010
        ring_pts = [(r_in, z_front), (r_in, z_back), (r_out, z_back), (r_out, z_front)]
        rings = []
        for k in range(segs):
            a = 2 * math.pi * k / segs
            ca, sa = math.cos(a), math.sin(a)
            rings.append([bm.verts.new((rad * ca, rad * sa, zz)) for rad, zz in ring_pts])
        for k in range(segs):
            n = (k + 1) % segs
            for j in range(4):
                jn = (j + 1) % 4
                bm.faces.new((rings[k][j], rings[n][j], rings[n][jn], rings[k][jn]))


    cut(plug, build_plug_face_ring)

    # The keyway's warding is the lock's own; the slot the wrench and pick use is common to all.
    cut(plug, lambda bm: prism_xy(bm, KEYWAY_OUTLINE, PLUG_Z0 + 0.016, PLUG_Z1 + 0.05))

    # Subtle chamfered entry recess at the mouth of the keyway.
    cut(
        plug,
        lambda bm: box(bm, (-KEYWAY_HALF_W - 0.004, KEYWAY_Y0 - 0.003, PLUG_Z1 - 0.003),
                       (KEYWAY_HALF_W + 0.004, KEYWAY_Y1 + 0.003, PLUG_Z1 + 0.02)),
    )

    # Pin chambers drilled through the top of the plug.
    for z in PIN_Z:
        cut(plug, lambda bm, z=z: lathe(bm, [(CHAMBER_R, PIN_REST_Y + 0.008), (CHAMBER_R, PLUG_R + 0.05)], 20, (0, 0, z)))

    # Rear actuator slot recessed into the back face of the plug (visible in Exploded & Ghost).
    cut(plug, lambda bm: box(bm, (-0.014, -0.030, PLUG_Z0 - 0.01), (0.014, 0.030, PLUG_Z0 + 0.008)))

    # Cutaway along +X inside the window.
    cut(plug, lambda bm: box(bm, (0.0, -PLUG_R - 0.05, -WINDOW_Z), (PLUG_R + 0.05, PLUG_R + 0.05, WINDOW_Z)))

    finish(plug, (0.0, 0.0, 0.0), sharp_degrees=38.0, uv_mode="cyl_z", uv_scale=6.0)
    plug["shear_y"] = SHEAR_Y
    plug["pin_rest_y"] = PIN_REST_Y
    plug["pick_fulcrum_z"] = PICK_FULCRUM_Z
    plug["open_degrees"] = -90.0
    plug["chamber_top"] = CHAMBER_TOP
    plug["driver_length"] = DRIVER_LEN
    return plug


# ── Pin stacks ────────────────────────────────────────────────────────────────────────────────

# Heights of a serrated driver's grooves above its base. LockMechanics.SERRATION_PITCH is the
# same ladder: a groove h above the base meets the shear line h short of the set.
SERRATIONS = (0.006, 0.012, 0.018)


def _groove(h, depth=0.0028):
    """Lathe profile points for one V-bottomed groove centred `h` up a pin."""
    return [(PIN_R, h - 0.0016), (PIN_R - depth, h - 0.0004), (PIN_R - depth, h + 0.0004), (PIN_R, h + 0.0016)]


def build_pins(plug):
    for i, (z, key_len) in enumerate(zip(PIN_Z, KEY_PIN_LEN), start=1):
        # Smooth bullet-nose ogive tip with exact contact point at y=0 and crisp shear shoulder.
        key_profile = [
            (0.0025, 0.000),
            (0.0072, 0.0022),
            (0.0118, 0.0058),
            (0.0148, 0.0102),
            (PIN_R, 0.0155),
        ]
        if i in SERRATED:
            # A serrated key pin to match: two shallow grooves under its shoulder.
            for h in (key_len - 0.0150, key_len - 0.0090):
                key_profile += _groove(h)
        key_profile += [
            (PIN_R, key_len - 0.0026),
            (PIN_R - 0.0024, key_len),
        ]
        # Grooves cost rings; a serrated pin gets fewer segments round it to pay for them.
        segments = 16 if i in SERRATED else 22
        key = make_object(f"KeyPin_{i}", lambda bm, p=key_profile, z=z: lathe(bm, p, segments, (0, PIN_REST_Y, z)), [M_PIN_BRASS])
        finish(key, (0.0, PIN_REST_Y, z), sharp_degrees=42.0, parent=plug, uv_mode="cyl_y", uv_scale=12.0)
        lift = SHEAR_Y - (PIN_REST_Y + key_len)
        key["pin"] = i
        key["length"] = key_len
        key["lift_to_shear"] = lift

        base = PIN_REST_Y + key_len
        d = DRIVER_LEN
        if i in SPOOLS:
            # Precision-turned security spool driver with crisp chamfered lips and clean waist fillets.
            driver_profile = [
                (PIN_R - 0.0025, 0.0),
                (PIN_R, 0.0025),
                (PIN_R, 0.0135),
                (PIN_R - 0.0012, 0.0150),
                (0.0085, 0.0170),
                (0.0085, d - 0.0170),
                (PIN_R - 0.0012, d - 0.0150),
                (PIN_R, d - 0.0135),
                (PIN_R, d - 0.0025),
                (PIN_R - 0.0025, d),
            ]
        elif i in SERRATED:
            # Serrated driver: a ladder of fine grooves at each end. Under torque every one of
            # them catches on the shear line on its way up, and each catch feels like a set.
            driver_profile = [(PIN_R - 0.0025, 0.0), (PIN_R, 0.0025)]
            for h in SERRATIONS:
                driver_profile += _groove(h)
            for h in reversed(SERRATIONS):
                driver_profile += _groove(d - h)
            driver_profile += [(PIN_R, d - 0.0025), (PIN_R - 0.0025, d)]
        else:
            # Standard cylindrical driver with crisp double-beveled shear faces.
            driver_profile = [
                (PIN_R - 0.0026, 0.0),
                (PIN_R, 0.0026),
                (PIN_R, d - 0.0026),
                (PIN_R - 0.0026, d),
            ]
        driver = make_object(f"DriverPin_{i}", lambda bm, p=driver_profile, z=z, b=base: lathe(bm, p, segments, (0, b, z)), [M_NICKEL])
        finish(driver, (0.0, base, z), sharp_degrees=42.0, uv_mode="cyl_y", uv_scale=12.0)
        driver["pin"] = i
        driver["spool"] = i in SPOOLS
        driver["serrated"] = i in SERRATED

        # Closed-and-ground helical spring seated flush against the bible cap and top of the driver.
        rest = CHAMBER_TOP - (base + d)
        wire_r = 0.0034
        coil_r = 0.0118
        turns = 6.25
        steps = 68
        usable = max(0.010, rest - 2.0 * wire_r)
        helix = []
        for s in range(steps + 1):
            u = s / steps
            a = 2.0 * math.pi * turns * u
            end_frac = 0.55 / turns
            if u < end_frac:
                t = u / end_frac
                v = 0.04 * (t * t * (3.0 - 2.0 * t))
            elif u > 1.0 - end_frac:
                t = (u - (1.0 - end_frac)) / end_frac
                v = 0.96 + 0.04 * (t * t * (3.0 - 2.0 * t))
            else:
                t = (u - end_frac) / (1.0 - 2.0 * end_frac)
                v = 0.04 + 0.92 * t
            y = (CHAMBER_TOP - wire_r) - usable * v
            helix.append((coil_r * math.cos(a), y, z + coil_r * math.sin(a)))
        spring = make_object(f"Spring_{i}", lambda bm, h=helix: tube(bm, h, wire_r, 6), [M_BRONZE])
        finish(spring, (0.0, CHAMBER_TOP, z), sharp_degrees=82.0, uv_mode="cyl_y", uv_scale=14.0)
        spring["pin"] = i
        spring["rest_length"] = rest


# ── Shackle ───────────────────────────────────────────────────────────────────────────────────

def build_shackle():
    shackle_path = [
        (-SHACKLE_X, SHACKLE_HEEL_Y, 0.0),
        (-SHACKLE_X, SHACKLE_HEEL_Y + 0.004, 0.0),
        (-SHACKLE_X, SHACKLE_ARC_Y - 0.055, 0.0),
    ]
    shackle_radii = [SHACKLE_R - 0.0045, SHACKLE_R, SHACKLE_R]

    ARC_STEPS = 28
    for s in range(ARC_STEPS + 1):
        a = math.pi - math.pi * s / ARC_STEPS
        shackle_path.append((SHACKLE_X * math.cos(a), SHACKLE_ARC_Y + SHACKLE_X * math.sin(a), 0.0))
        shackle_radii.append(SHACKLE_R)

    shackle_path += [
        (SHACKLE_X, SHACKLE_ARC_Y - 0.055, 0.0),
        (SHACKLE_X, SHACKLE_TOE_Y + 0.004, 0.0),
        (SHACKLE_X, SHACKLE_TOE_Y, 0.0),
    ]
    shackle_radii += [SHACKLE_R, SHACKLE_R, SHACKLE_R - 0.0045]

    shackle = make_object("Shackle", lambda bm: tube(bm, shackle_path, shackle_radii, 22), [M_STEEL])

    # Machined crescent locking-ball notch on the inner (-X) face of the toe leg (visible in cutaway/open).
    cut(shackle, lambda bm: cylinder_z(bm, 0.015, -0.05, 0.05, 18, center_xy=(SHACKLE_X - SHACKLE_R - 0.002, SHACKLE_TOE_Y + 0.022)))
    # Heel retention notch on the inner (+X) face of the heel leg.
    cut(shackle, lambda bm: cylinder_z(bm, 0.014, -0.05, 0.05, 16, center_xy=(-SHACKLE_X + SHACKLE_R + 0.003, SHACKLE_HEEL_Y + 0.036)))

    finish(shackle, (-SHACKLE_X, BODY_Y1, 0.0), sharp_degrees=55.0, uv_mode="shackle", uv_scale=4.5)
    shackle["toe_engagement"] = BODY_Y1 - SHACKLE_TOE_Y
    return shackle


# ── TensionWrench (beveled bent spring-steel bar with iconic 90-degree flex twist) ────────────

def build_wrench_tool(plug):
    WRENCH_Y = KEYWAY_Y0 + 0.007
    wrench_origin = (0.0, WRENCH_Y, PLUG_Z1)


    def build_wrench(bm):
        bend_r = 0.012
        z_elbow = PLUG_Z1 + 0.011
        pts = [
            Vector((0.0, WRENCH_Y, PLUG_Z1 - 0.060)),
            Vector((0.0, WRENCH_Y, PLUG_Z1 - 0.056)),
            Vector((0.0, WRENCH_Y, z_elbow - bend_r)),
        ]
        bend_steps = 7
        for k in range(1, bend_steps + 1):
            a = (math.pi * 0.5) * k / bend_steps
            pts.append(Vector((-bend_r * (1.0 - math.cos(a)), WRENCH_Y, (z_elbow - bend_r) + bend_r * math.sin(a))))
        handle_xs = [-0.024, -0.048, -0.062, -0.076, -0.090, -0.104, -0.118, -0.132, -0.155, -0.215, -0.265, -0.270]
        for hx in handle_xs:
            pts.append(Vector((hx, WRENCH_Y, z_elbow)))

        frames = []
        up_y = Vector((0.0, 1.0, 0.0))
        for i, p in enumerate(pts):
            a = pts[max(i - 1, 0)]
            b = pts[min(i + 1, len(pts) - 1)]
            tan = (b - a).normalized()
            side = up_y.cross(tan).normalized()
            up = tan.cross(side).normalized()
            if p.x < -0.050:
                tw_u = max(0.0, min(1.0, (-p.x - 0.050) / 0.080))
                twist = (math.pi * 0.5) * (tw_u * tw_u * (3.0 - 2.0 * tw_u))
            else:
                twist = 0.0
            ct, st = math.cos(twist), math.sin(twist)
            r_vec = side * ct + up * st
            u_vec = -side * st + up * ct
            frames.append((p, r_vec, u_vec, i / (len(pts) - 1)))

        def section(t):
            end_taper = 0.78 if (t < 0.02 or t > 0.98) else 1.0
            return chamfer_rect(0.0068 * end_taper, 0.0032 * end_taper, 0.0010 * end_taper)

        sweep_section(bm, frames, section)


    wrench = make_object("TensionWrench", build_wrench, [M_TOOL])
    finish(wrench, wrench_origin, sharp_degrees=48.0, parent=plug, uv_mode="box", uv_scale=8.0)
    return wrench


# ── HookPick (precision beveled medium hook + ergonomic full-tang riveted grip) ───────────────

def build_pick():
    pick_outline = [  # (z, y) in glTF space; exact hook tip pad at (0, 0)
        (-0.0018, 0.0000),
        (0.0022, 0.0000),
        (0.0038, -0.0012),
        (0.0046, -0.0045),
        (0.0062, -0.0088),
        (0.0095, -0.0118),
        (0.0160, -0.0132),
        (0.1100, -0.0132),
        (0.2450, -0.0112),
        (0.2640, -0.0052),
        (0.4640, -0.0052),
        (0.4690, -0.0090),
        (0.4690, -0.0245),
        (0.4640, -0.0285),
        (0.2640, -0.0285),
        (0.2450, -0.0218),
        (0.1100, -0.0195),
        (0.0090, -0.0195),
        (0.0020, -0.0185),
        (-0.0035, -0.0150),
        (-0.0050, -0.0095),
        (-0.0036, -0.0032),
    ]


    # Everything behind the thin shank moves back by PICK_EXTRA, so the handle of a pick that
    # reaches the last pin of a longer keyway still stays outside it.
    e = PICK_EXTRA
    pick_outline = [(z + e if z > 0.2 else z, y) for z, y in pick_outline]

    def build_pick_blade(bm):
        prism_yz(bm, pick_outline, -0.0032, 0.0032)
        bmesh.ops.bevel(bm, geom=bm.edges[:], offset=0.0008, segments=1, profile=0.5, affect="EDGES")


    pick = make_object("HookPick", build_pick_blade, [M_TOOL, M_GRIP], material_index=0)


    def build_ergonomic_grip(bm):
        z_stations = [
            (0.258, 0.0052, 0.0095),
            (0.266, 0.0074, 0.0118),
            (0.295, 0.0078, 0.0124),
            (0.340, 0.0072, 0.0116),
            (0.395, 0.0080, 0.0128),
            (0.448, 0.0076, 0.0122),
            (0.464, 0.0064, 0.0108),
            (0.470, 0.0044, 0.0086),
        ]
        yc = -0.0168
        right = Vector((1.0, 0.0, 0.0))
        up = Vector((0.0, 1.0, 0.0))
        frames = [(Vector((0.0, yc, z + e)), right, up, (hw, hh)) for z, hw, hh in z_stations]

        def sec(dims):
            hw, hh = dims
            return chamfer_rect(hw, hh, min(hw, hh) * 0.42)

        sweep_section(bm, frames, sec)


    grip = make_object("_grip", build_ergonomic_grip, [M_TOOL, M_GRIP], material_index=1)
    for gz in (0.282 + e, 0.292 + e, 0.302 + e, 0.312 + e):
        cut(grip, lambda bm, gz=gz: box(bm, (-0.012, -0.025, gz - 0.0020), (-0.0062, -0.0085, gz + 0.0020)), material_index=1)
        cut(grip, lambda bm, gz=gz: box(bm, (0.0062, -0.025, gz - 0.0020), (0.012, -0.0085, gz + 0.0020)), material_index=1)

    for rz in (0.352 + e, 0.430 + e):
        def build_rivet(bm, rz=rz):
            segs = 12
            r = 0.0038
            lo, hi = [], []
            for k in range(segs):
                a = 2 * math.pi * k / segs
                y = -0.0168 + r * math.cos(a)
                z = rz + r * math.sin(a)
                lo.append(bm.verts.new((-0.0082, y, z)))
                hi.append(bm.verts.new((0.0082, y, z)))
            for k in range(segs):
                n = (k + 1) % segs
                bm.faces.new((lo[k], lo[n], hi[n], hi[k]))
            bm.faces.new(lo)
            bm.faces.new(list(reversed(hi)))
        join_part(pick, build_rivet, material_index=0)

    join_objects(pick, grip)
    finish(pick, (0.0, 0.0, 0.0), sharp_degrees=42.0, uv_mode="box", uv_scale=8.0)
    pick.location = B((0.0, PIN_REST_Y - 0.006, PLUG_Z1 + 0.030))
    return pick


# ── Bench (Locksmith Silicone Pinning Mat) ────────────────────────────────────────────────────

def build_bench():
    bench_profile = [
        (0.736, -0.030),
        (0.748, -0.024),
        (0.750, -0.003),
        (0.744, 0.0035),
        (0.720, 0.0035),
        (0.706, 0.000),
    ]
    bench = make_object("Bench", lambda bm: lathe(bm, bench_profile, 56, (0, BODY_Y0, 0)), [M_BENCH])
    finish(bench, (0.0, BODY_Y0, 0.0), sharp_degrees=45.0, uv_mode="planar_bench")


# ── Export ────────────────────────────────────────────────────────────────────────────────────

def export(out, lock):
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with _override():
        _export_gltf(out)
    patch_glb(out, lock)
    print(f"WROTE {out} ({os.path.getsize(out)} bytes)")


def _export_gltf(out):
    bpy.ops.export_scene.gltf(
        filepath=out,
        use_active_scene=True,
        export_format="GLB",
        export_yup=True,
        export_apply=True,
        export_extras=True,
        export_normals=True,
        export_tangents=True,
        export_texcoords=True,
        export_materials="EXPORT",
        export_image_format="AUTO",
        export_cameras=False,
        export_lights=False,
        export_animations=False,
        export_skins=False,
        export_morph=False,
        use_selection=False,
    )


def patch_glb(path, lock):
    """The housing must load with blended materials so the app can ghost it at runtime; the
    exporter only writes BLEND when alpha is already below 1."""
    with open(path, "rb") as f:
        data = f.read()
    magic, version, _ = struct.unpack_from("<III", data, 0)
    assert magic == 0x46546C67 and version == 2
    json_len, json_type = struct.unpack_from("<II", data, 12)
    assert json_type == 0x4E4F534A
    doc = json.loads(data[20:20 + json_len])
    rest = data[20 + json_len:]
    housing = next(n for n in doc["nodes"] if n["name"] == "Body_Cutaway")
    for prim in doc["meshes"][housing["mesh"]]["primitives"]:
        doc["materials"][prim["material"]]["alphaMode"] = "BLEND"
    doc["asset"]["extras"] = {
        "lock": lock, "units_per_meter": 10, "up": "+Y", "keyway_faces": "+Z", "cutaway_side": "+X",
    }
    payload = json.dumps(doc, separators=(",", ":")).encode("utf-8")
    payload += b" " * (-len(payload) % 4)
    out = struct.pack("<III", magic, 2, 12 + 8 + len(payload) + len(rest))
    out += struct.pack("<II", len(payload), 0x4E4F534A) + payload + rest
    with open(path, "wb") as f:
        f.write(out)


# ── Optional studio preview renders (`--previews DIR` after `--`) ─────────────────────────────

def light_studio():
    """Lights the scene the way the app does: studio.hdr for the room, never drawn, and the key
    light high on the cutaway side. Not exported; it is for previews and for looking."""
    scene = SCENE
    world = bpy.data.worlds.get("LockBench Studio")
    if world is None:
        world = bpy.data.worlds.new("LockBench Studio")
        nt = world.node_tree
        nt.nodes.clear()
        out_w = nt.nodes.new("ShaderNodeOutputWorld")
        mix_s = nt.nodes.new("ShaderNodeMixShader")
        lp = nt.nodes.new("ShaderNodeLightPath")
        bg_cam = nt.nodes.new("ShaderNodeBackground")
        bg_cam.inputs[0].default_value = (0.010, 0.012, 0.016, 1.0)
        bg_cam.inputs[1].default_value = 1.0
        bg_env = nt.nodes.new("ShaderNodeBackground")
        bg_env.inputs[1].default_value = 1.0
        if os.path.exists(STUDIO_HDR):
            env_tex = nt.nodes.new("ShaderNodeTexEnvironment")
            env_tex.image = bpy.data.images.load(STUDIO_HDR, check_existing=True)
            nt.links.new(env_tex.outputs["Color"], bg_env.inputs["Color"])
        else:
            bg_env.inputs[0].default_value = (0.5, 0.52, 0.56, 1.0)
        nt.links.new(lp.outputs["Is Camera Ray"], mix_s.inputs["Fac"])
        nt.links.new(bg_env.outputs["Background"], mix_s.inputs[1])
        nt.links.new(bg_cam.outputs["Background"], mix_s.inputs[2])
        nt.links.new(mix_s.outputs["Shader"], out_w.inputs["Surface"])
    scene.world = world

    sun_data = bpy.data.lights.new("key", "SUN")
    sun_data.energy = 2.6
    sun_data.angle = math.radians(3.5)
    sun = bpy.data.objects.new("key", sun_data)
    sun_dir = B((-0.62, -1.0, -0.48)).normalized()
    sun.rotation_euler = sun_dir.to_track_quat("-Z", "Y").to_euler()
    scene.collection.objects.link(sun)


def render_previews(preview_dir, lock, body, shackle, wrench, pick):
    os.makedirs(preview_dir, exist_ok=True)
    scene = SCENE
    light_studio()

    cam_data = bpy.data.cameras.new("cam")
    cam = bpy.data.objects.new("cam", cam_data)
    scene.collection.objects.link(cam)
    scene.camera = cam

    scene.render.resolution_x, scene.render.resolution_y = 960, 960
    try:
        scene.render.engine = "CYCLES"
        scene.cycles.device = "CPU"
        scene.cycles.samples = 48
        scene.cycles.use_denoising = True
    except Exception:
        scene.render.engine = "BLENDER_EEVEE"

    views = [
        ("iso", (1.32, 0.70, 0.86), (0.0, 0.17, 0.0), 48),
        ("shear", (1.30, 0.11, 0.0), (0.0, 0.11, 0.0), 55),
        ("keyway", (0.0, 0.12, 1.70), (0.0, 0.17, 0.0), 52),
        ("closeup", (0.68, 0.16, 0.30), (0.0, 0.12, 0.0), 56),
        ("flutes", (-1.28, 0.56, 0.88), (0.0, 0.16, 0.0), 48),
    ]
    for label, eye, tgt, lens in views:
        cam.data.lens = lens
        cam.location = B(eye)
        cam.rotation_euler = (B(tgt) - cam.location).to_track_quat("-Z", "Y").to_euler()
        scene.render.filepath = os.path.join(preview_dir, f"{lock}_{label}.png")
        bpy.ops.render.render(write_still=True)
        print("RENDERED", scene.render.filepath)

    # Also render an exploded preview matching LockRig.kt's explode=1.0 pose!
    body.location += B((-0.42, 0.0, 0.0))
    shackle.location += B((0.0, 0.20, 0.0))
    for i in range(1, len(PIN_Z) + 1):
        bpy.data.objects[f"KeyPin_{i}"].location += B((0.0, 0.05, 0.0))
        bpy.data.objects[f"DriverPin_{i}"].location += B((0.0, 0.15, 0.0))
        bpy.data.objects[f"Spring_{i}"].location += B((0.0, 0.24, 0.0))
    wrench.location += B((0.0, 0.0, 0.14))
    pick.location += B((0.0, 0.0, 0.20))
    cam.data.lens = 44
    cam.location = B((1.42, 0.72, 0.96))
    cam.rotation_euler = (B((-0.05, 0.22, 0.0)) - cam.location).to_track_quat("-Z", "Y").to_euler()
    scene.render.filepath = os.path.join(preview_dir, f"{lock}_exploded.png")
    bpy.ops.render.render(write_still=True)
    print("RENDERED", scene.render.filepath)


# ── Build ─────────────────────────────────────────────────────────────────────────────────────

def build(lock="trainer", out=None, live=False, preview_dir=None):
    """Builds `lock` and writes its .glb to `out`, or to the app's assets. Returns the path."""
    configure(lock)
    begin(lock, live)
    make_materials()
    body = BODY_BUILDERS[BODY_STYLE]()
    plug = build_plug()
    build_pins(plug)
    shackle = build_shackle()
    wrench = build_wrench_tool(plug)
    pick = build_pick()
    build_bench()
    out = out or os.path.join(MODELS, FILE)
    export(out, lock)
    if preview_dir:
        render_previews(preview_dir, lock, body, shackle, wrench, pick)
    if live:
        light_studio()
        stash(lock)
    return out


def main(argv):
    args = argv[argv.index("--") + 1:] if "--" in argv else []
    preview_dir = None
    if "--previews" in args:
        at = args.index("--previews")
        preview_dir = args[at + 1]
        del args[at:at + 2]
    for lock in args or list(LOCKS):
        build(lock, preview_dir=preview_dir)


if __name__ == "__main__":
    main(sys.argv)
