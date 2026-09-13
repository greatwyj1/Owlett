package com.example.birdingsoundmvp.owlett

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BirdCallAudioCache private constructor(context: Context) {
    val cache = SimpleCache(File(context.cacheDir, "bird-call-audio"),
        LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024), StandaloneDatabaseProvider(context))
    val dataSourceFactory = DefaultDataSource.Factory(context,
        CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR))
    val bytes: Long get() = cache.cacheSpace
    // The caller releases its player before clearing, so an active writer cannot refill it.
    fun clear() { cache.keys.toList().forEach(cache::removeResource) }

    companion object {
        @Volatile private var instance: BirdCallAudioCache? = null
        fun get(context: Context): BirdCallAudioCache = instance ?: synchronized(this) {
            instance ?: BirdCallAudioCache(context.applicationContext).also { instance = it }
        }
    }
}

data class BirdCallPlayback(
    val recordingId: String? = null,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val wantsPlayback: Boolean = false,
    val bufferedPercent: Int? = null
)
