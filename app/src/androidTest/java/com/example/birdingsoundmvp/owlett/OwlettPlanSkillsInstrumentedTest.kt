package com.example.birdingsoundmvp.owlett

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.birdingsoundmvp.planning.*
import com.example.birdingsoundmvp.taxonomy.BirdTaxonomyRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OwlettPlanSkillsInstrumentedTest {
    @Test fun chineseRegionCreateConfirmationAndActivityRoundTrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "skill_plan_" + UUID.randomUUID() + ".db"
        val repository = PlansTripsRepository(context, databaseName)
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("[{\"code\":\"CN\",\"name\":\"China\"}]"))
                server.enqueue(MockResponse().setBody("[{\"code\":\"CN-11\",\"name\":\"Beijing\"}]"))
                server.enqueue(MockResponse().setBody("[{\"locId\":\"L1\",\"locName\":\"测试鸟点\",\"lat\":40.1,\"lng\":116.2}]"))
                var analysisCount = 0
                val registry = OwlettSkillRegistry.create(repository,
                    EbirdRecentObservationsClient(baseUrl = server.url("/v2").toString(), maxRetries = 0),
                    BirdTaxonomyRepository(context), XenoCantoClient()) { planId, _ ->
                    analysisCount++
                    val snapshot = PlanAnalysisSnapshot(2026, 9, 1, PlanAnalysisCoverage(60, 60, 0, 5, 5, 0), emptyList(), emptyList())
                    repository.replaceAnalysis(planId, snapshot)
                    PlanAnalysisResult.Success(snapshot)
                }
                val skillContext = OwlettSkillContext("plan-test", null, "test-token", null)
                val create = requireNotNull(registry.findBySlash("/plan"))
                val prepared = create.prepare("""{"country_name":"中国","region_name":"北京市","date":"2026-09-06","hotspot_keyword":"测试"}""", skillContext)
                    as OwlettSkillPreparation.WaitingConfirmation
                assertTrue(repository.listPlans().isEmpty())
                val created = create.execute(prepared.normalizedArgumentsJson, skillContext)
                val planId = requireNotNull(created.card.planId)
                create.execute(prepared.normalizedArgumentsJson, skillContext)
                assertEquals(1, repository.listPlans().size)
                assertEquals("CN-11", repository.getPlan(planId)?.regionCode)
                assertEquals("北京", repository.getPlan(planId)?.regionName)
                assertEquals(3, server.requestCount)
                val activity = requireNotNull(registry.findBySlash("/activity"))
                val activityContext = skillContext.copy(recentPlanId = planId, operationId = "analysis-test")
                val preview = activity.prepare("""{"use_recent_plan":true}""", activityContext)
                    as OwlettSkillPreparation.WaitingConfirmation
                assertEquals(0, analysisCount)
                assertEquals(planId, preview.card.planId)
                val result = activity.execute(preview.normalizedArgumentsJson, activityContext)
                assertFalse(result.isError)
                assertEquals("activity_complete", result.card.kind)
                assertTrue(result.toolResponse.contains("65/65"))
                assertEquals(1, analysisCount)
                val picker = activity.prepare("{}", skillContext)
                assertTrue(picker is OwlettSkillPreparation.WaitingInput)
            }
        } finally { repository.close(); context.deleteDatabase(databaseName) }
    }
}
