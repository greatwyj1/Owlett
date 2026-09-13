package com.example.birdingsoundmvp.planning

object PlanSpeciesBatchSelection {
    fun apply(existing: List<PlanExpectedSpecies>, candidates: List<PlanExpectedSpecies>, selected: Boolean): List<PlanExpectedSpecies> {
        fun PlanExpectedSpecies.aliases() = speciesIdentityAliases(speciesKey, speciesCode, scientificName, commonName)
        val candidateAliases = candidates.flatMap { it.aliases() }.toSet()
        if (!selected) return existing.filterNot { it.aliases().any(candidateAliases::contains) }
        val result = existing.toMutableList()
        val known = existing.flatMap { it.aliases() }.toMutableSet()
        candidates.forEach { candidate ->
            val aliases = candidate.aliases()
            if (aliases.none(known::contains)) result += candidate
            known += aliases
        }
        return result
    }
}
