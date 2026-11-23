package com.lockpicking.bench.di

import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

class AppModuleTest {

  /** Every constructor dependency in the graph has a definition. */
  @OptIn(KoinExperimentalAPI::class)
  @Test
  fun `the app module resolves`() {
    appModule.verify()
  }
}
