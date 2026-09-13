package com.example.birdingsoundmvp.audio

data class SpectrogramColumn(
    val tripAudioTimeMs: Long,
    val values: FloatArray
)

interface SpectrogramStore {
    fun addColumn(column: SpectrogramColumn)
    fun clear()
    fun snapshot(): List<SpectrogramColumn>
    fun window(startMs: Long, endMs: Long, maxColumns: Int): List<SpectrogramColumn>
}

class InMemorySpectrogramStore(
    private val maxColumns: Int = 1_000_000
) : SpectrogramStore {
    private data class StoredColumn(
        val tripAudioTimeMs: Long,
        val values: ByteArray
    )

    private val columns = ArrayDeque<StoredColumn>()

    @Synchronized
    override fun addColumn(column: SpectrogramColumn) {
        columns.addLast(
            StoredColumn(
                tripAudioTimeMs = column.tripAudioTimeMs,
                values = ByteArray(column.values.size) { index ->
                    (column.values[index].coerceIn(0f, 1f) * 255f).toInt().toByte()
                }
            )
        )
        while (columns.size > maxColumns) {
            columns.removeFirst()
        }
    }

    @Synchronized
    override fun clear() {
        columns.clear()
    }

    @Synchronized
    override fun snapshot(): List<SpectrogramColumn> = columns.map { it.toColumn() }

    @Synchronized
    override fun window(startMs: Long, endMs: Long, maxColumns: Int): List<SpectrogramColumn> {
        val visible = columns.filter { it.tripAudioTimeMs in startMs..endMs }
        if (visible.size <= maxColumns) return visible.map { it.toColumn() }
        val step = visible.size.toDouble() / maxColumns.coerceAtLeast(1)
        return List(maxColumns) { index ->
            visible[(index * step).toInt().coerceIn(0, visible.lastIndex)].toColumn()
        }
    }

    private fun StoredColumn.toColumn(): SpectrogramColumn {
        return SpectrogramColumn(
            tripAudioTimeMs = tripAudioTimeMs,
            values = FloatArray(values.size) { index ->
                (values[index].toInt() and 0xFF) / 255f
            }
        )
    }
}
