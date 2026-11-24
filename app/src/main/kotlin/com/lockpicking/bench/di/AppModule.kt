package com.lockpicking.bench.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.lockpicking.bench.data.AssetLockAssetRepository
import com.lockpicking.bench.data.BenchPrefs
import com.lockpicking.bench.data.DataStoreBenchPrefs
import com.lockpicking.bench.data.DataStoreGameRecords
import com.lockpicking.bench.data.GameRecords
import com.lockpicking.bench.data.LockAssetRepository
import com.lockpicking.bench.data.SystemGameClock
import com.lockpicking.bench.domain.game.GameClock
import com.lockpicking.bench.ui.bench.LockBenchViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Qualifier for the dispatcher that blocking I/O runs on. */
val IoDispatcher = named("io")

/**
 * A preferences DataStore in [name], seeded once from the SharedPreferences file of the same
 * name that earlier builds wrote, so nothing saved before the move is lost.
 */
private fun preferencesStore(context: Context, name: String): DataStore<Preferences> =
  PreferenceDataStoreFactory.create(migrations = listOf(SharedPreferencesMigration(context, name))) {
    context.preferencesDataStoreFile(name)
  }

val appModule = module {
  single<CoroutineDispatcher>(IoDispatcher) { Dispatchers.IO }
  single<LockAssetRepository> { AssetLockAssetRepository(androidContext().assets, get(IoDispatcher)) }
  // One DataStore per file for the life of the app: a second one on the same file throws.
  single<GameRecords> { DataStoreGameRecords(preferencesStore(androidContext(), "game_records")) }
  single<BenchPrefs> { DataStoreBenchPrefs(preferencesStore(androidContext(), "bench_prefs")) }
  single<GameClock> { SystemGameClock }
  viewModelOf(::LockBenchViewModel)
}
