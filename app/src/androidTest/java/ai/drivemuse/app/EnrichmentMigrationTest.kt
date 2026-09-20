package ai.drivemuse.app

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 8 to 9 upgrade adds the independent metadata layer. The SQL script in scripts/ already checks
 * the cascade against plain SQLite; what only Room can say is whether the migrated database matches
 * the exported schema 9, and only a real engine enforces the foreign keys Room turns on at open.
 *
 * Scope is the two new tables and the rows that were already there. It says nothing about the
 * providers or the model.
 */
@RunWith(AndroidJUnit4::class)
class EnrichmentMigrationTest {

    private val databaseName = "migration-8-9.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DriveDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun enrichmentTablesArriveAndCascadeFromCandidates() {
        helper.createDatabase(databaseName, 8).use { old ->
            old.execSQL(
                "INSERT INTO candidates (videoId,title,artist,durationSec,topics,familiar,affinity," +
                    "freshness,energy,source,fetchedAt) VALUES " +
                    "('4cOdK2wGLETKBW3PvgPWqT','Blue Hour','Northbound',210,'POP',0,.5,.5,NULL,'saved',1000)"
            )
            old.execSQL("INSERT INTO batches VALUES ('b1','s1',3,1,1,1,'cand','READY','[]',1000,0)")
        }

        // The assertion is the validate call: it fails if the upgraded database does not match the
        // exported schema 9, which is the failure a user would otherwise hit when the app opens.
        val db = helper.runMigrationsAndValidate(databaseName, 9, true, DriveDatabase.MIGRATION_8_9)

        db.query("SELECT count(*) FROM candidates").use { row ->
            row.moveToFirst(); assertEquals(1, row.getInt(0), "the existing candidate was lost")
        }
        db.query("SELECT count(*) FROM batches").use { row ->
            row.moveToFirst(); assertEquals(1, row.getInt(0), "an unrelated table was rebuilt")
        }

        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL(
            "INSERT INTO candidate_assertion VALUES ('a1','4cOdK2wGLETKBW3PvgPWqT','tag','chill'," +
                "'COMMUNITY_TAG','LASTFM',.65,'[]',1,1000,NULL)"
        )
        db.execSQL(
            "INSERT INTO enrichment_state VALUES ('4cOdK2wGLETKBW3PvgPWqT','DONE','DONE','PENDING',1,1000,0,1)"
        )

        // S7: the assertion belongs to the candidate and does not outlive it.
        db.execSQL("DELETE FROM candidates WHERE videoId='4cOdK2wGLETKBW3PvgPWqT'")
        db.query("SELECT count(*) FROM candidate_assertion").use { row ->
            row.moveToFirst(); assertEquals(0, row.getInt(0), "an assertion outlived its candidate")
        }
        db.query("SELECT count(*) FROM enrichment_state").use { row ->
            row.moveToFirst(); assertEquals(0, row.getInt(0), "an enrichment state outlived its candidate")
        }

        // The §27 UUID layer keeps its own namespace: nothing was written into it.
        db.query("SELECT count(*) FROM metadata_assertion").use { row ->
            row.moveToFirst(); assertTrue(row.getInt(0) == 0)
        }
    }
}
