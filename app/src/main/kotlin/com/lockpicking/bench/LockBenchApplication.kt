package com.lockpicking.bench

import android.app.Application
import com.lockpicking.bench.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class LockBenchApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    startKoin {
      androidLogger()
      androidContext(this@LockBenchApplication)
      modules(appModule)
    }
  }
}
