package com.lockpicking.bench.data

import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.lockpicking.bench.domain.bench.PickTool
import com.lockpicking.bench.domain.game.GameClock
import com.lockpicking.bench.domain.game.GameTier
import com.lockpicking.bench.domain.model.LockModel
import kotlinx.coroutines.flow.first

/**
 * Best times per lock, tier and tool, kept between launches. A lock that was raked is timed
 * against other raked rounds, so a rake cannot wipe out a single-pin record.
 */
interface GameRecords {
  suspend fun bestMillis(lock: LockModel, tier: GameTier, tool: PickTool = PickTool.Hook): Long?

  /** Records [millis] unless a faster time is already kept. */
  suspend fun saveBest(lock: LockModel, tier: GameTier, millis: Long, tool: PickTool = PickTool.Hook)
}

class DataStoreGameRecords(private val store: DataStore<Preferences>) : GameRecords {
  override suspend fun bestMillis(lock: LockModel, tier: GameTier, tool: PickTool): Long? = store.data.first()[key(lock, tier, tool)]

  override suspend fun saveBest(lock: LockModel, tier: GameTier, millis: Long, tool: PickTool) {
    val key = key(lock, tier, tool)
    store.edit { prefs ->
      val kept = prefs[key]
      if (kept == null || millis < kept) prefs[key] = millis
    }
  }

  /**
   * The trainer keeps the keys it had when it was the only lock, and hook times the keys they
   * had when the hook was the only tool, so the records from before carry over.
   */
  private fun key(lock: LockModel, tier: GameTier, tool: PickTool): Preferences.Key<Long> {
    val tierName = tier.name.lowercase()
    val base = if (lock == LockModel.Trainer) "best_$tierName" else "best_${lock.id}_$tierName"
    return longPreferencesKey(if (tool == PickTool.Hook) base else "${base}_${tool.name.lowercase()}")
  }
}

/** Time since boot, which keeps counting through sleep and never jumps with the wall clock. */
object SystemGameClock : GameClock {
  override fun nowMillis(): Long = SystemClock.elapsedRealtime()
}
