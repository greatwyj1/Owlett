package com.example.birdingsoundmvp.planning

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BirdingDatabaseMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun v1PlansTargetsHotspotsAndTripLinksMigrateInPlaceAndIdempotently() {
        LegacyV1Helper(context).use { it.writableDatabase }

        BirdingDatabaseHelper(context, TEST_DATABASE).use { helper ->
            val db = helper.writableDatabase
            assertEquals(4, db.version)
            assertEquals("ebird", stringValue(db, "SELECT source_id FROM plans WHERE id = 100"))
            assertEquals("Weekend plan", stringValue(db, "SELECT name FROM plans WHERE id = 100"))
            assertEquals("China", stringValue(db, "SELECT region_name FROM plans WHERE id = 100"))
            assertEquals("L-FIRST", stringValue(db, "SELECT hotspot_id FROM plans WHERE id = 100"))
            assertEquals("First eBird hotspot", stringValue(db, "SELECT hotspot_name FROM plans WHERE id = 100"))
            assertEquals("", stringValue(db, "SELECT hotspot_id FROM plans WHERE id = 101"))
            assertEquals(1, intValue(db, "SELECT COUNT(*) FROM plan_expected_species WHERE plan_id = 100"))
            assertEquals(
                "Corvus brachyrhynchos",
                stringValue(db, "SELECT scientific_name FROM plan_expected_species WHERE plan_id = 100")
            )
            assertEquals(1, intValue(db, "SELECT COUNT(*) FROM plan_trip_links WHERE plan_id = 100 AND trip_id = 'trip-a'"))
            assertTrue(tableExists(db, "birding_places"))
            assertTrue(tableExists(db, "planned_trips"))
            assertTrue(tableExists(db, "agent_action_receipts"))
        }

        BirdingDatabaseHelper(context, TEST_DATABASE).use { helper ->
            val db = helper.writableDatabase
            assertEquals(2, intValue(db, "SELECT COUNT(*) FROM plans"))
            assertEquals(1, intValue(db, "SELECT COUNT(*) FROM plan_expected_species"))
            assertEquals(1, intValue(db, "SELECT COUNT(*) FROM plan_trip_links"))
        }
    }

    @Test
    fun planLinksAreManyToManyAndPlanDeletionDoesNotDeleteOtherLinks() {
        BirdingDatabaseHelper(context, TEST_DATABASE).use { helper ->
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO plans (id,name,planned_date,region_code,created_at_ms,updated_at_ms) VALUES (1,'A','2026-09-01','CN',0,0)"
            )
            db.execSQL(
                "INSERT INTO plans (id,name,planned_date,region_code,created_at_ms,updated_at_ms) VALUES (2,'B','2026-09-02','CN',0,0)"
            )
            db.execSQL("INSERT INTO plan_trip_links (plan_id,trip_id,linked_at_ms) VALUES (1,'trip-a',0)")
            db.execSQL("INSERT INTO plan_trip_links (plan_id,trip_id,linked_at_ms) VALUES (2,'trip-a',0)")
            assertEquals(2, intValue(db, "SELECT COUNT(*) FROM plan_trip_links WHERE trip_id = 'trip-a'"))

            db.delete("plans", "id = ?", arrayOf("1"))

            assertEquals(1, intValue(db, "SELECT COUNT(*) FROM plan_trip_links WHERE trip_id = 'trip-a'"))
            assertEquals(2, intValue(db, "SELECT plan_id FROM plan_trip_links WHERE trip_id = 'trip-a'"))
        }
    }

    @Test fun trashRetainsLinksCanRestoreAndExpiresAfterThirtyDays() {
        val repository = PlansTripsRepository(context, TEST_DATABASE)
        try {
            val id = repository.createPlan("Trash", "2026-09-08", "CN", EbirdHotspotMatch("L1", "Place", null, null, "CN", "CN", null))
            repository.setPlanTrips(id, setOf("trip-retained"))
            repository.deletePlan(id)
            assertTrue(repository.listPlans().isEmpty())
            assertEquals(1, repository.listPlanTripLinks(id).size)
            val deletedAt = repository.getPlan(id, true)!!.deletedAtMs!!
            repository.restorePlan(id, deletedAt + 1)
            assertEquals(1, repository.listPlans().size)
            repository.deletePlan(id)
            val again = repository.getPlan(id, true)!!.deletedAtMs!!
            assertEquals(1, repository.purgeExpiredPlans(again + PlansTripsRepository.TRASH_RETENTION_MS))
            assertTrue(repository.listPlans(true).isEmpty())
            assertTrue(repository.listPlanTripLinks(id).isEmpty())
        } finally { repository.close() }
    }

    @Test fun genericAgentWriteRollsBackBatchAndRejectsStalePreview() {
        val repository = PlansTripsRepository(context, TEST_DATABASE)
        try {
            val location = EbirdHotspotMatch("L1", "Place", null, null, "CN", "CN", null)
            val id = repository.createPlan("Original", "2026-09-08", "CN", location)
            val signature = repository.planFingerprint(id)
            assertTrue(runCatching { repository.agentWrite("fail", "plans_update", listOf(id), mapOf(id to signature)) {
                repository.updatePlan(id, "Changed", "2026-09-09", "CN", location)
                error("synthetic failure")
            } }.isFailure)
            assertEquals("Original", repository.getPlan(id)!!.name)
            assertTrue(repository.findAgentActionReceipt("fail") == null)
            repository.updatePlan(id, "Manual", "2026-09-08", "CN", location)
            assertTrue(runCatching { repository.agentWrite("stale", "plans_update", listOf(id), mapOf(id to signature)) { "{}" } }.isFailure)
        } finally { repository.close() }
    }

    @Test
    fun removingTripReferencesUnlinksEveryPlanAndMarksSettlementsStale() {
        BirdingDatabaseHelper(context, TEST_DATABASE).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO plans (id,name,planned_date,region_code,created_at_ms,updated_at_ms) VALUES (1,'A','2026-09-01','CN',0,0)")
            db.execSQL("INSERT INTO plans (id,name,planned_date,region_code,created_at_ms,updated_at_ms) VALUES (2,'B','2026-09-02','CN',0,0)")
            db.execSQL("INSERT INTO plan_trip_links (plan_id,trip_id,linked_at_ms) VALUES (1,'trip-a',0)")
            db.execSQL("INSERT INTO plan_trip_links (plan_id,trip_id,linked_at_ms) VALUES (2,'trip-a',0)")
            db.execSQL("INSERT INTO plan_settlement (plan_id,generated_at_ms,is_stale) VALUES (1,0,0)")
            db.execSQL("INSERT INTO plan_settlement (plan_id,generated_at_ms,is_stale) VALUES (2,0,0)")
        }

        val repository = PlansTripsRepository(context, TEST_DATABASE)
        val affected = try {
            repository.removeTripReferences("trip-a")
        } finally {
            repository.close()
        }

        BirdingDatabaseHelper(context, TEST_DATABASE).use { helper ->
            val db = helper.writableDatabase
            assertEquals(setOf(1L, 2L), affected)
            assertEquals(0, intValue(db, "SELECT COUNT(*) FROM plan_trip_links WHERE trip_id = 'trip-a'"))
            assertEquals(2, intValue(db, "SELECT COUNT(*) FROM plan_settlement WHERE is_stale = 1"))
        }
    }

    @Test
    fun agentReceiptsMakePlanAndTargetWritesIdempotent() {
        val repository = PlansTripsRepository(context, TEST_DATABASE)
        try {
            val hotspot = EbirdHotspotMatch(
                locationId = "L1",
                name = "Test hotspot",
                latitude = 31.2,
                longitude = 121.5,
                countryCode = "CN",
                subnational1Code = "CN-31",
                latestObservationDate = null
            )
            val firstId = repository.createPlanIdempotent(
                "operation-plan",
                "Agent plan",
                "2026-09-12",
                "CN-31",
                "Shanghai",
                hotspot
            )
            val repeatedId = repository.createPlanIdempotent(
                "operation-plan",
                "Different retry title",
                "2026-10-01",
                "CN",
                "China",
                hotspot
            )
            assertEquals(firstId, repeatedId)
            assertEquals(1, repository.listPlans().size)

            val expected = listOf(
                PlanExpectedSpecies(
                    planId = firstId,
                    speciesKey = "turdus merula",
                    speciesCode = "eurbla",
                    scientificName = "Turdus merula",
                    commonName = "Common Blackbird",
                    displayNameZh = "乌鸫",
                    source = "manual_agent",
                    selectedAtMs = 1
                )
            )
            assertTrue(repository.replaceExpectedSpeciesIdempotent("operation-targets", firstId, expected))
            assertTrue(!repository.replaceExpectedSpeciesIdempotent("operation-targets", firstId, emptyList()))
            assertEquals(listOf("turdus merula"), repository.listExpectedSpecies(firstId).map { it.speciesKey })
        } finally {
            repository.close()
        }
    }

    private fun stringValue(db: SQLiteDatabase, sql: String): String =
        db.rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0).orEmpty()
        }

    @Test fun bulkTargetsPreserveOutsideSpeciesInvalidateOnceAndRollbackOnFailure() {
        val repository = PlansTripsRepository(context, TEST_DATABASE)
        try {
            val planId = repository.createPlanIdempotent("batch", "Batch", "2026-09-06", "CN", "中国",
                EbirdHotspotMatch("L1", "Bird site", null, null, "CN", "", null))
            fun species(key: String) = PlanExpectedSpecies(planId, key, key, key, key, null, "manual", 1)
            repository.setExpectedSpeciesBatch(planId, listOf(species("outside")), true)
            BirdingDatabaseHelper(context, TEST_DATABASE).use { helper ->
                helper.writableDatabase.apply {
                    execSQL("INSERT INTO plan_settlement (plan_id,generated_at_ms,is_stale) VALUES ($planId,0,0)")
                    execSQL("CREATE TABLE stale_count (n INTEGER)")
                    execSQL("INSERT INTO stale_count VALUES (0)")
                    execSQL("CREATE TRIGGER count_stale AFTER UPDATE OF is_stale ON plan_settlement BEGIN UPDATE stale_count SET n = n + 1; END")
                }
            }
            repository.setExpectedSpeciesBatch(planId, listOf(species("a"), species("b"), species("a")), true)
            assertEquals(setOf("a", "b", "outside"), repository.listExpectedSpecies(planId).map { it.speciesKey }.toSet())
            BirdingDatabaseHelper(context, TEST_DATABASE).use { helper ->
                assertEquals(1, intValue(helper.readableDatabase, "SELECT n FROM stale_count"))
                helper.writableDatabase.execSQL("CREATE TRIGGER reject_bad BEFORE INSERT ON plan_expected_species WHEN NEW.species_key = 'bad' BEGIN SELECT RAISE(ABORT,'test rollback'); END")
            }
            val failed = runCatching { repository.setExpectedSpeciesBatch(planId, listOf(species("new"), species("bad")), true) }
            assertTrue(failed.isFailure)
            assertEquals(setOf("a", "b", "outside"), repository.listExpectedSpecies(planId).map { it.speciesKey }.toSet())
            repository.setExpectedSpeciesBatch(planId, listOf(species("a"), species("b")), false)
            assertEquals(listOf("outside"), repository.listExpectedSpecies(planId).map { it.speciesKey })
        } finally { repository.close() }
    }

    private fun intValue(db: SQLiteDatabase, sql: String): Int =
        db.rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { it.moveToFirst() }

    private class LegacyV1Helper(context: Context) : SQLiteOpenHelper(
        context,
        TEST_DATABASE,
        null,
        1
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE birding_places (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,latitude REAL,longitude REAL,notes TEXT NOT NULL DEFAULT '',created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE linked_locations (id INTEGER PRIMARY KEY AUTOINCREMENT,place_id INTEGER NOT NULL,provider TEXT NOT NULL,external_id TEXT NOT NULL DEFAULT '',name TEXT NOT NULL DEFAULT '',latitude REAL,longitude REAL,raw_json TEXT,FOREIGN KEY(place_id) REFERENCES birding_places(id) ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE TABLE planned_trips (id INTEGER PRIMARY KEY AUTOINCREMENT,place_id INTEGER NOT NULL,title TEXT NOT NULL,planned_date TEXT NOT NULL,planned_start_time TEXT,planned_end_time TEXT,notes TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT 'planned',target_list_generated_at_ms INTEGER,FOREIGN KEY(place_id) REFERENCES birding_places(id) ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE TABLE target_species (id INTEGER PRIMARY KEY AUTOINCREMENT,planned_trip_id INTEGER NOT NULL,scientific_name TEXT NOT NULL DEFAULT '',common_name TEXT NOT NULL DEFAULT '',display_name_zh TEXT,category TEXT NOT NULL DEFAULT 'user_added',source TEXT NOT NULL DEFAULT 'manual',confidence REAL,last_seen_date TEXT,frequency REAL,FOREIGN KEY(planned_trip_id) REFERENCES planned_trips(id) ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE TABLE trip_session_links (trip_id TEXT PRIMARY KEY,place_id INTEGER,planned_trip_id INTEGER,is_quick_trip INTEGER NOT NULL DEFAULT 1,created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL,FOREIGN KEY(place_id) REFERENCES birding_places(id) ON DELETE SET NULL,FOREIGN KEY(planned_trip_id) REFERENCES planned_trips(id) ON DELETE SET NULL)"
            )
            db.execSQL(
                "CREATE TABLE trip_review_species (trip_id TEXT NOT NULL,scientific_name TEXT NOT NULL,status TEXT NOT NULL,max_confidence REAL NOT NULL DEFAULT 0,first_detected_ms INTEGER,last_detected_ms INTEGER,source TEXT NOT NULL DEFAULT 'manual',PRIMARY KEY(trip_id,scientific_name))"
            )
            db.execSQL("INSERT INTO birding_places VALUES (10,'Park',NULL,NULL,'',0,0)")
            db.execSQL("INSERT INTO birding_places VALUES (20,'No hotspot',NULL,NULL,'',0,0)")
            db.execSQL("INSERT INTO linked_locations VALUES (1,10,'custom','X','Other',NULL,NULL,NULL)")
            db.execSQL("INSERT INTO linked_locations VALUES (3,10,'ebird','L-SECOND','Second eBird hotspot',31.2,121.5,NULL)")
            db.execSQL("INSERT INTO linked_locations VALUES (2,10,'eBird','L-FIRST','First eBird hotspot',31.1,121.4,NULL)")
            db.execSQL("INSERT INTO planned_trips VALUES (100,10,'Weekend plan','2026-09-12',NULL,NULL,'','completed',NULL)")
            db.execSQL("INSERT INTO planned_trips VALUES (101,20,'Legacy no hotspot','2026-10-01',NULL,NULL,'','planned',NULL)")
            db.execSQL("INSERT INTO target_species VALUES (1,100,'Corvus brachyrhynchos','American Crow','美洲鸦','user_added','manual',NULL,NULL,NULL)")
            db.execSQL("INSERT INTO target_species VALUES (2,100,'Parus major','Great Tit','大山雀','recent','ebird',NULL,NULL,NULL)")
            db.execSQL("INSERT INTO trip_session_links VALUES ('trip-a',10,100,0,0,50)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val TEST_DATABASE = "birding_trips_migration_test.db"
    }
}
