"""
Generates app/src/main/assets/models/cutaway_padlock.glb — a 4-pin tumbler cutaway padlock
for the Lock Bench viewer.

Run headless:
    /Applications/Blender.app/Contents/MacOS/Blender --background --python tools/build_padlock.py

Everything below is authored in glTF space so the numbers match what the Kotlin side sees:
    +Y up, +Z out of the keyway face (toward the viewer), +X is the cutaway side.
    1 unit = 0.1 m, so 0.01 units = 1 mm.

Node contract (names are looked up from Kotlin, see LockRig.kt):
    Body_Cutaway                      housing, sectioned on the +X side
    Shackle                           origin on the heel axis at the top of the body
    Core_Plug                         origin on the cylinder axis; rotates about Z
      KeyPin_1..4                     children of the plug; origin at the tip; lift along Y
      TensionWrench                   child of the plug; rides the rotation
    DriverPin_1..4                    origin at the base; lift along Y
    Spring_1..4                       origin at the top of the bible; scale Y to compress
    HookPick                          origin at the hook tip
    Bench                             shadow-catching work surface

The mechanical numbers Kotlin needs travel in glTF node `extras`, so this file is the only
place they are written down.
"""

import json
import math
import os
import struct
import sys

import bmesh
import bpy
from mathutils import Matrix, Vector

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models", "cutaway_padlock.glb")
PREVIEW_DIR = None
if "--" in sys.argv:
    extra = sys.argv[sys.argv.index("--") + 1:]
    if extra:
        PREVIEW_DIR = extra[0]

# glTF (x, y, z) -> Blender (x, -z, y). A proper rotation, so winding survives.
TO_BLENDER = Matrix(((1, 0, 0, 0), (0, 0, -1, 0), (0, 1, 0, 0), (0, 0, 0, 1)))


def B(v):
    return Vector((v[0], -v[2], v[1]))


# ── Dimensions ────────────────────────────────────────────────────────────────────────────────

BODY_X, BODY_Z = 0.22, 0.13            # half extents
BODY_Y0, BODY_Y1 = -0.11, 0.29
PLUG_R = 0.070
BORE_R = 0.0715
PLUG_Z0, PLUG_Z1 = -0.125, 0.135
SHEAR_Y = PLUG_R                       # top of the plug, where plug meets bible
PIN_R = 0.016
CHAMBER_R = 0.0175
CHAMBER_TOP = 0.25
PIN_Z = [0.066, 0.022, -0.022, -0.066]  # pin 1 sits nearest the keyway face
PIN_REST_Y = -0.030                    # key pin tip height with no pick under it
KEY_PIN_LEN = [0.068, 0.052, 0.078, 0.060]
DRIVER_LEN = 0.060
SPOOLS = {2, 3}
KEYWAY_HALF_W = 0.011
KEYWAY_Y0, KEYWAY_Y1 = -0.064, -0.012
WINDOW_Z = 0.100                       # cutaway window half length
SHACKLE_X = 0.15
SHACKLE_R = 0.032
SHACKLE_ARC_Y = 0.40
SHACKLE_HEEL_Y = 0.05
SHACKLE_TOE_Y = 0.235
PICK_FULCRUM_Z = PLUG_Z1               # the pick levers on the keyway mouth


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


def cylinder_z(bm, radius, z0, z1, segments, center_xy=(0.0, 0.0)):
    """A capped cylinder whose axis runs along Z."""
    cx, cy = center_xy
    lo, hi = [], []
    for k in range(segments):
        a = 2 * math.pi * k / segments
        x, y = cx + radius * math.cos(a), cy + radius * math.sin(a)
        lo.append(bm.verts.new((x, y, z0)))
        hi.append(bm.verts.new((x, y, z1)))
    for k in range(segments):
        n = (k + 1) % segments
        bm.faces.new((lo[k], lo[n], hi[n], hi[k]))
    bm.faces.new(lo)
    bm.faces.new(list(reversed(hi)))


