package com.lockpicking.bench.data

import com.lockpicking.bench.domain.model.LockModel

/** Paths inside `app/src/main/assets`. */
object AssetPaths {
  const val STUDIO_ENVIRONMENT = "environments/studio.hdr"

  /** The .glb `tools/build_padlock.py` writes for [lock]. */
  fun model(lock: LockModel): String = when (lock) {
    LockModel.Trainer -> "models/cutaway_padlock.glb"
    LockModel.Master3 -> "models/master_no3_padlock.glb"
    LockModel.American1100 -> "models/american_1100_padlock.glb"
  }
}
