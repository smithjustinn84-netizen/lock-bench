# Lock Bench

An Android lockpicking bench: a 3D cutaway pin-tumbler padlock you can pick with your finger. Put tension on the plug, lift the pins one at a time to the shear line, and watch the mechanism move inside the housing.

Built with Jetpack Compose and [SceneView](https://github.com/SceneView/sceneview) (Filament).

<p align="center">
  <img src="docs/screenshots/cutaway.png" width="24%" alt="Isometric cutaway view of the padlock" />
  <img src="docs/screenshots/shear.png" width="24%" alt="Side-on shear line view of the pin stacks" />
  <img src="docs/screenshots/ghost.png" width="24%" alt="Ghost mode with a translucent housing" />
  <img src="docs/screenshots/exploded.png" width="24%" alt="Exploded view of every part" />
</p>

<p align="center"><sub>Cutaway (ISO) · Shear line · Ghost · Exploded</sub></p>

## How to pick it

1. Hold the **WRENCH** lever with one thumb and pull down. How far you pull is how hard you tension; let go and every pin drops.
2. With another finger, drag up on a pin stack to lift it, or tap it to nudge it to the shear line.
3. The pin that stops the plug binds. Set it and the plug creeps round, moving the bind to the next one. A binding pin drags more the harder you pull, and seizes if you pull too hard.
4. Pins 2 and 3 are spools. Once the standard pins are set the plug drops into a **false set**: it turns much further than a set is worth and hangs on a spool's waist. Keep the pick on that spool and ease the wrench until the plug turns back (counter-rotation) and the pin sets.
5. Lift a pin too far past its set and it oversets and jams. Ease the wrench and it slips back without losing the others.
6. Set all four and the shackle releases. Tap **RESET** to relock.

The **WRENCH** panel shows the torque against the marks where a spool can pass, where it is stuck and where pins seize, and a needle for how far the plug has turned. The **SHEAR LINE** panel shows each stack against the shear line. Sets, false sets and counter-rotation are also felt as vibration and heard.

## Views

| Control | What it does |
| --- | --- |
| **ISO / KEYWAY / SHEAR** | Camera presets. Tap the current one again to snap an orbited camera back. |
| **HINTS** | Names the binding pin and says what the lock is doing. Off leaves you with feel, the gauge and the motion. |
| **GHOST** | Makes the housing translucent. |
| **EXPLODED** / **EXPLODE** slider | Pulls the parts apart. |
| **RESET** | Relocks the padlock. |

Drag anywhere off the pins and the lever to orbit the camera, and pinch to zoom. The camera holds still while the wrench is held.

## Project layout

```
app/src/main/kotlin/com/lockpicking/bench/
  domain/                 pure Kotlin, no Android, unit tested
    lock/                 LockMechanics: the pin-tumbler state machine
    bench/                BenchSimulation: frame-stepped lock, pick, plug and shackle
    model/, motion/       LockGeometry, springs, easing, RigPose, FrameClock
  data/                   LockAssetRepository and the .glb summary parser
  di/AppModule.kt         Koin module
  ui/
    bench/                LockBenchViewModel, the screen, touch routing, haptics and sound
    bench/scene/          SceneView scene and LockRig, which poses the glTF nodes
    components/           shear line panel, wrench lever, control bar, status block, stats pill
    theme/                colours, type and BenchTheme
app/src/main/assets/
  models/cutaway_padlock.glb
  environments/studio.hdr
tools/
  build_padlock.py        Blender script that generates the padlock model
  make_studio_hdr.py      paints the studio lighting HDR
  inspect_glb.py          checks a .glb against the node contract
```

## Building

Requires JDK 17 and an Android SDK with API 37. Min SDK is 28.

```bash
./gradlew installDebug
```

Run the unit tests:

```bash
./gradlew test
```

### Regenerating the assets

The model and the lighting are generated from scripts, so you can change them in code:

```bash
/Applications/Blender.app/Contents/MacOS/Blender --background --python tools/build_padlock.py
```

```bash
python3 tools/make_studio_hdr.py
```

```bash
python3 tools/inspect_glb.py
```
