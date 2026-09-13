package com.example.birdingsoundmvp.planning

import com.example.birdingsoundmvp.i18n.AppText

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.birdingsoundmvp.taxonomy.BirdTaxonomyRepository
import com.example.birdingsoundmvp.trip.TripHistoryRepository
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PlansTripsSection { PLANS, TRIPS }

sealed class PlansTripsRoute {
    object Overview : PlansTripsRoute()
    data class PlanDetail(val planId: Long) : PlansTripsRoute()
    data class PlanEditor(val planId: Long?) : PlansTripsRoute()
}

enum class PlanSpeciesSort { COMBINED, HISTORICAL, CURRENT }

data class PlanEditorUiState(
    val planId: Long? = null,
    val name: String = "",
    val plannedDate: String = LocalDate.now().toString(),
    val countryCode: String = "CN",
    val countryName: String = "中国",
    val regionCode: String = "CN",
    val regionName: String = "中国",
    val hotspotKeyword: String = "",
    val hotspotResults: List<EbirdHotspotMatch> = emptyList(),
    val selectedHotspot: EbirdHotspotMatch? = null,
    val selectedLocationIds: Set<String> = emptySet(),
    val searchMode: String = "exact",
    val isSearching: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val sourceId: String = "ebird",
    val searchStartDate: String = LocalDate.now().minusDays(29).toString(),
    val searchEndDate: String = LocalDate.now().toString()
)

data class PlanAnalysisTaskUiState(
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val completedDays: Int = 0,
    val totalDays: Int = 0,
    val message: String = "",
    val error: String? = null,
    val warning: String? = null
)

data class TripPickerUiState(
    val isVisible: Boolean = false,
    val query: String = "",
    val selectedTripIds: Set<String> = emptySet()
)

data class PlansTripsUiState(
    val section: PlansTripsSection = PlansTripsSection.PLANS,
    val route: PlansTripsRoute = PlansTripsRoute.Overview,
    val data: PlansTripsData = PlansTripsData(),
    val planDetail: PlanDetailData? = null,
    val editor: PlanEditorUiState = PlanEditorUiState(),
    val countries: List<EbirdRegionOption> = listOf(EbirdRegionOption("CN", "中国")),
    val regions: List<EbirdRegionOption> = emptyList(),
    val countriesLoading: Boolean = false,
    val regionsLoading: Boolean = false,
    val regionError: String? = null,
    val countryError: String? = null,
    val analysisTasks: Map<Long, PlanAnalysisTaskUiState> = emptyMap(),
    val speciesSort: PlanSpeciesSort = PlanSpeciesSort.COMBINED,
    val tripPicker: TripPickerUiState = TripPickerUiState(),
    val isLoading: Boolean = true,
    val isSettling: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val apiKeyConfigured: Boolean = false,
    val showTrash: Boolean = false,
    val trashedPlans: List<Plan> = emptyList()
)

class PlansTripsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PlansTripsRepository(application)
    private val tripHistory = TripHistoryRepository(application)
    private val taxonomy = BirdTaxonomyRepository(application)
    private val ebirdClient = EbirdRecentObservationsClient(cacheDirectory = java.io.File(application.cacheDir, "ebird-reference"))
    private val analysisManager = PlanAnalysisManager.get(application)
    val observationSources = BirdObservationSources.get(application)

    private val _state = MutableStateFlow(PlansTripsUiState())
    val state: StateFlow<PlansTripsUiState> = _state.asStateFlow()
    @Volatile
    private var refreshJob: Job? = null
    private var ebirdApiKey: String = ""
    private var searchJob: Job? = null
    private var countriesJob: Job? = null
    private var regionsJob: Job? = null

    init {
        viewModelScope.launch {
            analysisManager.tasks.collect { tasks ->
                _state.update { state ->
                    state.copy(
                        analysisTasks = tasks.mapValues { (_, task) ->
                            PlanAnalysisTaskUiState(
                                isRunning = task.isRunning,
                                progress = task.progress,
                                completedDays = task.completedDays,
                                totalDays = task.totalDays,
                                message = task.message,
                                error = task.error,
                                warning = task.warning
                            )
                        }
                    )
                }
                if (tasks.values.any { !it.isRunning && it.result != null }) refresh()
            }
        }
        refresh()
    }

    fun setEbirdApiKey(apiKey: String) {
        val changed = ebirdApiKey != apiKey
        ebirdApiKey = apiKey
        _state.update { it.copy(apiKeyConfigured = apiKey.isNotBlank()) }
        if (changed && _state.value.route is PlansTripsRoute.PlanEditor &&
            observationSources.find(_state.value.editor.sourceId)?.capabilities?.requiresApiKey == true) {
            loadCountries()
            loadRegions(_state.value.editor.countryCode)
        }
    }

    fun selectSection(section: PlansTripsSection) {
        _state.update {
            it.copy(
                section = section,
                route = PlansTripsRoute.Overview,
                planDetail = null,
                tripPicker = TripPickerUiState(),
                error = null,
                message = null
            )
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (!com.example.birdingsoundmvp.audio.PlaybackCoordinator.recording &&
                    !com.example.birdingsoundmvp.transfer.DataTransferGate.active &&
                    analysisManager.tasks.value.values.none { it.isRunning }) repository.purgeExpiredPlans()
                val trips = tripHistory.listTrips()
                val data = repository.loadData(trips, tripHistory.storageBytes())
                val planId = (_state.value.route as? PlansTripsRoute.PlanDetail)?.planId
                data to planId?.let { repository.loadPlanDetail(it, trips) }
            }.onSuccess { (data, detail) ->
                val trash = repository.listPlans(includeDeleted = true).filter { it.deletedAtMs != null }
                _state.update { it.copy(data = data, planDetail = detail, isLoading = false, error = null, trashedPlans = trash) }
            }.onFailure { error ->
                _state.update { it.copy(isLoading = false, error = error.userMessage(AppText.get("Could not load plans and trips"))) }
            }
        }
    }

    fun showTrash(show: Boolean) { _state.update { it.copy(showTrash = show) }; if (show) refresh() }

    fun restorePlan(planId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.restorePlan(planId) }
                .onSuccess { _state.update { it.copy(message = "计划已恢复") }; refresh() }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    fun openPlan(planId: Long) {
        _state.update {
            it.copy(
                route = PlansTripsRoute.PlanDetail(planId),
                planDetail = null,
                tripPicker = TripPickerUiState(),
                isLoading = true,
                error = null,
                message = null
            )
        }
        refresh()
    }

    fun openNewPlan() {
        _state.update {
            it.copy(
                route = PlansTripsRoute.PlanEditor(null),
                editor = PlanEditorUiState(),
                error = null,
                message = null
            )
        }
        loadCountries()
        loadRegions("CN")
    }

    fun editPlan(planId: Long) {
        val current = _state.value
        val plan = current.planDetail?.plan
            ?: current.data.plans.firstOrNull { it.plan.id == planId }?.plan
            ?: return
        val hotspot = plan.hotspotId.takeIf(String::isNotBlank)?.let { plan.location }
        val scope = runCatching { com.google.gson.JsonParser.parseString(plan.sourceLocationJson).asJsonObject }.getOrNull()
        _state.update {
            it.copy(
                route = PlansTripsRoute.PlanEditor(planId),
                editor = PlanEditorUiState(
                    planId = planId,
                    sourceId = plan.sourceId,
                    name = plan.name,
                    plannedDate = plan.plannedDate,
                    countryCode = plan.location.countryCode,
                    countryName = countryName(plan.location.countryCode, it.countries),
                    regionCode = plan.regionCode,
                    regionName = plan.regionName.ifBlank { regionName(plan.regionCode, it.regions) },
                    selectedHotspot = hotspot,
                    hotspotKeyword = scope?.get("keyword")?.asString.orEmpty(),
                    searchMode = scope?.get("mode")?.asString?.takeIf { mode -> mode in setOf("exact", "fuzzy") }
                        ?: if (plan.sourceId == "ebird") "exact" else "fuzzy"
                ),
                error = null,
                message = null
            )
        }
        loadCountries()
        loadRegions(plan.location.countryCode)
    }

    fun navigateBack() {
        cancelLocationSearch()
        countriesJob?.cancel(); regionsJob?.cancel()
        _state.update { it.copy(countriesLoading = false, regionsLoading = false) }
        val current = _state.value.route
        when (current) {
            is PlansTripsRoute.PlanEditor -> current.planId?.let(::openPlan) ?: run {
                _state.update { it.copy(route = PlansTripsRoute.Overview, editor = PlanEditorUiState()) }
            }
            is PlansTripsRoute.PlanDetail -> _state.update {
                it.copy(route = PlansTripsRoute.Overview, planDetail = null, tripPicker = TripPickerUiState())
            }
            PlansTripsRoute.Overview -> Unit
        }
    }

    fun updateEditorName(value: String) = updateEditor { copy(name = value, error = null) }

    fun updateEditorDate(value: String) = updateEditor { copy(plannedDate = value, error = null) }

    fun updateEditorKeyword(value: String) = updateSearchCriteria { copy(hotspotKeyword = value) }
    fun updateSearchMode(value: String) {
        require(value in setOf("exact", "fuzzy"))
        updateSearchCriteria { copy(searchMode = value) }
    }

    private fun updateSearchCriteria(block: PlanEditorUiState.() -> PlanEditorUiState) {
        searchJob?.cancel()
        updateEditor { block().copy(hotspotResults = emptyList(), selectedHotspot = null,
            selectedLocationIds = emptySet(), isSearching = false, error = null) }
    }

    fun selectSource(id: String) {
        observationSources.require(id)
        searchJob?.cancel(); countriesJob?.cancel(); regionsJob?.cancel()
        _state.update { it.copy(editor = it.editor.copy(sourceId = id, countryCode = "CN", countryName = "中国",
            regionCode = "CN", regionName = "中国", hotspotResults = emptyList(), selectedHotspot = null,
            selectedLocationIds = emptySet(), searchMode = "exact", isSearching = false, error = null),
            countries = emptyList(), regions = emptyList(), countriesLoading = false, regionsLoading = false) }
        loadCountries(); loadRegions("CN")
    }

    fun updateSearchStart(value: String) = updateSearchCriteria { copy(searchStartDate = value) }
    fun updateSearchEnd(value: String) = updateSearchCriteria { copy(searchEndDate = value) }

    fun selectRegion(region: EbirdRegionOption) {
        searchJob?.cancel()
        updateEditor {
            copy(
                regionCode = region.code,
                regionName = region.name,
                hotspotResults = emptyList(),
                selectedHotspot = null,
                selectedLocationIds = emptySet(),
                isSearching = false,
                error = null
            )
        }
    }

    fun selectCountry(country: EbirdRegionOption) {
        searchJob?.cancel()
        updateEditor {
            copy(
                countryCode = country.code,
                countryName = country.name,
                regionCode = country.code,
                regionName = country.name,
                hotspotResults = emptyList(),
                selectedHotspot = null,
                selectedLocationIds = emptySet(),
                isSearching = false,
                error = null
            )
        }
        loadRegions(country.code)
    }

    fun selectHotspot(hotspot: EbirdHotspotMatch) = updateEditor {
        val source = observationSources.require(sourceId)
        if (source.capabilities.supportsSearchModes && searchMode == "fuzzy") {
            val selected = if (hotspot.locationId in selectedLocationIds) selectedLocationIds - hotspot.locationId else selectedLocationIds + hotspot.locationId
            val locations = hotspotResults.filter { it.locationId in selected }
            copy(selectedLocationIds = selected, selectedHotspot = locations.takeIf { it.isNotEmpty() }?.let {
                source.combineLocations(it, hotspotKeyword.trim())
            })
        } else copy(selectedHotspot = hotspot, selectedLocationIds = setOf(hotspot.locationId))
    }

    fun searchHotspots() {
        val editor = _state.value.editor
        val source = observationSources.find(editor.sourceId) ?: run {
            updateEditor { copy(error = "当前版本不支持这个来源") }; return
        }
        if (source.capabilities.requiresApiKey && ebirdApiKey.isBlank()) {
            updateEditor { copy(error = AppText.get("Add your eBird API key in Settings first.")) }
            return
        }
        if (editor.hotspotKeyword.isBlank()) {
            updateEditor { copy(error = AppText.get("Enter a hotspot keyword.")) }
            return
        }
        val dates = runCatching { LocalDate.parse(editor.searchStartDate) to LocalDate.parse(editor.searchEndDate) }.getOrNull()
        if (dates == null || dates.first > dates.second) { updateEditor { copy(error = "查询日期范围不正确") }; return }
        searchJob?.cancel()
        updateEditor { copy(isSearching = true, error = null, hotspotResults = emptyList(), selectedHotspot = null, selectedLocationIds = emptySet()) }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val response = source.searchLocations(
                ebirdApiKey,
                editor.regionCode,
                editor.hotspotKeyword, dates.first, dates.second, editor.searchMode
            )
            if (_state.value.editor.sourceId != editor.sourceId || _state.value.editor.hotspotKeyword != editor.hotspotKeyword ||
                _state.value.editor.regionCode != editor.regionCode || _state.value.editor.searchMode != editor.searchMode ||
                _state.value.editor.searchStartDate != editor.searchStartDate || _state.value.editor.searchEndDate != editor.searchEndDate) return@launch
            when (response) {
                is EbirdHotspotSearchResponse.Success -> updateEditor {
                    val preselect = response.hotspots.takeIf { it.size == 1 ||
                        (source.capabilities.supportsSearchModes && editor.searchMode == "fuzzy") }.orEmpty()
                    copy(
                        isSearching = false,
                        hotspotResults = response.hotspots,
                        selectedHotspot = preselect.takeIf { it.isNotEmpty() }?.let { source.combineLocations(it, editor.hotspotKeyword.trim()) },
                        selectedLocationIds = preselect.map { it.locationId }.toSet(),
                        error = if (response.hotspots.isEmpty()) AppText.get("No matching hotspots found.") else response.cacheNotice
                    )
                }
                is EbirdHotspotSearchResponse.Failure -> updateEditor {
                    copy(isSearching = false, error = response.message)
                }
            }
        }
    }

    fun cancelLocationSearch() {
        searchJob?.cancel()
        searchJob = null
        updateEditor { copy(isSearching = false) }
    }

    fun saveEditor() {
        val editor = _state.value.editor
        val parsedDate = runCatching { LocalDate.parse(editor.plannedDate) }.getOrNull()
        val error = when {
            editor.name.isBlank() -> AppText.get("Enter a plan name.")
            parsedDate == null -> AppText.get("Enter the date as YYYY-MM-DD.")
            editor.selectedHotspot == null -> "请搜索并选择一个具体鸟点"
            else -> null
        }
        if (error != null) {
            updateEditor { copy(error = error) }
            return
        }
        updateEditor { copy(isSaving = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val hotspot = requireNotNull(editor.selectedHotspot)
                editor.planId?.let { id ->
                    val previous = repository.getPlan(id)
                    val scopeChanged = previous != null && (
                        previous.sourceId != hotspot.sourceId || previous.hotspotId != hotspot.locationId ||
                            previous.sourceLocationJson != hotspot.sourceLocationJson ||
                            runCatching { YearMonth.from(LocalDate.parse(previous.plannedDate)) }.getOrNull() !=
                            YearMonth.from(parsedDate)
                        )
                    if (scopeChanged) {
                        analysisManager.cancel(id)
                    }
                    repository.updatePlan(
                        id,
                        editor.name,
                        parsedDate.toString(),
                        editor.regionCode,
                        hotspot,
                        editor.regionName
                    )
                    id
                } ?: repository.createPlan(
                    editor.name,
                    parsedDate.toString(),
                    editor.regionCode,
                    hotspot,
                    editor.regionName
                )
            }.onSuccess { planId ->
                openPlan(planId)
            }.onFailure { throwable ->
                updateEditor { copy(isSaving = false, error = throwable.userMessage(AppText.get("Could not save plan"))) }
            }
        }
    }

    fun deletePlan(planId: Long) {
        analysisManager.cancel(planId)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deletePlan(planId) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            route = PlansTripsRoute.Overview,
                            planDetail = null,
                            analysisTasks = it.analysisTasks - planId,
                            message = "计划已移入回收站，30天内可恢复。"
                        )
                    }
                    refresh()
                }
                .onFailure { error -> _state.update { it.copy(error = error.userMessage(AppText.get("Could not delete plan"))) } }
        }
    }

    fun analyzePlan(planId: Long) {
        analysisManager.start(planId, ebirdApiKey)
    }

    fun cancelAnalysis(planId: Long) {
        analysisManager.cancel(planId)
    }

    fun setSpeciesSort(sort: PlanSpeciesSort) {
        _state.update { it.copy(speciesSort = sort) }
    }

    fun toggleExpected(stat: PlanSpeciesStat, selected: Boolean) = setExpected(
        planId = stat.planId,
        speciesKey = stat.speciesKey,
        speciesCode = stat.speciesCode,
        scientificName = stat.scientificName,
        commonName = stat.commonName,
        displayNameZh = stat.displayNameZh,
        source = "analysis",
        selected = selected
    )

    fun setAllExpected(rare: Boolean, selected: Boolean) {
        val detail = _state.value.planDetail ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val candidates = if (rare) detail.rareObservations.map {
                PlanExpectedSpecies(detail.plan.id, it.speciesKey, it.speciesCode, it.scientificName,
                    it.commonName, it.displayNameZh, "rare", now)
            } else detail.speciesStats.map {
                PlanExpectedSpecies(detail.plan.id, it.speciesKey, it.speciesCode, it.scientificName,
                    it.commonName, it.displayNameZh, "analysis", now)
            }
            runCatching { repository.setExpectedSpeciesBatch(detail.plan.id, candidates, selected) }
                .onSuccess { refresh() }
                .onFailure { error -> _state.update { it.copy(error = error.message ?: AppText.get("清单更新失败")) } }
        }
    }

    fun toggleExpected(rare: PlanRareObservation, selected: Boolean) = setExpected(
        planId = rare.planId,
        speciesKey = rare.speciesKey,
        speciesCode = rare.speciesCode,
        scientificName = rare.scientificName,
        commonName = rare.commonName,
        displayNameZh = rare.displayNameZh,
        source = "rare",
        selected = selected
    )

    fun removeExpected(expected: PlanExpectedSpecies) = setExpected(
        planId = expected.planId,
        speciesKey = expected.speciesKey,
        speciesCode = expected.speciesCode,
        scientificName = expected.scientificName,
        commonName = expected.commonName,
        displayNameZh = expected.displayNameZh,
        source = expected.source,
        selected = false
    )

    private fun setExpected(
        planId: Long,
        speciesKey: String,
        speciesCode: String,
        scientificName: String,
        commonName: String,
        displayNameZh: String?,
        source: String,
        selected: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setExpectedSpecies(
                planId,
                speciesKey,
                speciesCode,
                scientificName,
                commonName,
                displayNameZh,
                source,
                selected
            )
            refresh()
        }
    }

    fun showTripPicker() {
        val linkedIds = _state.value.planDetail?.linkedTrips.orEmpty().map { it.tripId }.toSet()
        _state.update { it.copy(tripPicker = TripPickerUiState(true, selectedTripIds = linkedIds)) }
    }

    fun updateTripPickerQuery(query: String) {
        _state.update { it.copy(tripPicker = it.tripPicker.copy(query = query)) }
    }

    fun toggleTripPickerSelection(tripId: String) {
        _state.update { state ->
            val selected = state.tripPicker.selectedTripIds
            state.copy(
                tripPicker = state.tripPicker.copy(
                    selectedTripIds = if (tripId in selected) selected - tripId else selected + tripId
                )
            )
        }
    }

    fun dismissTripPicker() {
        _state.update { it.copy(tripPicker = TripPickerUiState()) }
    }

    fun saveTripLinks(planId: Long) {
        val selected = _state.value.tripPicker.selectedTripIds
        viewModelScope.launch(Dispatchers.IO) {
            repository.setPlanTrips(planId, selected)
            _state.update { it.copy(tripPicker = TripPickerUiState(), message = AppText.get("Linked trips updated.")) }
            refresh()
        }
    }

    fun settlePlan(planId: Long) {
        if (_state.value.isSettling) return
        _state.update { it.copy(isSettling = true, error = null, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { buildAndSaveSettlement(planId) }
                .onSuccess {
                    _state.update { it.copy(isSettling = false, message = AppText.get("Trip settlement updated.")) }
                    refresh()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(isSettling = false, error = error.userMessage(AppText.get("Could not settle this plan")))
                    }
                }
        }
    }

    fun addManualObservation(planId: Long, name: String) {
        val query = name.trim()
        if (query.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val detail = runCatching { taxonomy.findSpecies(query, query) }.getOrNull()
            val scientific = detail?.scientificName.orEmpty()
            val common = detail?.commonName?.ifBlank { query } ?: query
            val chinese = detail?.chineseName?.takeIf(String::isNotBlank)
                ?: detail?.zhCnName?.takeIf(String::isNotBlank)
            val key = canonicalSpeciesKey("", scientific, common)
            repository.upsertSettlementOverride(
                PlanSettlementOverride(
                    planId,
                    key,
                    "",
                    scientific,
                    common,
                    chinese,
                    PlanSettlementOverrideAction.MANUAL_ADD,
                    System.currentTimeMillis()
                )
            )
            buildAndSaveSettlement(planId)
            refresh()
        }
    }

    fun excludeSettlementSpecies(item: PlanSettlementSpecies) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.upsertSettlementOverride(
                PlanSettlementOverride(
                    item.planId,
                    item.speciesKey,
                    item.speciesCode,
                    item.scientificName,
                    item.commonName,
                    item.displayNameZh,
                    PlanSettlementOverrideAction.EXCLUDE,
                    System.currentTimeMillis()
                )
            )
            buildAndSaveSettlement(item.planId)
            refresh()
        }
    }

    fun removeManualObservation(item: PlanSettlementSpecies) {
        viewModelScope.launch(Dispatchers.IO) {
            val itemAliases = speciesAliases(item.speciesKey, item.scientificName, item.commonName)
            val storedKey = repository.listSettlementOverrides(item.planId)
                .firstOrNull { override ->
                    override.action == PlanSettlementOverrideAction.MANUAL_ADD &&
                        speciesAliases(override.speciesKey, override.scientificName, override.commonName)
                            .any(itemAliases::contains)
                }
                ?.speciesKey
                ?: item.speciesKey
            repository.deleteSettlementOverride(item.planId, storedKey)
            buildAndSaveSettlement(item.planId)
            refresh()
        }
    }

    fun resetSettlementOverrides(planId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearSettlementOverrides(planId)
            buildAndSaveSettlement(planId)
            refresh()
        }
    }

    fun deleteTrip(tripId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val trip = _state.value.data.trips.firstOrNull { it.trip.tripId == tripId }?.trip
            if (trip?.isCompleted != true) {
                _state.update { it.copy(error = AppText.get("Only completed trips can be deleted here.")) }
                return@launch
            }
            runCatching {
                check(tripHistory.deleteTrip(tripId)) { AppText.get("Trip files could not be deleted") }
                repository.removeTripReferences(tripId)
            }.onSuccess {
                _state.update { it.copy(message = AppText.get("Trip deleted.")) }
                refresh()
            }.onFailure { error -> _state.update { it.copy(error = error.userMessage(AppText.get("Could not delete trip"))) } }
        }
    }

    fun deleteTripAudio(tripId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { check(tripHistory.deleteTripAudio(tripId)) { AppText.get("Audio files could not be deleted") } }
                .onSuccess { refresh() }
                .onFailure { error -> _state.update { it.copy(error = error.userMessage(AppText.get("Could not delete audio"))) } }
        }
    }

    fun deleteOldAudio(days: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = tripHistory.deleteAudioOlderThan(days)
            _state.update { it.copy(message = AppText.format("Audio removed from {0} trip{1}.", deleted, if (deleted == 1) "" else "s")) }
            refresh()
        }
    }

    fun markTripUpdated(tripId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.markSettlementsStaleForTrip(tripId)
            refresh()
        }
    }

    fun clearNotice() {
        _state.update { it.copy(error = null, message = null) }
    }

    private fun loadCountries() {
        val source = observationSources.find(_state.value.editor.sourceId) ?: return
        countriesJob?.cancel()
        if (source.capabilities.requiresApiKey && ebirdApiKey.isBlank()) {
            _state.update { it.copy(countriesLoading = false, countryError = "请先在设置中填写并测试 eBird 密钥") }
            return
        }
        _state.update { it.copy(countriesLoading = true, countryError = null) }
        val key = ebirdApiKey
        countriesJob = viewModelScope.launch {
            val result = directoryRequest { source.countries(key) }
            if (_state.value.editor.sourceId != source.id) return@launch
            when (result) {
                is EbirdRegionsResponse.Success -> _state.update { state ->
                    val countries = result.regions.ifEmpty { state.countries }
                    state.copy(
                        countries = countries,
                        countriesLoading = false,
                        countryError = if (result.regions.isEmpty()) "未取得国家列表，请重新加载地区" else result.cacheNotice,
                        editor = state.editor.copy(
                            countryName = countryName(state.editor.countryCode, countries)
                        )
                    )
                }
                is EbirdRegionsResponse.Failure -> _state.update {
                    it.copy(countriesLoading = false, countryError = result.message)
                }
            }
        }
    }

    private fun loadRegions(countryCode: String, force: Boolean = false) {
        val current = _state.value
        val source = observationSources.find(current.editor.sourceId) ?: return
        regionsJob?.cancel()
        if (source.capabilities.requiresApiKey && ebirdApiKey.isBlank()) {
            _state.update { it.copy(regions = emptyList(), regionsLoading = false,
                regionError = "请先在设置中填写并测试 eBird 密钥，才能加载省级地区") }
            return
        }
        _state.update { it.copy(regions = emptyList(), regionsLoading = true, regionError = null) }
        val key = ebirdApiKey
        regionsJob = viewModelScope.launch {
            val result = directoryRequest { source.regions(key, countryCode, force) }
            if (_state.value.editor.sourceId != source.id || _state.value.editor.countryCode != countryCode) return@launch
            when (result) {
                is EbirdRegionsResponse.Success -> _state.update { state ->
                    val parent = EbirdRegionOption(
                        countryCode,
                        countryName(countryCode, state.countries)
                    )
                    val regions = (if (source.id == "ebird") listOf(parent) + result.regions else result.regions)
                        .distinctBy { it.code }
                    state.copy(
                        regions = regions,
                        regionsLoading = false,
                        regionError = if (result.regions.isEmpty()) "未取得省级地区列表，请重新加载地区" else result.cacheNotice,
                        editor = state.editor.copy(
                            regionName = regions.firstOrNull { it.code == state.editor.regionCode }?.name ?: state.editor.regionName
                        )
                    )
                }
                is EbirdRegionsResponse.Failure -> _state.update {
                    it.copy(regionsLoading = false, regionError = result.message)
                }
            }
        }
    }

    fun reloadRegions() {
        loadCountries()
        loadRegions(_state.value.editor.countryCode, force = true)
    }

    private suspend fun directoryRequest(request: suspend () -> EbirdRegionsResponse): EbirdRegionsResponse = try {
        withContext(Dispatchers.IO) { request() }
    } catch (cancel: CancellationException) { throw cancel }
      catch (error: Exception) { EbirdRegionsResponse.Failure(error.message ?: "地区加载失败，请重试") }

    private fun buildAndSaveSettlement(planId: Long) {
        val plan = repository.getPlan(planId) ?: error(AppText.get("Plan no longer exists"))
        val tripIds = repository.listPlanTripLinks(planId).map { it.tripId }.sorted()
        val trips = tripHistory.listTrips().associateBy { it.tripId }
        val detectionSets = tripIds.map { id -> TripDetectionSet(id, tripHistory.loadDetections(id)) }
        val settlement = PlanSettlementEngine.build(
            planId,
            repository.listExpectedSpecies(planId),
            detectionSets,
            repository.listSettlementOverrides(planId)
        )
        val signature = tripIds.joinToString("|") { id ->
            val trip = trips[id]
            "$id:${trip?.lastModifiedMs ?: 0L}:${trip?.detectionsBytes ?: 0L}"
        }
        repository.replaceSettlement(planId, settlement, plan.analysisGeneratedAtMs, signature)
    }

    private fun updateEditor(block: PlanEditorUiState.() -> PlanEditorUiState) {
        _state.update { it.copy(editor = it.editor.block()) }
    }

    private fun regionName(code: String, regions: List<EbirdRegionOption>): String =
        regions.firstOrNull { it.code == code }?.name ?: code

    private fun countryName(code: String, countries: List<EbirdRegionOption>): String =
        countries.firstOrNull { it.code == code }?.name
            ?: if (code == "CN") "中国" else code

    private fun speciesAliases(speciesKey: String, scientificName: String, commonName: String): Set<String> =
        listOf(speciesKey, scientificName, commonName)
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun Throwable.userMessage(fallback: String): String =
        message?.takeIf(String::isNotBlank) ?: fallback

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }

}
