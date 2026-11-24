package com.lockpicking.bench.data

import android.content.Context
import com.lockpicking.bench.domain.model.LockModel

/** Bench choices kept between launches. */
interface BenchPrefs {
  /** The lock last put on the bench, or null if none was ever chosen (or it has since gone). */
  fun lastLock(): LockModel?

  fun saveLastLock(lock: LockModel)
}

class SharedPrefsBenchPrefs(context: Context) : BenchPrefs {
  private val prefs = context.getSharedPreferences("bench_prefs", Context.MODE_PRIVATE)

  override fun lastLock(): LockModel? {
    val id = prefs.getString(KEY_LOCK, null) ?: return null
    return LockModel.entries.firstOrNull { it.id == id }
  }

  override fun saveLastLock(lock: LockModel) {
    prefs.edit().putString(KEY_LOCK, lock.id).apply()
  }

  private companion object {
    const val KEY_LOCK = "last_lock"
  }
}
