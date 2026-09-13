package com.example.birdingsoundmvp.planning

import com.example.birdingsoundmvp.i18n.AppText

import android.app.Application
import com.example.birdingsoundmvp.taxonomy.BirdTaxonomyRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ManagedPlanAnalysisTask(
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val completedDays: Int = 0,
    val totalDays: Int = 0,
    val message: String = "",
    val error: String? = null,
    val warning: String? = null,
    val result: PlanAnalysisResult? = null
)

class PlanAnalysisManager internal constructor(
    private val loadPlan: (Long) -> Plan?,
    private val coordinator: PlanAnalysisCoordinator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val coordinatorForPlan: ((Plan) -> PlanAnalysisCoordinator)? = null
) {
    private val jobs = ConcurrentHashMap<Long, Deferred<PlanAnalysisResult>>()
    private val _tasks = MutableStateFlow<Map<Long, ManagedPlanAnalysisTask>>(emptyMap())
    val tasks: StateFlow<Map<Long, ManagedPlanAnalysisTask>> = _tasks.asStateFlow()

    @Synchronized
    fun start(planId: Long, apiKey: String): Deferred<PlanAnalysisResult> {
        check(!com.example.birdingsoundmvp.transfer.DataTransferGate.active) { "数据迁移期间不能整理鸟况。" }
        jobs[planId]?.takeUnless(Job::isCompleted)?.let { return it }
        val job = scope.async(start = CoroutineStart.LAZY) {
            update(planId, ManagedPlanAnalysisTask(isRunning = true, message = AppText.get("Preparing analysis")))
            try {
                val plan = loadPlan(planId) ?: error(AppText.get("Plan no longer exists."))
                val result = (coordinatorForPlan?.invoke(plan) ?: coordinator).analyze(plan, apiKey) { progress ->
                    update(
                        planId,
                        ManagedPlanAnalysisTask(
                            isRunning = true,
                            progress = progress.fraction,
                            completedDays = progress.completedDays,
                            totalDays = progress.totalDays,
                            message = progress.message
                        )
                    )
                }
                when (result) {
                    is PlanAnalysisResult.Success -> update(
                        planId,
                        ManagedPlanAnalysisTask(
                            progress = 1f,
                            completedDays = result.snapshot.coverage.historicalRequestedDays +
                                result.snapshot.coverage.currentRequestedDays,
                            totalDays = result.snapshot.coverage.historicalRequestedDays +
                                result.snapshot.coverage.currentRequestedDays,
                            message = AppText.get("Bird activity organized"),
                            warning = result.snapshot.warning,
                            result = result
                        )
                    )
                    is PlanAnalysisResult.Failure -> update(
                        planId,
                        ManagedPlanAnalysisTask(error = result.message, result = result)
                    )
                }
                result
            } catch (cancelled: CancellationException) {
                update(planId, ManagedPlanAnalysisTask(message = AppText.get("Analysis cancelled")))
                throw cancelled
            } catch (error: Throwable) {
                val result = PlanAnalysisResult.Failure(error.message ?: AppText.get("Could not organize bird activity"))
                update(planId, ManagedPlanAnalysisTask(error = result.message, result = result))
                result
            } finally {
                jobs.remove(planId)
            }
        }
        jobs[planId] = job
        job.start()
        return job
    }

    suspend fun analyzeAndAwait(planId: Long, apiKey: String): PlanAnalysisResult {
        return try { start(planId, apiKey).await() }
        catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            PlanAnalysisResult.Failure(AppText.get("Bird activity analysis was cancelled."))
        }
    }

    fun cancel(planId: Long) {
        jobs[planId]?.cancel()
    }

    private fun update(planId: Long, task: ManagedPlanAnalysisTask) {
        _tasks.update { it + (planId to task) }
    }

    companion object {
        @Volatile
        private var instance: PlanAnalysisManager? = null

        fun get(application: Application): PlanAnalysisManager = instance ?: synchronized(this) {
            instance ?: run {
                val repository = PlansTripsRepository(application)
                val taxonomy = BirdTaxonomyRepository(application)
                val resolver: (String, String) -> String? = { scientific, common ->
                    runCatching { taxonomy.findSpecies(scientific, common) }.getOrNull()?.let { detail ->
                        detail.chineseName.takeIf(String::isNotBlank) ?: detail.zhCnName.takeIf(String::isNotBlank)
                    }
                }
                val sources = BirdObservationSources.get(application)
                val coordinator = PlanAnalysisCoordinator(
                    repository = repository,
                    client = EbirdRecentObservationsClient(cacheDirectory = java.io.File(application.cacheDir, "ebird-reference")),
                    displayNameResolver = resolver
                )
                PlanAnalysisManager({ repository.getPlan(it) }, coordinator, coordinatorForPlan = { plan ->
                    val source = sources.require(plan.sourceId)
                    PlanAnalysisCoordinator(object : PlanAnalysisStore by repository {
                        override fun replaceAnalysis(planId: Long, snapshot: PlanAnalysisSnapshot) {
                            val current = repository.getPlan(planId) ?: error("计划已删除，未保存鸟况")
                            check(current.location == plan.location &&
                                java.time.YearMonth.parse(current.plannedDate.take(7)) ==
                                java.time.YearMonth.parse(plan.plannedDate.take(7))) { "计划地点或月份已变化，请重新整理" }
                            repository.replaceAnalysis(planId, snapshot)
                        }
                    }, source.observationsFor(plan), resolver,
                        requiresApiKey = source.capabilities.requiresApiKey,
                        useDailyCache = source.id == "ebird", supportsNotable = source.capabilities.supportsNotable)
                })
            }.also { instance = it }
        }
    }
}
