package ai.drivemuse.app

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The 7 to 8 upgrade rewrites a table that every existing install already has rows in, so the SQL
 * check in scripts/ is not enough on its own: only Room decides whether the migrated database
 * matches what the entities expect, and only a real SQLite engine shows what happens to the rows.
 *
 * Scope is deliberately narrow — the batches key change. It says nothing about playback on a
 * device.
 */
@RunWith(AndroidJUnit4::class)
class BatchMigrationTest {

    private val databaseName = "migration-7-8.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DriveDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun batchesGainSequenceAndKeepTheirRows() {
        helper.createDatabase(databaseName, 7).use { old ->
            old.execSQL(
                "INSERT INTO batches VALUES ('b1','s1',3,1,1,1,'cand','INVALIDATED','[]',1000)"
            )
            old.execSQL(
                "INSERT INTO outcomes (attemptId,trackId,sessionId,version,score,explicit,createdAt) " +
                    "VALUES ('a1','t1','s1',1,1.0,1,1000)"
            )
        }

        // runMigrationsAndValidate is the assertion: it fails if the migrated database does not
        // match the exported schema 8, which is the failure a user would otherwise hit on open.
        val db = helper.runMigrationsAndValidate(
            databaseName, 8, true, DriveDatabase.MIGRATION_7_8
        )

        db.query("SELECT id, status, batchSeq FROM batches").use { row ->
            assertTrue(row.moveToFirst(), "the existing batch was lost in the migration")
            assertEquals("b1", row.getString(0))
            assertEquals("INVALIDATED", row.getString(1))
            // Under the old key at most one row per (sessionId, generation) existed, so 0 is right.
            assertEquals(0L, row.getLong(2))
            assertEquals(1, row.count)
        }
        db.query("SELECT count(*) FROM outcomes").use { row ->
            row.moveToFirst(); assertEquals(1, row.getInt(0))
        }

        // The point of the change: a second batch in the same generation.
        db.execSQL("INSERT INTO batches VALUES ('b2','s1',3,1,1,1,'cand','READY','[]',2000,1)")
        db.query("SELECT count(*) FROM batches WHERE sessionId='s1' AND generation=3").use { row ->
            row.moveToFirst(); assertEquals(2, row.getInt(0))
        }

        // And the key still means something: the same triple is refused.
        assertFailsWith<android.database.sqlite.SQLiteConstraintException> {
            db.execSQL("INSERT INTO batches VALUES ('b3','s1',3,1,1,1,'cand','READY','[]',3000,1)")
        }
    }
}
