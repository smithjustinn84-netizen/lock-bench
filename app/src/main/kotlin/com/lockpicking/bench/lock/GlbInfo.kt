package com.lockpicking.bench.lock

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the HUD wants to know about the asset that Filament does not hand back once the
 * buffers are uploaded: triangle and primitive counts, and each material's authored base colour.
 * Read straight from the .glb's JSON chunk, so the numbers are the file's, not a guess.
 */
data class GlbInfo(
  val triangles: Int,
  val primitives: Int,
  val nodes: Int,
  val trianglesByNode: Map<String, Int>,
  /** Linear RGBA base colour factor by material name. */
  val baseColors: Map<String, FloatArray>,
) {
  companion object {
    private const val GLB_MAGIC = 0x46546C67
    private const val JSON_CHUNK = 0x4E4F534A

    fun read(context: Context, assetPath: String): GlbInfo {
      val bytes = context.assets.open(assetPath).use { it.readBytes() }
      val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      require(header.getInt(0) == GLB_MAGIC) { "$assetPath is not a binary glTF" }
      val jsonLength = header.getInt(12)
      require(header.getInt(16) == JSON_CHUNK) { "$assetPath: first chunk is not JSON" }
      val doc = JSONObject(String(bytes, 20, jsonLength, Charsets.UTF_8))

      val accessors = doc.getJSONArray("accessors")
      val meshes = doc.getJSONArray("meshes")
      val nodes = doc.getJSONArray("nodes")
      var triangles = 0
      var primitives = 0
      val byNode = LinkedHashMap<String, Int>()
      for (n in 0 until nodes.length()) {
        val node = nodes.getJSONObject(n)
        if (!node.has("mesh")) continue
        val prims = meshes.getJSONObject(node.getInt("mesh")).getJSONArray("primitives")
        var nodeTriangles = 0
        for (p in 0 until prims.length()) {
          val prim = prims.getJSONObject(p)
          nodeTriangles += accessors.getJSONObject(prim.getInt("indices")).getInt("count") / 3
          primitives++
        }
        byNode[node.optString("name", "node$n")] = nodeTriangles
        triangles += nodeTriangles
      }

      val colors = HashMap<String, FloatArray>()
      val materials = doc.optJSONArray("materials")
      if (materials != null) {
        for (m in 0 until materials.length()) {
          val material = materials.getJSONObject(m)
          val factor = material.optJSONObject("pbrMetallicRoughness")?.optJSONArray("baseColorFactor")
          colors[material.optString("name")] =
            if (factor == null) floatArrayOf(1f, 1f, 1f, 1f)
            else FloatArray(4) { factor.getDouble(it).toFloat() }
        }
      }
      return GlbInfo(triangles, primitives, nodes.length(), byNode, colors)
    }
  }
}
