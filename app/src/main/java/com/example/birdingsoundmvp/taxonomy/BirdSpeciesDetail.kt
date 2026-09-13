package com.example.birdingsoundmvp.taxonomy

data class BirdSpeciesDetail(
    val birdnetId: String,
    val scientificName: String,
    val commonName: String,
    val chineseName: String = "",
    val commonNameAlt: String,
    val zhCnName: String,
    val taxonGroup: String,
    val observationsCount: Long?,
    val descriptionSource: String,
    val images: List<BirdSpeciesImage>,
    val ibirding: IbirdingSpeciesAccount? = null
)

data class BirdSpeciesImage(
    val url: String,
    val author: String,
    val license: String,
    val source: String,
    val isLocalAsset: Boolean = false
)

data class IbirdingSpeciesAccount(
    val sourceSpeciesId: String,
    val taxonomyCn: String,
    val taxonomyEn: String,
    val description: String,
    val iris: String,
    val bill: String,
    val feet: String,
    val voice: String,
    val rangeText: String,
    val chinaDistribution: String,
    val habits: String,
    val aliases: String,
    val plateCaption: String,
    val sourceUrl: String,
    val encyclopediaUrl: String,
    val soundUrl: String
)
