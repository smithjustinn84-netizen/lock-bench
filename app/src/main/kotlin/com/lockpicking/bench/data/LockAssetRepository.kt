package com.lockpicking.bench.data

import android.content.res.AssetManager
import com.lockpicking.bench.data.glb.GlbInfo
import com.lockpicking.bench.data.glb.GlbParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Where the bench gets facts about its 3D model. */
interface LockAssetRepository {
  /** Mesh counts and authored material colours of the padlock model. */
  suspend fun loadModelInfo(): GlbInfo
}

/** Reads the model from the APK's assets, off the main thread. */
class AssetLockAssetRepository(
  private val assets: AssetManager,
  private val io: CoroutineDispatcher,
) : LockAssetRepository {
  override suspend fun loadModelInfo(): GlbInfo = withContext(io) {
    val bytes = assets.open(AssetPaths.MODEL).use { it.readBytes() }
    GlbParser.parse(bytes, AssetPaths.MODEL)
  }
}
