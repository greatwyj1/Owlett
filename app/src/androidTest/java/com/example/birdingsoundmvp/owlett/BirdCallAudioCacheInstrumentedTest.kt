package com.example.birdingsoundmvp.owlett

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BirdCallAudioCacheInstrumentedTest {
    @Test fun repeatPlaybackAndReopenUseDiskWhileClearRequiresUpstreamAgain() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "test-call-cache-${System.nanoTime()}")
        val provider = StandaloneDatabaseProvider(context)
        var cache = SimpleCache(directory, NoOpCacheEvictor(), provider)
        var reads = 0
        val content = ByteArray(8192) { (it % 100).toByte() }
        val upstream = DataSource.Factory {
            val delegate = ByteArrayDataSource(content)
            object : DataSource by delegate {
                override fun open(dataSpec: DataSpec): Long { reads++; return delegate.open(dataSpec) }
            }
        }
        val spec = DataSpec.Builder().setUri(Uri.parse("https://example.org/recording.mp3")).setKey("xc:1").build()
        fun read(): ByteArray {
            val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).createDataSource()
            val output = java.io.ByteArrayOutputStream()
            try {
                source.open(spec)
                val bytes = ByteArray(1024)
                while (true) { val count = source.read(bytes, 0, bytes.size); if (count < 0) break; output.write(bytes, 0, count) }
            } finally { source.close() }
            return output.toByteArray()
        }
        try {
            assertArrayEquals(content, read())
            val afterFirst = reads
            assertTrue(cache.cacheSpace > 0)
            cache.release()
            cache = SimpleCache(directory, NoOpCacheEvictor(), provider)
            assertArrayEquals(content, read())
            assertEquals(afterFirst, reads)
            assertTrue(cache.isCached("xc:1", 0, content.size.toLong()))
            cache.keys.toList().forEach(cache::removeResource)
            assertEquals(0, cache.cacheSpace)
            assertArrayEquals(content, read())
            assertTrue(reads > afterFirst)
        } finally { cache.release(); provider.close(); directory.deleteRecursively() }
    }
}
