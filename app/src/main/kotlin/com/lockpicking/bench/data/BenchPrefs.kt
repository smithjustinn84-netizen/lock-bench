package com.lockpicking.bench.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lockpicking.bench.domain.model.LockModel
import com.lockpicking.bench.ui.bench.CameraPreset
import kotlinx.coroutines.flow.first

/** How the open bench was left: what it opens on next launch. */
data class SavedBench(
  val lock: LockModel = LockModel.Trainer,
  val preset: CameraPreset = CameraPreset.ShearSide,
  val hints: Boolean = true,
  val swapHands: Boolean = false,
  val ghost: Boolean = false,
)

/** Bench choices kept between launches. */
interface BenchPrefs {
  /** What was saved, with the defaults for anything never saved (or no longer known). */
  suspend fun load(): SavedBench

  suspend fun saveLock(lock: LockModel)

  suspend fun savePreset(preset: CameraPreset)

  suspend fun saveHints(hints: Boolean)

  suspend fun saveSwapHands(swapHands: Boolean)

  suspend fun saveGhost(ghost: Boolean)
}

class DataStoreBenchPrefs(private val store: DataStore<Preferences>) : BenchPrefs {
  override suspend fun load(): SavedBench {
    val prefs = store.data.first()
    val defaults = SavedBench()
    return SavedBench(
      lock = prefs[LOCK]?.let { id -> LockModel.entries.firstOrNull { it.id == id } } ?: defaults.lock,
      // Only the angles the drawer offers: the blind angle belongs to a round.
      preset = prefs[PRESET]?.let { name -> CameraPreset.entries.firstOrNull { it.name == name && it.inDrawer } } ?: defaults.preset,
      hints = prefs[HINTS] ?: defaults.hints,
      swapHands = prefs[SWAP_HANDS] ?: defaults.swapHands,
      ghost = prefs[GHOST] ?: defaults.ghost,
    )
  }

  override suspend fun saveLock(lock: LockModel) {
    store.edit { it[LOCK] = lock.id }
  }

  override suspend fun savePreset(preset: CameraPreset) {
    store.edit { it[PRESET] = preset.name }
  }

  override suspend fun saveHints(hints: Boolean) {
    store.edit { it[HINTS] = hints }
  }

  override suspend fun saveSwapHands(swapHands: Boolean) {
    store.edit { it[SWAP_HANDS] = swapHands }
  }

  override suspend fun saveGhost(ghost: Boolean) {
    store.edit { it[GHOST] = ghost }
  }

  private companion object {
    val LOCK = stringPreferencesKey("last_lock")
    val PRESET = stringPreferencesKey("camera_preset")
    val HINTS = booleanPreferencesKey("hints")
    val SWAP_HANDS = booleanPreferencesKey("swap_hands")
    val GHOST = booleanPreferencesKey("ghost")
  }
}
