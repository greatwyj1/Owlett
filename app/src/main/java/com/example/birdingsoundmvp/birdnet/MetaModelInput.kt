package com.example.birdingsoundmvp.birdnet

data class MetaModelInput(
    val latitude: Float,
    val longitude: Float,
    val week: Float
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        latitude.coerceIn(-90f, 90f),
        longitude.coerceIn(-180f, 180f),
        week.coerceIn(1f, 48f)
    )

    companion object {
        fun missing(week: Int): MetaModelInput = MetaModelInput(-1f, -1f, week.coerceIn(1, 48).toFloat())
    }
}
