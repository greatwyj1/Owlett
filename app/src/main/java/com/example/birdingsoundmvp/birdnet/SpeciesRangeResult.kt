package com.example.birdingsoundmvp.birdnet

data class SpeciesRangeResult(
    val labelIndex: Int,
    val scientificName: String,
    val commonName: String,
    val occurrenceProbability: Float
)
