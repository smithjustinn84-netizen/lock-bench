package com.lockpicking.bench.di

import com.lockpicking.bench.data.AssetLockAssetRepository
import com.lockpicking.bench.data.LockAssetRepository
import com.lockpicking.bench.ui.bench.LockBenchViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Qualifier for the dispatcher that blocking I/O runs on. */
val IoDispatcher = named("io")

val appModule = module {
  single<CoroutineDispatcher>(IoDispatcher) { Dispatchers.IO }
  single<LockAssetRepository> { AssetLockAssetRepository(androidContext().assets, get(IoDispatcher)) }
  viewModelOf(::LockBenchViewModel)
}
