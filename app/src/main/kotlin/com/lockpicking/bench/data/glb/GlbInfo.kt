package com.lockpicking.bench.data.glb

/**
 * What the HUD wants to know about the asset that Filament does not hand back once the
 * buffers are uploaded: triangle and primitive counts, and each material's authored base colour.
 * Read straight from the .glb's JSON chunk by [GlbParser], so the numbers are the file's, not a guess.
 */
data class GlbInfo(
  val triangles: Int,
  val primitives: Int,
  val nodes: Int,
  val trianglesByNode: Map<String, Int>,
  /** Linear RGBA base colour factor by material name. */
  val baseColors: Map<String, FloatArray>,
)
