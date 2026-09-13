package com.example.birdingsoundmvp.planning

import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanAnalysisCoordinatorTest {
    @Test fun sharedAnalysisDeduplicatesAndStoppingAWaiterDoesNotCancelTheTask() = runBlocking {
        val store = FakeStore()
        val gate = CompletableDeferred<Unit>()
        val source = object : PlanEbirdDataSource {
            override suspend fun fetchHistoricObservations(apiKey: String, hotspotId: String, date: LocalDate): EbirdRecentObservationsResponse {
                gate.await()
                return EbirdRecentObservationsResponse.Success(emptyList())
            }
            override suspend fun fetchRecentNotableObservations(apiKey: String, hotspotId: String, daysBack: Int) =
                EbirdRecentObservationsResponse.Success(emptyList())
        }
        val manager = PlanAnalysisManager({ plan }, PlanAnalysisCoordinator(store, source), this)
        val first = manager.start(plan.id, "token")
        assertTrue(first === manager.start(plan.id, "token"))
        val waiter = launch { manager.analyzeAndAwait(plan.id, "token") }
        yield()
        waiter.cancelAndJoin()
        assertTrue(first.isActive)
        gate.complete(Unit)
        assertTrue(first.await() is PlanAnalysisResult.Success)
        assertTrue(manager.tasks.value[plan.id]?.progress == 1f)
    }
    @Test fun authenticationFailureStopsHistoricBatchImmediately() = runBlocking {
        val store = FakeStore()
        val source = FakeSource { _, _ ->
            EbirdRecentObservationsResponse.Failure("密钥无效", statusCode = 401, retryable = false)
        }
        val error = runCatching { PlanAnalysisCoordinator(store, source).analyze(plan, "bad", today) }.exceptionOrNull()
        assertTrue(error is EbirdRequestException)
        assertTrue(source.historicCalls <= 2)
        assertEquals(0, source.notableCalls)
        assertNull(store.savedSnapshot)
    }
    private val today = LocalDate.of(2025, 6, 10)
    private val plan = Plan(
        id = 9L,
        name = "Spring plan",
        plannedDate = "2025-03-15",
        regionCode = "CN-31",
        hotspotId = "L123",
        hotspotName = "City Park",
        hotspotLatitude = null,
        hotspotLongitude = null,
        createdAtMs = 0L,
        updatedAtMs = 0L,
        analysisGeneratedAtMs = null,
        analysisTargetYear = null,
        analysisTargetMonth = null,
        historicalRequestedDays = 0,
        historicalSuccessfulDays = 0,
        historicalActiveDays = 0,
        currentRequestedDays = 0,
        currentSuccessfulDays = 0,
        currentActiveDays = 0
    )

    @Test
    fun cacheHitSkipsHistoricNetworkRequests() = runBlocking {
        val store = FakeStore()
        val dates = PlanAnalysisDatePlanner.datesFor(LocalDate.parse(plan.plannedDate), today)
        (dates.historicalDates + dates.currentDates).forEach { date ->
            store.cache[date] = EbirdDailyCacheEntry(listOf(observation(date)), 10_000L)
        }
        val source = FakeSource { _, _ -> error("network should not be used") }
        val coordinator = PlanAnalysisCoordinator(store, source, nowMillis = { 10_000L })

        val result = coordinator.analyze(plan, "token", today)

        assertTrue(result is PlanAnalysisResult.Success)
        assertEquals(0, source.historicCalls)
        assertEquals(1, source.notableCalls)
        assertNotNull(store.savedSnapshot)
    }

    @Test
    fun retryableFailureIsRetriedAndSuccessfulSnapshotIsSaved() = runBlocking {
        val store = FakeStore()
        val attempts = ConcurrentHashMap<LocalDate, Int>()
        val retryDate = LocalDate.of(2023, 3, 1)
        val source = FakeSource { date, _ ->
            val attempt = attempts.merge(date, 1, Int::plus) ?: 1
            if (date == retryDate && attempt == 1) {
                EbirdRecentObservationsResponse.Failure(
                    "rate limited",
                    statusCode = 429,
                    retryAfterMs = 0L,
                    retryable = true
                )
            } else {
                EbirdRecentObservationsResponse.Success(listOf(observation(date)))
            }
        }
        val coordinator = PlanAnalysisCoordinator(store, source, nowMillis = { 20_000L })

        val result = coordinator.analyze(plan, "token", today)

        assertTrue(result is PlanAnalysisResult.Success)
        assertEquals(2, attempts[retryDate])
        assertNotNull(store.savedSnapshot)
    }

    @Test
    fun belowHalfHistoricalSuccessKeepsPreviousSnapshot() = runBlocking {
        val store = FakeStore()
        val source = FakeSource { date, _ ->
            if (date.dayOfMonth <= 10) {
                EbirdRecentObservationsResponse.Success(listOf(observation(date)))
            } else {
                EbirdRecentObservationsResponse.Failure("offline")
            }
        }
        val coordinator = PlanAnalysisCoordinator(store, source)

        val result = coordinator.analyze(plan, "token", today)

        assertTrue(result is PlanAnalysisResult.Failure)
        assertTrue((result as PlanAnalysisResult.Failure).message.contains("已保留上次结果"))
        assertNull(store.savedSnapshot)
        assertEquals(0, source.notableCalls)
    }

    @Test
    fun atLeastHalfHistoricalSuccessSavesPartialSnapshotWithCoverageWarning() = runBlocking {
        val store = FakeStore()
        val source = FakeSource { date, historical ->
            if (!historical || date.dayOfMonth % 2 == 0 || date.dayOfMonth == 1) {
                EbirdRecentObservationsResponse.Success(listOf(observation(date)))
            } else {
                EbirdRecentObservationsResponse.Failure("temporary failure")
            }
        }
        val coordinator = PlanAnalysisCoordinator(store, source)

        val result = coordinator.analyze(plan, "token", today)

        assertTrue(result is PlanAnalysisResult.Success)
        val snapshot = requireNotNull(store.savedSnapshot)
        assertTrue(snapshot.coverage.historicalSuccessfulDays >= snapshot.coverage.historicalRequestedDays / 2)
        assertTrue(snapshot.warning?.contains("未能查询") == true)
    }

    @Test
    fun historicRequestsNeverExceedTwoConcurrentCalls() = runBlocking(Dispatchers.Default) {
        val store = FakeStore()
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val source = object : PlanEbirdDataSource {
            override suspend fun fetchHistoricObservations(
                apiKey: String,
                hotspotId: String,
                date: LocalDate
            ): EbirdRecentObservationsResponse {
                val concurrent = active.incrementAndGet()
                maximum.updateAndGet { previous -> maxOf(previous, concurrent) }
                Thread.sleep(5L)
                active.decrementAndGet()
                return EbirdRecentObservationsResponse.Success(listOf(observation(date)))
            }

            override suspend fun fetchRecentNotableObservations(
                apiKey: String,
                hotspotId: String,
                backDays: Int
            ) = EbirdRecentObservationsResponse.Success(emptyList())
        }

        val result = PlanAnalysisCoordinator(store, source).analyze(plan, "token", today)

        assertTrue(result is PlanAnalysisResult.Success)
        assertEquals(2, maximum.get())
    }

    private class FakeStore : PlanAnalysisStore {
        val cache = ConcurrentHashMap<LocalDate, EbirdDailyCacheEntry>()
        var savedSnapshot: PlanAnalysisSnapshot? = null

        override fun getCachedObservationDay(
            hotspotId: String,
            observationDate: LocalDate,
            minFetchedAtMs: Long
        ): EbirdDailyCacheEntry? = cache[observationDate]?.takeIf { it.fetchedAtMs >= minFetchedAtMs }

        override fun putCachedObservationDay(
            hotspotId: String,
            observationDate: LocalDate,
            observations: List<EbirdRecentObservation>,
            fetchedAtMs: Long
        ) {
            cache[observationDate] = EbirdDailyCacheEntry(observations, fetchedAtMs)
        }

        override fun replaceAnalysis(planId: Long, snapshot: PlanAnalysisSnapshot) {
            savedSnapshot = snapshot
        }
    }

    private class FakeSource(
        private val history: (LocalDate, Boolean) -> EbirdRecentObservationsResponse
    ) : PlanEbirdDataSource {
        var historicCalls = 0
        var notableCalls = 0

        override suspend fun fetchHistoricObservations(
            apiKey: String,
            hotspotId: String,
            date: LocalDate
        ): EbirdRecentObservationsResponse {
            historicCalls += 1
            val historical = date.year < 2025
            return history(date, historical)
        }

        override suspend fun fetchRecentNotableObservations(
            apiKey: String,
            hotspotId: String,
            backDays: Int
        ): EbirdRecentObservationsResponse {
            notableCalls += 1
            return EbirdRecentObservationsResponse.Success(emptyList())
        }
    }

    private companion object {
        fun observation(date: LocalDate) = EbirdRecentObservation(
            speciesCode = "grtits1",
            scientificName = "Parus major",
            commonName = "Great Tit",
            locationId = "L123",
            locationName = "City Park",
            observedAt = "$date 08:00",
            count = 1
        )
    }
}
