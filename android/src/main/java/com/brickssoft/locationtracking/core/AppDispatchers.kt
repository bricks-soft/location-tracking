package com.brickssoft.locationtracking.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Dispatchers used by all components. Never hard-code `Dispatchers.IO`; inject this instead.
 *
 * @property main Android main thread.
 * @property io SQLite, files and HTTP.
 * @property engine single-threaded dispatcher on which all engine state changes happen.
 */
class AppDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val engine: CoroutineDispatcher,
) {
    companion object {
        val DEFAULT = AppDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default.limitedParallelism(1))
    }
}
