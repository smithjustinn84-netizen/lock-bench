"""
Prints the node tree, per-node triangle counts, bounds, materials and extras of a .glb, and
checks it against the Lock Bench export contract (node names, triangle budget). With no path
it checks the trainer.

    python3 tools/inspect_glb.py [path/to/file.glb] [--section]
"""

import json
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
PATH = ARGS[0] if ARGS else os.path.join(
    ROOT, "app", "src", "main", "assets", "models", "cutaway_padlock.glb")

TRIANGLE_BUDGET = 18_000

with open(PATH, "rb") as f:
    data = f.read()
json_len = struct.unpack_from("<I", data, 12)[0]
doc = json.loads(data[20:20 + json_len])
bin_start = 20 + json_len + 8
COMPONENTS = {5120: "b", 5121: "B", 5122: "h", 5123: "H", 5125: "I", 5126: "f"}
WIDTH = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}


def read(index):
    acc = doc["accessors"][index]
    view = doc["bufferViews"][acc["bufferView"]]
    fmt = COMPONENTS[acc["componentType"]]
    n = WIDTH[acc["type"]]
    size = struct.calcsize(fmt) * n
    stride = view.get("byteStride", size)
    base = bin_start + view.get("byteOffset", 0) + acc.get("byteOffset", 0)
    return [struct.unpack_from("<" + fmt * n, data, base + i * stride) for i in range(acc["count"])]


parents = {}
for i, node in enumerate(doc["nodes"]):
    for child in node.get("children", []):
        parents[child] = i

total = 0
print(f"{PATH}  ({len(data) / 1024:.1f} KiB)")
print(f"{'node':<16}{'parent':<12}{'tris':>6}  {'origin':<24}{'materials':<34}extras")
for i, node in enumerate(doc["nodes"]):
    tris, mats = 0, []
    if "mesh" in node:
        for prim in doc["meshes"][node["mesh"]]["primitives"]:
            tris += doc["accessors"][prim["indices"]]["count"] // 3
            mats.append(doc["materials"][prim["material"]]["name"])
    total += tris
    origin = ", ".join(f"{v:+.3f}" for v in node.get("translation", [0, 0, 0]))
    parent = doc["nodes"][parents[i]]["name"] if i in parents else "-"
    print(f"{node['name']:<16}{parent:<12}{tris:>6}  {origin:<24}{'/'.join(mats):<34}{json.dumps(node.get('extras', {}))}")

print()
for m in doc["materials"]:
    pbr = m.get("pbrMetallicRoughness", {})
    print(f"material {m['name']:<20} base={[round(c, 3) for c in pbr.get('baseColorFactor', [1, 1, 1, 1])]} "
          f"metallic={pbr.get('metallicFactor', 1.0):.2f} roughness={pbr.get('roughnessFactor', 1.0):.2f} "
          f"alphaMode={m.get('alphaMode', 'OPAQUE')}")

names = {n["name"] for n in doc["nodes"]}
# A lock has as many stacks as it has key pins, numbered from 1 with no gaps.
pin_count = 0
while f"KeyPin_{pin_count + 1}" in names:
    pin_count += 1
REQUIRED = (["Body_Cutaway", "Shackle", "Core_Plug", "TensionWrench", "HookPick", "KeyPin_1"]
            + [f"{kind}_{i}" for kind in ("KeyPin", "DriverPin", "Spring") for i in range(1, pin_count + 1)])
missing = [n for n in REQUIRED if n not in names]
print()
print(f"triangles: {total} / {TRIANGLE_BUDGET}  {'OK' if total < TRIANGLE_BUDGET else 'OVER BUDGET'}")
print(f"pin stacks: {pin_count}")
print(f"required nodes: {'all present' if not missing else 'MISSING ' + ', '.join(missing)}")


def positions(name):
    node = next(n for n in doc["nodes"] if n["name"] == name)
    out = []
    for prim in doc["meshes"][node["mesh"]]["primitives"]:
        pts = read(prim["attributes"]["POSITION"])
        idx = [i[0] for i in read(prim["indices"])]
        out.append((doc["materials"][prim["material"]]["name"], pts, idx))
    return out


def mesh_health(name):
    """Degenerate triangles and zero-length normals. Either turns into NaN in a lit shader,
    and bloom then smears that one bad pixel into screen-sized blocks."""
    node = next(n for n in doc["nodes"] if n["name"] == name)
    slivers = bad_normals = 0
    for prim in doc["meshes"][node["mesh"]]["primitives"]:
        pts = read(prim["attributes"]["POSITION"])
        normals = read(prim["attributes"]["NORMAL"])
        idx = [i[0] for i in read(prim["indices"])]
        bad_normals += sum(1 for n in normals if not 0.99 < (n[0] ** 2 + n[1] ** 2 + n[2] ** 2) ** 0.5 < 1.01)
        for t in range(0, len(idx), 3):
            a, b, c = (pts[idx[t + k]] for k in range(3))
            u = [b[k] - a[k] for k in range(3)]
            v = [c[k] - a[k] for k in range(3)]
            cross = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
            if (cross[0] ** 2 + cross[1] ** 2 + cross[2] ** 2) ** 0.5 < 1e-9:
                slivers += 1
    return slivers, bad_normals


unhealthy = 0
for node in doc["nodes"]:
    if "mesh" not in node:
        continue
    slivers, bad_normals = mesh_health(node["name"])
    if slivers or bad_normals:
        unhealthy += 1
        print(f"UNHEALTHY {node['name']}: {slivers} degenerate triangles, {bad_normals} non-unit normals")
print("mesh health: " + ("OK" if not unhealthy else f"{unhealthy} nodes need cleaning"))

if "--section" in sys.argv:
    # Sanity-check the cutaway: the plug must have nothing left on +X inside the window, and
    # the housing must have a section face lying on x = 0.
    for name in ("Core_Plug", "Body_Cutaway"):
        for mat, pts, idx in positions(name):
            beyond = sum(1 for x, y, z in pts if x > 0.002 and abs(z) < 0.095)
            on_plane = sum(1 for t in range(0, len(idx), 3)
                           if all(abs(pts[idx[t + k]][0]) < 1e-4 for k in range(3)))
            xs = [p[0] for p in pts]
            print(f"{name:<14}{mat:<20} verts={len(pts):<6} x=[{min(xs):+.3f},{max(xs):+.3f}] "
                  f"+X verts inside window={beyond:<5} tris on x=0 plane={on_plane}")

sys.exit(0 if not missing and total < TRIANGLE_BUDGET and not unhealthy else 1)
