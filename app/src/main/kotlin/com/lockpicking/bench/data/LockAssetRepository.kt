package com.lockpicking.bench.data

import android.content.res.AssetManager
import com.lockpicking.bench.data.glb.GlbInfo
import com.lockpicking.bench.data.glb.GlbParser
import com.lockpicking.bench.domain.model.LockModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Where the bench gets facts about its 3D models. */
interface LockAssetRepository {
  /** Mesh counts and authored material colours of [lock]'s model. */
  suspend fun loadModelInfo(lock: LockModel): GlbInfo
}

/** Reads the model from the APK's assets, off the main thread. */
class AssetLockAssetRepository(
  private val assets: AssetManager,
  private val io: CoroutineDispatcher,
) : LockAssetRepository {
  override suspend fun loadModelInfo(lock: LockModel): GlbInfo = withContext(io) {
    val path = AssetPaths.model(lock)
    val bytes = assets.open(path).use { it.readBytes() }
    GlbParser.parse(bytes, path)
  }
}
