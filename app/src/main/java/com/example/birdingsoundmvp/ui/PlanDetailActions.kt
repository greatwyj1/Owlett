package com.example.birdingsoundmvp.ui

import com.example.birdingsoundmvp.planning.*

internal data class PlanDetailActions(
    val analyzePlan: (Long) -> Unit,
    val cancelAnalysis: (Long) -> Unit,
    val setSpeciesSort: (PlanSpeciesSort) -> Unit,
    val setAllExpected: (Boolean, Boolean) -> Unit,
    val toggleStat: (PlanSpeciesStat, Boolean) -> Unit,
    val toggleRare: (PlanRareObservation, Boolean) -> Unit,
    val removeExpected: (PlanExpectedSpecies) -> Unit,
    val showTripPicker: () -> Unit,
    val settlePlan: (Long) -> Unit,
    val addManualObservation: (Long, String) -> Unit,
    val resetSettlementOverrides: (Long) -> Unit,
    val removeManualObservation: (PlanSettlementSpecies) -> Unit,
    val excludeSettlementSpecies: (PlanSettlementSpecies) -> Unit
) {
    fun toggleExpected(stat: PlanSpeciesStat, selected: Boolean) = toggleStat(stat, selected)
    fun toggleExpected(rare: PlanRareObservation, selected: Boolean) = toggleRare(rare, selected)

    companion object {
        fun from(vm: PlansTripsViewModel) = PlanDetailActions(vm::analyzePlan, vm::cancelAnalysis,
            vm::setSpeciesSort, vm::setAllExpected, vm::toggleExpected, vm::toggleExpected, vm::removeExpected,
            vm::showTripPicker, vm::settlePlan, vm::addManualObservation, vm::resetSettlementOverrides,
            vm::removeManualObservation, vm::excludeSettlementSpecies)
    }
}
