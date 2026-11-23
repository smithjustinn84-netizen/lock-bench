package com.lockpicking.bench.data.glb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlbParserTest {

  @Test
  fun `counts triangles and primitives per mesh node`() {
    val info = GlbParser.parse(glb(SAMPLE_JSON))
    assertEquals(16, info.triangles)
    assertEquals(2, info.primitives)
    assertEquals(2, info.nodes)
    assertEquals(mapOf("Body" to 16), info.trianglesByNode)
  }

  @Test
  fun `reads base colours and defaults a material without one to white`() {
    val info = GlbParser.parse(glb(SAMPLE_JSON))
    assertArrayEquals(floatArrayOf(0.9f, 0.6f, 0.2f, 1f), info.baseColors.getValue("Brass"), 1e-6f)
    assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f), info.baseColors.getValue("Plain"), 0f)
  }

  @Test
  fun `rejects a file that is not binary glTF`() {
    val bytes = glb(SAMPLE_JSON).also { it[0] = 0 }
    assertThrows(IllegalArgumentException::class.java) { GlbParser.parse(bytes) }
  }

  @Test
  fun `rejects a truncated file`() {
    assertThrows(IllegalArgumentException::class.java) { GlbParser.parse(ByteArray(8)) }
  }

  /** A minimal GLB: the 12-byte header and one JSON chunk, padded to four bytes with spaces. */
  private fun glb(json: String): ByteArray {
    val padded = json.padEnd((json.length + 3) / 4 * 4, ' ').toByteArray(Charsets.UTF_8)
    return ByteBuffer.allocate(20 + padded.size).order(ByteOrder.LITTLE_ENDIAN)
      .putInt(0x46546C67) // "glTF"
      .putInt(2)
      .putInt(20 + padded.size)
      .putInt(padded.size)
      .putInt(0x4E4F534A) // "JSON"
      .put(padded)
      .array()
  }

  private companion object {
    val SAMPLE_JSON = """
      {
        "accessors": [{"count": 36}, {"count": 12}],
        "meshes": [{"primitives": [{"indices": 0}, {"indices": 1}]}],
        "nodes": [{"name": "Body", "mesh": 0}, {"name": "Empty"}],
        "materials": [
          {"name": "Brass", "pbrMetallicRoughness": {"baseColorFactor": [0.9, 0.6, 0.2, 1.0]}},
          {"name": "Plain"}
        ]
      }
    """.trimIndent()
  }
}