def box(bm, lo, hi):
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    v = [bm.verts.new(p) for p in (
        (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
        (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1))]
    # Wound so every normal points outward: bevel and the exact boolean solver both need a
    # consistently oriented, closed volume.
    faces = [bm.faces.new([v[i] for i in f]) for f in (
        (0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (2, 3, 7, 6), (1, 2, 6, 5), (0, 4, 7, 3))]
    return faces


def prism_yz(bm, outline, x0, x1):
    """Extrude a (z, y) outline along X. Used for the flat tools."""
    a = [bm.verts.new((x0, y, z)) for z, y in outline]
    b = [bm.verts.new((x1, y, z)) for z, y in outline]
    n = len(outline)
    for k in range(n):
        m = (k + 1) % n
        bm.faces.new((a[k], a[m], b[m], b[k]))
    bm.faces.new(a)
    bm.faces.new(list(reversed(b)))


def prism_xz(bm, outline, y0, y1):
    """Extrude an (x, z) outline along Y."""
    a = [bm.verts.new((x, y0, z)) for x, z in outline]
    b = [bm.verts.new((x, y1, z)) for x, z in outline]
    n = len(outline)
    for k in range(n):
        m = (k + 1) % n
        bm.faces.new((a[k], a[m], b[m], b[k]))
    bm.faces.new(a)
    bm.faces.new(list(reversed(b)))


def tube(bm, path, radius, sides):
    """Sweep a circle along a polyline with parallel-transport frames. Caps both ends."""
    pts = [Vector(p) for p in path]
    tangents = []
    for i in range(len(pts)):
        a = pts[max(i - 1, 0)]
        b = pts[min(i + 1, len(pts) - 1)]
        tangents.append((b - a).normalized())
    t0 = tangents[0]
    ref = Vector((0, 0, 1)) if abs(t0.z) < 0.9 else Vector((1, 0, 0))
    normal = (ref - t0 * ref.dot(t0)).normalized()
    rings = []
    for i, (p, t) in enumerate(zip(pts, tangents)):
        if i > 0:
            normal = (normal - t * normal.dot(t)).normalized()
        binormal = t.cross(normal)
        ring = []
        for k in range(sides):
            a = 2 * math.pi * k / sides
            ring.append(bm.verts.new(p + radius * (math.cos(a) * normal + math.sin(a) * binormal)))
        rings.append(ring)
    for lo, hi in zip(rings, rings[1:]):
        for k in range(sides):
            n = (k + 1) % sides
            bm.faces.new((lo[k], lo[n], hi[n], hi[k]))
    bm.faces.new(rings[0])
    bm.faces.new(list(reversed(rings[-1])))


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
    bpy.context.scene.collection.objects.link(obj)
    return obj


def cut(target, build, material_index=0):
    """Boolean-subtract a cutter built in glTF space. Cut faces take `material_index`."""
    cutter = make_object("_cutter", build, list(target.data.materials), material_index)
    mod = target.modifiers.new("cut", "BOOLEAN")
    mod.operation = "DIFFERENCE"
    mod.solver = "EXACT"
    mod.material_mode = "INDEX"
    mod.object = cutter
    bpy.context.view_layer.objects.active = target
    with bpy.context.temp_override(object=target, active_object=target, selected_objects=[target]):
        bpy.ops.object.modifier_apply(modifier=mod.name)
    mesh = cutter.data
    bpy.data.objects.remove(cutter)
    bpy.data.meshes.remove(mesh)


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
    bmesh.ops.dissolve_limit(bm, angle_limit=math.radians(0.5), verts=bm.verts[:], edges=bm.edges[:],
                             delimit={"MATERIAL"})
    bmesh.ops.triangulate(bm, faces=bm.faces[:], quad_method="BEAUTY", ngon_method="BEAUTY")
    bmesh.ops.dissolve_degenerate(bm, dist=1e-6, edges=bm.edges[:])
    # Dissolving can fuse a sliver into its neighbour as a quad with three corners in a line;
    # split those again so the sliver check below sees every triangle the exporter will write.
    bmesh.ops.triangulate(bm, faces=[f for f in bm.faces if len(f.verts) > 3])
    # Anything still without area covers no pixels; dropping it leaves a zero-width seam.
    # Judged by height over base, so a needle as long as the plug counts too.
    def is_sliver(face):
        longest = max(e.calc_length() for e in face.edges)
        return longest < 1e-7 or 2.0 * face.calc_area() / longest < 1e-6

    slivers = [f for f in bm.faces if is_sliver(f)]
    if slivers:
        bmesh.ops.delete(bm, geom=slivers, context="FACES_ONLY")
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    bm.to_mesh(obj.data)
    bm.free()


def finish(obj, origin, sharp_degrees=40.0, parent=None, parent_origin=(0.0, 0.0, 0.0)):
    """Move the object origin to `origin` (glTF space), shade it, and parent it."""
    clean(obj)
    o = B(origin)
    obj.data.transform(Matrix.Translation(-o))
    obj.location = o - B(parent_origin) if parent else o
    if parent:
        obj.parent = parent
    for poly in obj.data.polygons:
        poly.use_smooth = True
    obj.data.set_sharp_from_angle(angle=math.radians(sharp_degrees))
    obj.data.update()
    return obj


def material(name, rgb, metallic, roughness, alpha=1.0):
    m = bpy.data.materials.new(name)
    try:
        m.use_nodes = True
    except Exception:
        pass
    bsdf = next((n for n in m.node_tree.nodes if n.type == "BSDF_PRINCIPLED"), None)
    if bsdf is None:
        bsdf = m.node_tree.nodes.new("ShaderNodeBsdfPrincipled")
        out = m.node_tree.nodes.new("ShaderNodeOutputMaterial")
        m.node_tree.links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
    bsdf.inputs["Base Color"].default_value = (*rgb, 1.0)
    bsdf.inputs["Metallic"].default_value = metallic
    bsdf.inputs["Roughness"].default_value = roughness
    bsdf.inputs["Alpha"].default_value = alpha
    return m


# ── Scene ─────────────────────────────────────────────────────────────────────────────────────

bpy.ops.wm.read_factory_settings(use_empty=True)

# Linear-space base colours. Metals carry their colour in F0, so these are reflectances.
M_ANODIZED = material("Anodized_Aluminum", (0.085, 0.095, 0.125), 1.0, 0.45)
M_RAW_ALU = material("Milled_Aluminum", (0.80, 0.81, 0.82), 1.0, 0.38)
M_BRASS = material("Brushed_Brass", (0.910, 0.778, 0.423), 1.0, 0.28)
M_PIN_BRASS = material("Polished_Brass", (0.960, 0.700, 0.300), 1.0, 0.16)
M_STEEL = material("Hardened_Steel", (0.62, 0.63, 0.64), 1.0, 0.18)
M_NICKEL = material("Nickel_Silver", (0.66, 0.61, 0.53), 1.0, 0.30)
M_BRONZE = material("Phosphor_Bronze", (0.72, 0.43, 0.22), 1.0, 0.35)
M_TOOL = material("Tool_Steel", (0.50, 0.51, 0.52), 1.0, 0.36)
M_GRIP = material("Tool_Grip", (0.020, 0.020, 0.022), 0.0, 0.70)
M_BENCH = material("Bench_Mat", (0.030, 0.032, 0.036), 0.0, 0.88)

# Body ----------------------------------------------------------------------------------------


def build_body(bm):
    box(bm, (-BODY_X, BODY_Y0, -BODY_Z), (BODY_X, BODY_Y1, BODY_Z))
    bmesh.ops.bevel(bm, geom=bm.edges[:], offset=0.020, segments=3, profile=0.5, affect="EDGES")


body = make_object("Body_Cutaway", build_body, [M_ANODIZED, M_RAW_ALU])
# Plug bore, straight through.
cut(body, lambda bm: cylinder_z(bm, BORE_R, -BODY_Z - 0.05, BODY_Z + 0.05, 40))
# Pin chambers: from inside the bore up into the bible.
for z in PIN_Z:
    cut(body, lambda bm, z=z: lathe(bm, [(CHAMBER_R, 0.0), (CHAMBER_R, CHAMBER_TOP)], 20, (0, 0, z)))
# Shackle bores.
cut(body, lambda bm: lathe(bm, [(SHACKLE_R + 0.003, SHACKLE_HEEL_Y - 0.008), (SHACKLE_R + 0.003, BODY_Y1 + 0.05)], 20, (-SHACKLE_X, 0, 0)))
cut(body, lambda bm: lathe(bm, [(SHACKLE_R + 0.003, SHACKLE_TOE_Y - 0.008), (SHACKLE_R + 0.003, BODY_Y1 + 0.05)], 20, (SHACKLE_X, 0, 0)))
# The cutaway: section the +X half through the pin centreline, open to the keyway face so the
# stacks can be seen from a three-quarter view and not only square-on. The back wall stays as
# a backdrop. Milled after anodising, so these faces are bare aluminium.
cut(body, lambda bm: box(bm, (0.0, BODY_Y0 + 0.028, -WINDOW_Z), (BODY_X + 0.05, CHAMBER_TOP + 0.008, BODY_Z + 0.05)), material_index=1)
finish(body, (0.0, 0.0, 0.0))
body["role"] = "housing"

# Plug ----------------------------------------------------------------------------------------

plug = make_object("Core_Plug", lambda bm: cylinder_z(bm, PLUG_R, PLUG_Z0, PLUG_Z1, 40), [M_BRASS])
cut(plug, lambda bm: box(bm, (-KEYWAY_HALF_W, KEYWAY_Y0, PLUG_Z0 + 0.015), (KEYWAY_HALF_W, KEYWAY_Y1, PLUG_Z1 + 0.05)))
for z in PIN_Z:
    cut(plug, lambda bm, z=z: lathe(bm, [(CHAMBER_R, PIN_REST_Y + 0.008), (CHAMBER_R, PLUG_R + 0.05)], 20, (0, 0, z)))
cut(plug, lambda bm: box(bm, (0.0, -PLUG_R - 0.05, -WINDOW_Z), (PLUG_R + 0.05, PLUG_R + 0.05, WINDOW_Z)))
finish(plug, (0.0, 0.0, 0.0))
plug["shear_y"] = SHEAR_Y
plug["pin_rest_y"] = PIN_REST_Y
plug["pick_fulcrum_z"] = PICK_FULCRUM_Z
plug["open_degrees"] = -90.0
plug["chamber_top"] = CHAMBER_TOP
plug["driver_length"] = DRIVER_LEN

# Pin stacks ----------------------------------------------------------------------------------

for i, (z, key_len) in enumerate(zip(PIN_Z, KEY_PIN_LEN), start=1):
    key_profile = [(0.005, 0.0), (PIN_R, 0.014), (PIN_R, key_len - 0.003), (PIN_R - 0.003, key_len)]
    key = make_object(f"KeyPin_{i}", lambda bm, p=key_profile, z=z: lathe(bm, p, 16, (0, PIN_REST_Y, z)), [M_PIN_BRASS])
    finish(key, (0.0, PIN_REST_Y, z), parent=plug)
    lift = SHEAR_Y - (PIN_REST_Y + key_len)
    key["pin"] = i
    key["length"] = key_len
    key["lift_to_shear"] = lift

    base = PIN_REST_Y + key_len
    d = DRIVER_LEN
    if i in SPOOLS:
        driver_profile = [(PIN_R - 0.003, 0.0), (PIN_R, 0.003), (PIN_R, 0.015), (0.009, 0.020),
                          (0.009, d - 0.020), (PIN_R, d - 0.015), (PIN_R, d - 0.003), (PIN_R - 0.003, d)]
    else:
        driver_profile = [(PIN_R - 0.003, 0.0), (PIN_R, 0.003), (PIN_R, d - 0.003), (PIN_R - 0.003, d)]
    driver = make_object(f"DriverPin_{i}", lambda bm, p=driver_profile, z=z, b=base: lathe(bm, p, 16, (0, b, z)), [M_NICKEL])
    finish(driver, (0.0, base, z))
    driver["pin"] = i
    driver["spool"] = i in SPOOLS

    rest = CHAMBER_TOP - (base + d)
    turns, per_turn = 6, 12
    helix = []
    for s in range(turns * per_turn + 1):
        a = 2 * math.pi * s / per_turn
        helix.append((0.0115 * math.cos(a), CHAMBER_TOP - rest * s / (turns * per_turn), z + 0.0115 * math.sin(a)))
    spring = make_object(f"Spring_{i}", lambda bm, h=helix: tube(bm, h, 0.0038, 5), [M_BRONZE])
    finish(spring, (0.0, CHAMBER_TOP, z), sharp_degrees=80.0)
    spring["pin"] = i
    spring["rest_length"] = rest

# Shackle -------------------------------------------------------------------------------------

shackle_path = [(-SHACKLE_X, SHACKLE_HEEL_Y, 0.0), (-SHACKLE_X, SHACKLE_ARC_Y - 0.06, 0.0)]
ARC_STEPS = 22
for s in range(ARC_STEPS + 1):
    a = math.pi - math.pi * s / ARC_STEPS
    shackle_path.append((SHACKLE_X * math.cos(a), SHACKLE_ARC_Y + SHACKLE_X * math.sin(a), 0.0))
shackle_path += [(SHACKLE_X, SHACKLE_ARC_Y - 0.06, 0.0), (SHACKLE_X, SHACKLE_TOE_Y, 0.0)]
shackle = make_object("Shackle", lambda bm: tube(bm, shackle_path, SHACKLE_R, 14), [M_STEEL])
finish(shackle, (-SHACKLE_X, BODY_Y1, 0.0), sharp_degrees=60.0)
shackle["toe_engagement"] = BODY_Y1 - SHACKLE_TOE_Y

# Tools ---------------------------------------------------------------------------------------

WRENCH_Y = KEYWAY_Y0 + 0.007
wrench_origin = (0.0, WRENCH_Y, PLUG_Z1)
# An L seen from above: the short leg sits in the bottom of the keyway, the handle runs along -X
# just proud of the plug face. The plug turns clockwise seen from the front, so the handle
# swings upward and stays clear of both the bench and the cutaway window.
wrench_outline = [(0.008, PLUG_Z1 - 0.060), (-0.008, PLUG_Z1 - 0.060), (-0.008, PLUG_Z1 + 0.004),
                  (-0.270, PLUG_Z1 + 0.004), (-0.270, PLUG_Z1 + 0.018), (0.008, PLUG_Z1 + 0.018)]
wrench = make_object("TensionWrench", lambda bm: prism_xz(bm, wrench_outline, WRENCH_Y - 0.005, WRENCH_Y + 0.005), [M_TOOL])
finish(wrench, wrench_origin, parent=plug)

# Hook pick, authored with its tip at the origin and its shaft running out along +Z.
pick_outline = [  # (z, y)
    (-0.0015, 0.000), (0.0035, 0.000), (0.008, -0.013), (0.270, -0.013), (0.270, -0.020),
    (-0.004, -0.020), (-0.006, -0.013),
]
pick = make_object("HookPick", lambda bm: prism_yz(bm, pick_outline, -0.004, 0.004), [M_TOOL, M_GRIP])


def build_grip(bm):
    box(bm, (-0.007, -0.030, 0.262), (0.007, -0.004, 0.470))
    bmesh.ops.bevel(bm, geom=bm.edges[:], offset=0.003, segments=2, profile=0.5, affect="EDGES")


grip = make_object("_grip", build_grip, [M_TOOL, M_GRIP], material_index=1)
with bpy.context.temp_override(object=pick, active_object=pick, selected_objects=[pick, grip], selected_editable_objects=[pick, grip]):
    bpy.ops.object.join()
finish(pick, (0.0, 0.0, 0.0))
# Parked just outside the keyway mouth until a pin is selected.
pick.location = B((0.0, PIN_REST_Y - 0.006, PLUG_Z1 + 0.030))

# Bench ---------------------------------------------------------------------------------------

bench = make_object("Bench", lambda bm: lathe(bm, [(0.74, -0.030), (0.75, -0.022), (0.75, -0.004), (0.746, 0.0)], 56, (0, BODY_Y0, 0)), [M_BENCH])
finish(bench, (0.0, BODY_Y0, 0.0))

# ── Export ────────────────────────────────────────────────────────────────────────────────────

os.makedirs(os.path.dirname(OUT), exist_ok=True)
bpy.ops.export_scene.gltf(
    filepath=OUT,
    export_format="GLB",
    export_yup=True,
    export_apply=True,
    export_extras=True,
    export_normals=True,
    export_tangents=False,
    export_texcoords=False,
    export_materials="EXPORT",
    export_cameras=False,
    export_lights=False,
    export_animations=False,
    export_skins=False,
    export_morph=False,
    use_selection=False,
)


def patch_glb(path):
    """The housing must load as a blended material so the app can ghost it at runtime; the
    exporter only writes BLEND when alpha is already below 1."""
    with open(path, "rb") as f:
        data = f.read()
    magic, version, _ = struct.unpack_from("<III", data, 0)
    assert magic == 0x46546C67 and version == 2
    json_len, json_type = struct.unpack_from("<II", data, 12)
    assert json_type == 0x4E4F534A
    doc = json.loads(data[20:20 + json_len])
    rest = data[20 + json_len:]
    for m in doc["materials"]:
        if m["name"] in ("Anodized_Aluminum", "Milled_Aluminum"):
            m["alphaMode"] = "BLEND"
    doc["asset"]["extras"] = {"units_per_meter": 10, "up": "+Y", "keyway_faces": "+Z", "cutaway_side": "+X"}
    payload = json.dumps(doc, separators=(",", ":")).encode("utf-8")
    payload += b" " * (-len(payload) % 4)
    out = struct.pack("<III", magic, 2, 12 + 8 + len(payload) + len(rest))
    out += struct.pack("<II", len(payload), 0x4E4F534A) + payload + rest
    with open(path, "wb") as f:
        f.write(out)


patch_glb(OUT)
print(f"WROTE {OUT} ({os.path.getsize(OUT)} bytes)")

# ── Optional preview renders (pass a directory after `--`) ────────────────────────────────────

if PREVIEW_DIR:
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    scene = bpy.context.scene
    world = bpy.data.worlds.new("w")
    scene.world = world
    try:
        world.use_nodes = True
    except Exception:
        pass
    bg = next((n for n in world.node_tree.nodes if n.type == "BACKGROUND"), None)
    if bg:
        bg.inputs[0].default_value = (0.55, 0.57, 0.6, 1)
        bg.inputs[1].default_value = 1.0
    sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN"))
    sun.data.energy = 4.0
    sun.rotation_euler = (math.radians(50), 0, math.radians(35))
    scene.collection.objects.link(sun)
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    cam.data.lens = 60
    scene.collection.objects.link(cam)
    scene.camera = cam
    scene.render.resolution_x, scene.render.resolution_y = 1100, 1100
    for engine in ("BLENDER_EEVEE", "BLENDER_EEVEE_NEXT", "BLENDER_WORKBENCH"):
        try:
            scene.render.engine = engine
            break
        except Exception:
            continue
    target = Vector(B((0.0, 0.16, 0.0)))
    for label, eye in (("iso", (1.45, 0.75, 1.0)), ("side", (2.0, 0.10, 0.0)), ("front", (0.0, 0.12, 2.1))):
        cam.location = B(eye)
        cam.rotation_euler = (target - cam.location).to_track_quat("-Z", "Y").to_euler()
        scene.render.filepath = os.path.join(PREVIEW_DIR, f"preview_{label}.png")
        bpy.ops.render.render(write_still=True)
        print("RENDERED", scene.render.filepath)
