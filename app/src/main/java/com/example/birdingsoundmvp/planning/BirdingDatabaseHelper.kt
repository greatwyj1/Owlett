package com.example.birdingsoundmvp.planning

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class BirdingDatabaseHelper(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME
) : SQLiteOpenHelper(
    context,
    databaseName,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE birding_places (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                latitude REAL,
                longitude REAL,
                notes TEXT NOT NULL DEFAULT '',
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE linked_locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                place_id INTEGER NOT NULL,
                provider TEXT NOT NULL,
                external_id TEXT NOT NULL DEFAULT '',
                name TEXT NOT NULL DEFAULT '',
                latitude REAL,
                longitude REAL,
                raw_json TEXT,
                FOREIGN KEY(place_id) REFERENCES birding_places(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE planned_trips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                place_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                planned_date TEXT NOT NULL,
                planned_start_time TEXT,
                planned_end_time TEXT,
                notes TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'planned',
                target_list_generated_at_ms INTEGER,
                FOREIGN KEY(place_id) REFERENCES birding_places(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE target_species (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                planned_trip_id INTEGER NOT NULL,
                scientific_name TEXT NOT NULL DEFAULT '',
                common_name TEXT NOT NULL DEFAULT '',
                display_name_zh TEXT,
                category TEXT NOT NULL DEFAULT 'user_added',
                source TEXT NOT NULL DEFAULT 'manual',
                confidence REAL,
                last_seen_date TEXT,
                frequency REAL,
                FOREIGN KEY(planned_trip_id) REFERENCES planned_trips(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE trip_session_links (
                trip_id TEXT PRIMARY KEY,
                place_id INTEGER,
                planned_trip_id INTEGER,
                is_quick_trip INTEGER NOT NULL DEFAULT 1,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                FOREIGN KEY(place_id) REFERENCES birding_places(id) ON DELETE SET NULL,
                FOREIGN KEY(planned_trip_id) REFERENCES planned_trips(id) ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE trip_review_species (
                trip_id TEXT NOT NULL,
                scientific_name TEXT NOT NULL,
                status TEXT NOT NULL,
                max_confidence REAL NOT NULL DEFAULT 0,
                first_detected_ms INTEGER,
                last_detected_ms INTEGER,
                source TEXT NOT NULL DEFAULT 'manual',
                PRIMARY KEY(trip_id, scientific_name)
            )
            """.trimIndent()
        )
        createPlansTripsV2Tables(db)
        createPlansTripsV3Tables(db)
        createPlansTripsV4Tables(db)
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) {
            onCreate(db)
            return
        }
        if (oldVersion < 2) {
            createPlansTripsV2Tables(db)
            migrateLegacyPlanningData(db)
        }
        if (oldVersion < 3) {
            createPlansTripsV3Tables(db)
        }
        if (oldVersion < 4) createPlansTripsV4Tables(db)
        createIndexes(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    private fun createPlansTripsV4Tables(db: SQLiteDatabase) {
        val columns = db.rawQuery("PRAGMA table_info(plans)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        mapOf(
            "source_id" to "TEXT NOT NULL DEFAULT 'ebird'",
            "source_location_json" to "TEXT NOT NULL DEFAULT '{}'",
            "statistic_kind" to "TEXT NOT NULL DEFAULT 'report_day_frequency'",
            "deleted_at_ms" to "INTEGER"
        ).forEach { (name, definition) ->
            if (name !in columns) db.execSQL("ALTER TABLE plans ADD COLUMN $name $definition")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plans_deleted ON plans(deleted_at_ms)")
        val rareColumns = db.rawQuery("PRAGMA table_info(plan_rare_observations)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        if ("source_url" !in rareColumns) db.execSQL("ALTER TABLE plan_rare_observations ADD COLUMN source_url TEXT")
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_linked_locations_place ON linked_locations(place_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_planned_trips_place ON planned_trips(place_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_target_species_trip ON target_species(planned_trip_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trip_links_place ON trip_session_links(place_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trip_links_planned ON trip_session_links(planned_trip_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plans_date ON plans(planned_date DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plan_stats_plan ON plan_species_stats(plan_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plan_expected_plan ON plan_expected_species(plan_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plan_rare_plan ON plan_rare_observations(plan_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plan_trip_links_trip ON plan_trip_links(trip_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plan_settlement_species_plan ON plan_settlement_species(plan_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_agent_action_receipts_plan ON agent_action_receipts(plan_id)")
    }

    private fun createPlansTripsV2Tables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                planned_date TEXT NOT NULL,
                region_code TEXT NOT NULL DEFAULT 'CN',
                hotspot_id TEXT NOT NULL DEFAULT '',
                hotspot_name TEXT NOT NULL DEFAULT '',
                hotspot_latitude REAL,
                hotspot_longitude REAL,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                analysis_generated_at_ms INTEGER,
                analysis_target_year INTEGER,
                analysis_target_month INTEGER,
                historical_requested_days INTEGER NOT NULL DEFAULT 0,
                historical_successful_days INTEGER NOT NULL DEFAULT 0,
                historical_active_days INTEGER NOT NULL DEFAULT 0,
                current_requested_days INTEGER NOT NULL DEFAULT 0,
                current_successful_days INTEGER NOT NULL DEFAULT 0,
                current_active_days INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plan_species_stats (
                plan_id INTEGER NOT NULL,
                species_key TEXT NOT NULL,
                species_code TEXT NOT NULL DEFAULT '',
                scientific_name TEXT NOT NULL DEFAULT '',
                common_name TEXT NOT NULL DEFAULT '',
                display_name_zh TEXT,
                historical_frequency REAL,
                current_frequency REAL,
                combined_frequency REAL NOT NULL DEFAULT 0,
                historical_observed_days INTEGER NOT NULL DEFAULT 0,
                current_observed_days INTEGER NOT NULL DEFAULT 0,
                last_seen_date TEXT,
                PRIMARY KEY(plan_id, species_key),
                FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plan_expected_species (
                plan_id INTEGER NOT NULL,
                species_key TEXT NOT NULL,
                species_code TEXT NOT NULL DEFAULT '',
                scientific_name TEXT NOT NULL DEFAULT '',
                common_name TEXT NOT NULL DEFAULT '',
                display_name_zh TEXT,
                source TEXT NOT NULL DEFAULT 'analysis',
                selected_at_ms INTEGER NOT NULL,
                PRIMARY KEY(plan_id, species_key),
                FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plan_rare_observations (
                plan_id INTEGER NOT NULL,
                species_key TEXT NOT NULL,
                species_code TEXT NOT NULL DEFAULT '',
                scientific_name TEXT NOT NULL DEFAULT '',
                common_name TEXT NOT NULL DEFAULT '',
                display_name_zh TEXT,
                observed_at TEXT NOT NULL DEFAULT '',
                location_id TEXT NOT NULL DEFAULT '',
                location_name TEXT NOT NULL DEFAULT '',
                latitude REAL,
                longitude REAL,
                count INTEGER,
                report_count INTEGER NOT NULL DEFAULT 1,
                provisional INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(plan_id, species_key),
                FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plan_trip_links (
                plan_id INTEGER NOT NULL,
                trip_id TEXT NOT NULL,
                linked_at_ms INTEGER NOT NULL,
                PRIMARY KEY(plan_id, trip_id),
                FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plan_settlement (
                plan_id INTEGER PRIMARY KEY,
                generated_at_ms INTEGER NOT NULL,
                is_stale INTEGER NOT NULL DEFAULT 0,
                analysis_generated_at_ms INTEGER,
                linked_trip_signature TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plan_settlement_species (
                plan_id INTEGER NOT NULL,
                species_key TEXT NOT NULL,
                species_code TEXT NOT NULL DEFAULT '',
                scientific_name TEXT NOT NULL DEFAULT '',
                common_name TEXT NOT NULL DEFAULT '',
                display_name_zh TEXT,
                category TEXT NOT NULL,
                origin TEXT NOT NULL,
                max_confidence REAL NOT NULL DEFAULT 0,
                trip_count INTEGER NOT NULL DEFAULT 0,
                detection_sources TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(plan_id, species_key),
                FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plan_settlement_overrides (
                plan_id INTEGER NOT NULL,
                species_key TEXT NOT NULL,
                species_code TEXT NOT NULL DEFAULT '',
                scientific_name TEXT NOT NULL DEFAULT '',
                common_name TEXT NOT NULL DEFAULT '',
                display_name_zh TEXT,
                action TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY(plan_id, species_key),
                FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ebird_daily_cache (
                hotspot_id TEXT NOT NULL,
                observation_date TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                fetched_at_ms INTEGER NOT NULL,
                PRIMARY KEY(hotspot_id, observation_date)
            )
            """.trimIndent()
        )
    }

    private fun createPlansTripsV3Tables(db: SQLiteDatabase) {
        if (!db.hasColumn("plans", "region_name")) {
            db.execSQL("ALTER TABLE plans ADD COLUMN region_name TEXT NOT NULL DEFAULT ''")
        }
        db.execSQL(
            "UPDATE plans SET region_name = CASE WHEN region_code = 'CN' THEN 'China' ELSE region_code END WHERE region_name = ''"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_action_receipts (
                operation_id TEXT PRIMARY KEY,
                skill_id TEXT NOT NULL,
                plan_id INTEGER,
                result_json TEXT NOT NULL DEFAULT '',
                created_at_ms INTEGER NOT NULL,
                FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE SET NULL
            )
            """.trimIndent()
        )
    }

    private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        rawQuery("PRAGMA table_info($table)", emptyArray()).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }

    private fun migrateLegacyPlanningData(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.execSQL(
            """
            INSERT OR IGNORE INTO plans (
                id, name, planned_date, region_code, hotspot_id, hotspot_name,
                hotspot_latitude, hotspot_longitude, created_at_ms, updated_at_ms
            )
            SELECT
                pt.id,
                pt.title,
                pt.planned_date,
                'CN',
                COALESCE((
                    SELECT ll.external_id FROM linked_locations ll
                    WHERE ll.place_id = pt.place_id
                      AND lower(ll.provider) = 'ebird'
                      AND ll.external_id != ''
                    ORDER BY ll.id ASC LIMIT 1
                ), ''),
                COALESCE((
                    SELECT ll.name FROM linked_locations ll
                    WHERE ll.place_id = pt.place_id
                      AND lower(ll.provider) = 'ebird'
                      AND ll.external_id != ''
                    ORDER BY ll.id ASC LIMIT 1
                ), ''),
                (SELECT ll.latitude FROM linked_locations ll
                    WHERE ll.place_id = pt.place_id
                      AND lower(ll.provider) = 'ebird'
                      AND ll.external_id != ''
                    ORDER BY ll.id ASC LIMIT 1),
                (SELECT ll.longitude FROM linked_locations ll
                    WHERE ll.place_id = pt.place_id
                      AND lower(ll.provider) = 'ebird'
                      AND ll.external_id != ''
                    ORDER BY ll.id ASC LIMIT 1),
                ?, ?
            FROM planned_trips pt
            """.trimIndent(),
            arrayOf(now, now)
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO plan_expected_species (
                plan_id, species_key, scientific_name, common_name,
                display_name_zh, source, selected_at_ms
            )
            SELECT
                planned_trip_id,
                lower(trim(CASE WHEN scientific_name != '' THEN scientific_name ELSE common_name END)),
                scientific_name,
                common_name,
                display_name_zh,
                'legacy_manual',
                ?
            FROM target_species
            WHERE lower(source) != 'ebird'
              AND trim(CASE WHEN scientific_name != '' THEN scientific_name ELSE common_name END) != ''
            """.trimIndent(),
            arrayOf(now)
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO plan_trip_links (plan_id, trip_id, linked_at_ms)
            SELECT planned_trip_id, trip_id, updated_at_ms
            FROM trip_session_links
            WHERE planned_trip_id IS NOT NULL
            """.trimIndent()
        )
    }

    companion object {
        const val DEFAULT_DATABASE_NAME = "birding_trips.db"
        private const val DATABASE_VERSION = 4
    }
}
