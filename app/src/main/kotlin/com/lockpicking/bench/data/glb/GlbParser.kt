package com.lockpicking.bench.data.glb

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the [GlbInfo] summary out of a binary glTF's JSON chunk. No I/O: give it the bytes. */
object GlbParser {
  private const val GLB_MAGIC = 0x46546C67
  private const val JSON_CHUNK = 0x4E4F534A
  private const val HEADER_BYTES = 20

  // Byte offsets in the 12-byte file header plus the first chunk's 8-byte header.
  private const val JSON_LENGTH_OFFSET = 12
  private const val CHUNK_TYPE_OFFSET = 16
  private const val INDICES_PER_TRIANGLE = 3
  private const val RGBA = 4
  private val WHITE = floatArrayOf(1f, 1f, 1f, 1f)

  fun parse(bytes: ByteArray, name: String = "asset"): GlbInfo {
    require(bytes.size >= HEADER_BYTES) { "$name is too short to be a binary glTF" }
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(header.getInt(0) == GLB_MAGIC) { "$name is not a binary glTF" }
    val jsonLength = header.getInt(JSON_LENGTH_OFFSET)
    require(header.getInt(CHUNK_TYPE_OFFSET) == JSON_CHUNK) { "$name: first chunk is not JSON" }
    val doc = JSONObject(String(bytes, HEADER_BYTES, jsonLength, Charsets.UTF_8))
    val meshes = meshCounts(doc)
    return GlbInfo(
      triangles = meshes.triangles,
      primitives = meshes.primitives,
      nodes = meshes.nodes,
      trianglesByNode = meshes.trianglesByNode,
      baseColors = baseColors(doc),
    )
  }

  private class MeshCounts(val triangles: Int, val primitives: Int, val nodes: Int, val trianglesByNode: Map<String, Int>)

  private fun meshCounts(doc: JSONObject): MeshCounts {
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
        nodeTriangles += accessors.getJSONObject(prim.getInt("indices")).getInt("count") / INDICES_PER_TRIANGLE
        primitives++
      }
      byNode[node.optString("name", "node$n")] = nodeTriangles
      triangles += nodeTriangles
    }
    return MeshCounts(triangles, primitives, nodes.length(), byNode)
  }

  private fun baseColors(doc: JSONObject): Map<String, FloatArray> {
    val materials = doc.optJSONArray("materials") ?: return emptyMap()
    val colors = HashMap<String, FloatArray>()
    for (m in 0 until materials.length()) {
      val material = materials.getJSONObject(m)
      val factor = material.optJSONObject("pbrMetallicRoughness")?.optJSONArray("baseColorFactor")
      colors[material.optString("name")] =
        if (factor == null) WHITE.copyOf() else FloatArray(RGBA) { factor.getDouble(it).toFloat() }
    }
    return colors
  }
}
