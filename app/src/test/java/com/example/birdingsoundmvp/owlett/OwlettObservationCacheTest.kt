package com.example.birdingsoundmvp.owlett

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OwlettObservationCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private fun obj(value: String) = JsonParser.parseString(value).asJsonObject
    private val criteria = obj("""{"start":"2026-09-01","locationId":"test"}""")
    private val data = obj("""{"records":[{"commonName":"example","locationName":"place"}],"failedDates":[]}""")

    @Test fun reusesAcrossInstancesAndOrderOfCriteriaButNotConversationOrSource() = runBlocking {
        val dir = folder.newFolder()
        val cache = OwlettObservationCache(dir)
        var requests = 0
        val first = cache.load(1, "observations", "ebird", criteria) { requests++; data }
        val second = OwlettObservationCache(dir).load(1, "observations", "ebird",
            obj("""{"locationId":"test","start":"2026-09-01"}""")) { error("must not fetch") }
        assertTrue(second.reused)
        assertEquals(first.query.id, second.query.id)
        cache.load(2, "observations", "ebird", criteria) { requests++; data }
        cache.load(1, "observations", "alternate-test-source", criteria) { requests++; data }
        assertEquals(3, requests)
    }
    @Test fun cachedQueryCanBeReadWithoutNetworkAndDeletedWithConversation() = runBlocking {
        val cache = OwlettObservationCache(folder.newFolder())
        val result = cache.load(1, "observations", "alternate-test-source", criteria) { data }
        assertEquals(data, cache.get(1, result.query.id)!!.data)
        assertNull(cache.get(2, result.query.id))
        assertEquals(1, cache.list(1).size)
        cache.delete(1)
        assertTrue(cache.list(1).isEmpty())
        assertTrue(runCatching { cache.get(1, "../../other") }.isFailure)
    }
    @Test fun expiryRefreshAndFailedRefreshKeepLastGoodSnapshot() = runBlocking {
        var now = System.currentTimeMillis()
        val cache = OwlettObservationCache(folder.newFolder()) { now }
        val first = cache.load(1, "observations", "alternate-test-source", criteria) { data }
        assertTrue(runCatching { cache.load(1, "observations", "alternate-test-source", criteria, true) { error("network failed") } }.isFailure)
        assertNotNull(cache.get(1, first.query.id))
        val changed = obj("""{"records":[],"failedDates":[]}""")
        assertFalse(cache.load(1, "observations", "alternate-test-source", criteria, true) { changed }.reused)
        assertEquals(changed, cache.get(1, first.query.id)!!.data)
        now += OwlettObservationCache.TTL_MS + 1
        assertNull(cache.get(1, first.query.id))
        assertFalse(cache.load(1, "observations", "alternate-test-source", criteria) { data }.reused)
    }
    @Test fun partialResponsesAndCancelledLoadsAreNeverSuccessfulSnapshots() = runBlocking {
        val cache = OwlettObservationCache(folder.newFolder())
        val partial = obj("""{"records":[],"failedDates":["2026-09-02"]}""")
        val read = cache.load(1, "observations", "ebird", criteria) { partial }
        assertFalse(read.stored)
        assertTrue(cache.list(1).isEmpty())
        val started = CompletableDeferred<Unit>()
        val job = launch { cache.load(1, "observations", "ebird", criteria) { started.complete(Unit); awaitCancellation() } }
        started.await(); job.cancelAndJoin()
        assertTrue(cache.list(1).isEmpty())
    }
    @Test fun concurrentSameQueryRunsOnlyOneFetch() = runBlocking {
        val cache = OwlettObservationCache(folder.newFolder())
        var requests = 0
        val results = (1..3).map { async { cache.load(1, "observations", "alternate-test-source", criteria) { requests++; delay(20); data } } }.awaitAll()
        assertEquals(1, requests)
        assertEquals(2, results.count { it.reused })
    }
}
