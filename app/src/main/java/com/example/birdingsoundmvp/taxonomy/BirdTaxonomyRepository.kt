package com.example.birdingsoundmvp.taxonomy

import com.example.birdingsoundmvp.i18n.AppText

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

class BirdTaxonomyRepository(private val context: Context) {
    private val taxonomyDbFile: File
        get() = File(context.noBackupFilesDir, TAXONOMY_DB_FILE_NAME)

    private val ibirdingDbFile: File
        get() = File(context.noBackupFilesDir, IBIRDING_DB_FILE_NAME)

    fun findSpecies(scientificName: String, commonName: String): BirdSpeciesDetail? {
        if (!databasesAvailable()) return null
        copyDatabaseIfNeeded(TAXONOMY_ASSET_PATH, taxonomyDbFile)
        copyDatabaseIfNeeded(IBIRDING_ASSET_PATH, ibirdingDbFile)

        val normalizedScientific = scientificName.trim()
        val normalizedCommon = commonName.trim()
        val taxonomy = SQLiteDatabase
            .openDatabase(taxonomyDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            .use { queryTaxonomyImageDetail(it, normalizedScientific, normalizedCommon) }
        val ibirding = SQLiteDatabase
            .openDatabase(ibirdingDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            .use { queryIbirdingAccount(it, normalizedScientific, normalizedCommon) }

        if (taxonomy == null && ibirding == null) return null

        val taxonomyImages = taxonomy?.images.orEmpty()
        val ibirdingImages = ibirding?.images.orEmpty()
        return BirdSpeciesDetail(
            birdnetId = taxonomy?.birdnetId.orEmpty(),
            scientificName = ibirding?.scientificName?.ifBlank { null }
                ?: taxonomy?.scientificName
                ?: normalizedScientific,
            commonName = ibirding?.englishName?.ifBlank { null }
                ?: taxonomy?.commonName
                ?: normalizedCommon,
            chineseName = ibirding?.chineseName.orEmpty(),
            commonNameAlt = taxonomy?.commonNameAlt.orEmpty(),
            zhCnName = taxonomy?.zhCnName.orEmpty(),
            taxonGroup = taxonomy?.taxonGroup.orEmpty(),
            observationsCount = taxonomy?.observationsCount,
            descriptionSource = "",
            images = ibirdingImages + taxonomyImages,
            ibirding = ibirding?.account
        )
    }

    fun searchSpecies(query: String, limit: Int = 8): List<BirdSpeciesDetail> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()
        if (!databasesAvailable()) return emptyList()
        findSpecies(normalized, normalized)?.let { exact -> return listOf(exact) }
        copyDatabaseIfNeeded(TAXONOMY_ASSET_PATH, taxonomyDbFile)
        copyDatabaseIfNeeded(IBIRDING_ASSET_PATH, ibirdingDbFile)
        val candidates = linkedSetOf<Pair<String, String>>()
        val like = "%${normalized.replace("%", "\\%").replace("_", "\\_")}%"
        SQLiteDatabase.openDatabase(
            ibirdingDbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            db.rawQuery(
                """
                SELECT scientific_name, english_name
                FROM species_accounts
                WHERE scientific_name LIKE ? ESCAPE '\\' COLLATE NOCASE
                   OR english_name LIKE ? ESCAPE '\\' COLLATE NOCASE
                   OR chinese_name LIKE ? ESCAPE '\\' COLLATE NOCASE
                   OR aliases LIKE ? ESCAPE '\\' COLLATE NOCASE
                LIMIT ?
                """.trimIndent(),
                arrayOf(like, like, like, like, limit.coerceIn(1, 20).toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    candidates += cursor.getStringOrBlank(0) to cursor.getStringOrBlank(1)
                }
            }
        }
        if (candidates.size < limit) {
            SQLiteDatabase.openDatabase(
                taxonomyDbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery(
                    """
                    SELECT scientific_name, common_name
                    FROM taxa
                    WHERE scientific_name LIKE ? ESCAPE '\\' COLLATE NOCASE
                       OR common_name LIKE ? ESCAPE '\\' COLLATE NOCASE
                       OR common_name_alt LIKE ? ESCAPE '\\' COLLATE NOCASE
                       OR birdnet_id IN (
                           SELECT birdnet_id FROM taxon_names
                           WHERE name LIKE ? ESCAPE '\\' COLLATE NOCASE
                       )
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(like, like, like, like, limit.coerceIn(1, 20).toString())
                ).use { cursor ->
                    while (cursor.moveToNext() && candidates.size < limit) {
                        candidates += cursor.getStringOrBlank(0) to cursor.getStringOrBlank(1)
                    }
                }
            }
        }
        return candidates
            .take(limit.coerceIn(1, 20))
            .mapNotNull { (scientific, common) -> findSpecies(scientific, common) }
            .distinctBy { it.scientificName.lowercase() }
    }

    private fun databasesAvailable(): Boolean = listOf(
        TAXONOMY_ASSET_PATH to taxonomyDbFile, IBIRDING_ASSET_PATH to ibirdingDbFile
    ).all { (asset, local) ->
        local.isFile && local.length() > 0 || runCatching { context.assets.open(asset).use { } }.isSuccess
    }

    private fun copyDatabaseIfNeeded(assetPath: String, outputFile: File) = synchronized(databaseCopyLock) {
        if (outputFile.exists() && outputFile.length() > 0L) return@synchronized
        outputFile.parentFile?.mkdirs()
        val temporary = File.createTempFile("taxonomy-", ".tmp", outputFile.parentFile)
        try {
            context.assets.open(assetPath).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.renameTo(outputFile)) { AppText.get("Could not install taxonomy database") }
        } finally { temporary.delete() }
    }

    private fun queryTaxonomyImageDetail(
        db: SQLiteDatabase,
        scientificName: String,
        commonName: String
    ): TaxonomyPartial? {
        queryTaxonByWhere(
            db,
            "scientific_name = ? COLLATE NOCASE",
            arrayOf(scientificName)
        )?.let { return it }

        val matchedBirdnetId = queryMatchedBirdnetId(db, scientificName, commonName)
        if (!matchedBirdnetId.isNullOrBlank()) {
            queryTaxonByWhere(db, "birdnet_id = ?", arrayOf(matchedBirdnetId))?.let { return it }
        }

        if (commonName.isNotBlank()) {
            return queryTaxonByWhere(
                db,
                "common_name = ? COLLATE NOCASE OR common_name_alt = ? COLLATE NOCASE",
                arrayOf(commonName, commonName)
            )
        }
        return null
    }

    private fun queryMatchedBirdnetId(
        db: SQLiteDatabase,
        scientificName: String,
        commonName: String
    ): String? {
        db.rawQuery(
            """
            SELECT birdnet_id FROM birdnet_label_matches
            WHERE scientific_name = ? COLLATE NOCASE
               OR common_name = ? COLLATE NOCASE
               OR label = ? COLLATE NOCASE
               OR label = ? COLLATE NOCASE
            LIMIT 1
            """.trimIndent(),
            arrayOf(scientificName, commonName, scientificName, commonName)
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getStringOrBlank(0)
        }
        return null
    }

    private fun queryTaxonByWhere(
        db: SQLiteDatabase,
        where: String,
        args: Array<String>
    ): TaxonomyPartial? {
        db.rawQuery(
            """
            SELECT birdnet_id, scientific_name, common_name, common_name_alt, taxon_group,
                   observations_count, image_url, image_author, image_license, image_source
            FROM taxa
            WHERE $where
            LIMIT 1
            """.trimIndent(),
            args
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val birdnetId = cursor.getStringOrBlank(0)
            val imageUrl = cursor.getStringOrBlank(6)
            val images = if (imageUrl.isBlank()) {
                emptyList()
            } else {
                listOf(
                    BirdSpeciesImage(
                        url = imageUrl,
                        author = cursor.getStringOrBlank(7),
                        license = cursor.getStringOrBlank(8),
                        source = cursor.getStringOrBlank(9)
                    )
                )
            }
            return TaxonomyPartial(
                birdnetId = birdnetId,
                scientificName = cursor.getStringOrBlank(1),
                commonName = cursor.getStringOrBlank(2),
                commonNameAlt = cursor.getStringOrBlank(3),
                zhCnName = queryTaxonomyName(db, birdnetId, "zh-CN"),
                taxonGroup = cursor.getStringOrBlank(4),
                observationsCount = if (cursor.isNull(5)) null else cursor.getLong(5),
                images = images
            )
        }
    }

    private fun queryTaxonomyName(db: SQLiteDatabase, birdnetId: String, locale: String): String {
        db.rawQuery(
            "SELECT name FROM taxon_names WHERE birdnet_id = ? AND locale = ? LIMIT 1",
            arrayOf(birdnetId, locale)
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getStringOrBlank(0)
        }
        return ""
    }

    private fun queryIbirdingAccount(
        db: SQLiteDatabase,
        scientificName: String,
        commonName: String
    ): IbirdingPartial? {
        val where = if (commonName.isBlank()) {
            "scientific_name = ? COLLATE NOCASE"
        } else {
            """
            scientific_name = ? COLLATE NOCASE
               OR english_name = ? COLLATE NOCASE
               OR chinese_name = ? COLLATE NOCASE
            """.trimIndent()
        }
        val args = if (commonName.isBlank()) {
            arrayOf(scientificName)
        } else {
            arrayOf(scientificName, commonName, commonName)
        }
        db.rawQuery(
            """
            SELECT source_species_id, chinese_name, english_name, scientific_name,
                   taxonomy_cn, taxonomy_en, description, iris, bill, feet,
                   voice, range_text, china_distribution, habits, aliases,
                   plate_caption, source_url, encyclopedia_url, sound_url
            FROM species_accounts
            WHERE $where
            LIMIT 1
            """.trimIndent(),
            args
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val sourceSpeciesId = cursor.getStringOrBlank(0)
            val account = IbirdingSpeciesAccount(
                sourceSpeciesId = sourceSpeciesId,
                taxonomyCn = cursor.getStringOrBlank(4),
                taxonomyEn = cursor.getStringOrBlank(5),
                description = cursor.getStringOrBlank(6),
                iris = cursor.getStringOrBlank(7),
                bill = cursor.getStringOrBlank(8),
                feet = cursor.getStringOrBlank(9),
                voice = cursor.getStringOrBlank(10),
                rangeText = cursor.getStringOrBlank(11),
                chinaDistribution = cursor.getStringOrBlank(12),
                habits = cursor.getStringOrBlank(13),
                aliases = cursor.getStringOrBlank(14),
                plateCaption = cursor.getStringOrBlank(15),
                sourceUrl = cursor.getStringOrBlank(16),
                encyclopediaUrl = cursor.getStringOrBlank(17),
                soundUrl = cursor.getStringOrBlank(18)
            )
            return IbirdingPartial(
                sourceSpeciesId = sourceSpeciesId,
                chineseName = cursor.getStringOrBlank(1),
                englishName = cursor.getStringOrBlank(2),
                scientificName = cursor.getStringOrBlank(3),
                account = account,
                images = queryIbirdingImages(db, sourceSpeciesId)
            )
        }
    }

    private fun queryIbirdingImages(db: SQLiteDatabase, sourceSpeciesId: String): List<BirdSpeciesImage> {
        val images = mutableListOf<BirdSpeciesImage>()
        db.rawQuery(
            """
            SELECT media_type, local_path, title, alt
            FROM media_assets
            WHERE source_species_id = ?
              AND local_path IS NOT NULL
              AND local_path != ''
              AND (error IS NULL OR error = '')
            ORDER BY CASE media_type
                WHEN 'plate' THEN 0
                WHEN 'china_range_map' THEN 1
                ELSE 2
            END
            """.trimIndent(),
            arrayOf(sourceSpeciesId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val localPath = cursor.getStringOrBlank(1)
                if (localPath.isBlank()) continue
                images += BirdSpeciesImage(
                    url = "$IBIRDING_ASSET_ROOT/$localPath",
                    author = "",
                    license = "",
                    source = cursor.getStringOrBlank(2).ifBlank { cursor.getStringOrBlank(3) },
                    isLocalAsset = true
                )
            }
        }
        return images
    }

    private fun android.database.Cursor.getStringOrBlank(index: Int): String {
        return if (isNull(index)) "" else getString(index).orEmpty()
    }

    private data class TaxonomyPartial(
        val birdnetId: String,
        val scientificName: String,
        val commonName: String,
        val commonNameAlt: String,
        val zhCnName: String,
        val taxonGroup: String,
        val observationsCount: Long?,
        val images: List<BirdSpeciesImage>
    )

    private data class IbirdingPartial(
        val sourceSpeciesId: String,
        val chineseName: String,
        val englishName: String,
        val scientificName: String,
        val account: IbirdingSpeciesAccount,
        val images: List<BirdSpeciesImage>
    )

    private companion object {
        val databaseCopyLock = Any()
        const val TAXONOMY_ASSET_PATH = "taxonomy/birdnet_taxonomy.db"
        const val TAXONOMY_DB_FILE_NAME = "birdnet_taxonomy.db"
        const val IBIRDING_ASSET_PATH = "ibirding_cn/bird_species.db"
        const val IBIRDING_DB_FILE_NAME = "ibirding_cn_bird_species.db"
        const val IBIRDING_ASSET_ROOT = "ibirding_cn"
    }
}
