package com.lockpicking.bench.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lockpicking.bench.domain.model.LockModel
import kotlinx.coroutines.flow.first

/** Bench choices kept between launches. */
interface BenchPrefs {
  /** The lock last put on the bench, or null if none was ever chosen (or it has since gone). */
  suspend fun lastLock(): LockModel?

  suspend fun saveLastLock(lock: LockModel)
}

class DataStoreBenchPrefs(private val store: DataStore<Preferences>) : BenchPrefs {
  override suspend fun lastLock(): LockModel? {
    val id = store.data.first()[LAST_LOCK] ?: return null
    return LockModel.entries.firstOrNull { it.id == id }
  }

  override suspend fun saveLastLock(lock: LockModel) {
    store.edit { it[LAST_LOCK] = lock.id }
  }

  private companion object {
    val LAST_LOCK = stringPreferencesKey("last_lock")
  }
}
