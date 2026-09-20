package ai.drivemuse.app

import ai.drivemuse.app.learning.LearningStore
import ai.drivemuse.app.playback.PlaybackObserver
import ai.drivemuse.app.spotify.RemotePlayerState
import ai.drivemuse.domain.EndJudgement
import ai.drivemuse.domain.EndReason
import ai.drivemuse.domain.EndTrigger
import ai.drivemuse.domain.ListeningTotals
import ai.drivemuse.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BL01 and BL12 from the behaviour-learning spec, which exist to prove the boundary holds rather
 * than to describe the engine. The engine is not built yet; what is asserted here is that a
 * forbidden source produces nothing to build it from.
 *
 * Room is an Android database, so this runs on the emulator alongside the migration test.
 */
@RunWith(AndroidJUnit4::class)
class LearningBoundaryTest {

    private lateinit var db: DriveDatabase
    private lateinit var learning: LearningStore

    @Before fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, DriveDatabase::class.java).build()
        learning = LearningStore(db)
    }

    @After fun close() = db.close()

    private fun attempt(id: String, track: String, commandId: String? = null) =
        PlaybackAttemptEntity(id, track, "s1", null, 0, commandId, 1_000, 1_100, null, "START_CONFIRMED")

    /** BL01: normal completion and an early skip from a forbidden source write no learning at all. */
    @Test fun forbiddenSourceProducesNoLearning() = runBlocking {
        val finished = learning.record(
            attempt("a1", "t1"),
            ListeningTotals(activeMs = 200_000, coveredMs = 200_000, ratio = .95, uncertain = false),
            EndJudgement(EndReason.NATURAL_END, 1.0),
            endedAt = 300_000
        )
        val skipped = learning.record(
            attempt("a2", "t2", commandId = "c1"),
            ListeningTotals(activeMs = 4_000, coveredMs = 4_000, ratio = .02, uncertain = false),
            EndJudgement(EndReason.USER_NEXT, 1.0),
            endedAt = 400_000
        )

        assertEquals(0.0, finished)
        assertEquals(0.0, skipped)
        assertTrue(db.intelligence().outcomes().none { !it.explicit }, "an implicit outcome was stored")
        assertEquals(0, db.intelligence().implicitOutcomeCount())
        assertNull(db.intelligence().state("learned_archive"), "a derived archive was written")
        assertEquals(emptyMap(), learning.scores("s1", 500_000))
        assertEquals(0, learning.summary().validTracks)
        assertEquals(0, learning.summary().observedAttempts)

        // Control state is not taste and is still recorded: the attempt must be closed.
        val closed = requireNotNull(db.intelligence().attempt("a1")) { "the attempt row was dropped" }
        assertEquals("TERMINAL", closed.state)
        assertEquals(300_000L, closed.endedAt)
    }

    /**
     * BL12: rows written before the boundary are removed, and a callback arriving after the purge
     * does not recreate them. The user's own explicit rating is not part of the derived data.
     */
    @Test fun purgeRemovesDerivedDataAndLateCallbacksDoNotRestoreIt() = runBlocking {
        val dao = db.intelligence()
        dao.putAttempt(attempt("legacy", "t1"))
        dao.acceptOutcome(OutcomeEntity("legacy", "t1", "s0", 1, -0.4, false, 10, 4_000, 4_000, .02, false, "USER_NEXT", 1.0))
        dao.acceptOutcome(OutcomeEntity("explicit:s0:t9", "t9", "s0", 1, 1.0, true, 10))
        dao.putState(IntelligenceState("learned_archive", "{\"t1\":-0.4}", 1, 10))

        val removed = learning.purgeDerivedLearning()

        assertEquals(1, removed)
        assertEquals(0, dao.implicitOutcomeCount())
        assertNull(dao.state("learned_archive"))
        // The explicit rating is the user's own statement, not a derived metric.
        assertEquals(1, dao.outcomes().count { it.explicit })

        // A late callback for an attempt that was in flight during the purge.
        learning.record(
            attempt("late", "t1"),
            ListeningTotals(activeMs = 180_000, coveredMs = 180_000, ratio = .9, uncertain = false),
            EndJudgement(EndReason.NATURAL_END, 1.0),
            endedAt = 500_000
        )
        assertEquals(0, dao.implicitOutcomeCount())
        assertNull(dao.state("learned_archive"))

        // Expiry must not rebuild the archive out of rows on their way out either.
        learning.prune(now = 10_000_000_000L)
        assertNull(dao.state("learned_archive"))
        assertEquals(0, dao.implicitOutcomeCount())
    }

    /**
     * The storage tests above prove the store refuses to write. This one drives the real observer,
     * because the derivation being skipped matters as much as the row not being stored: aggregation
     * and end-reason resolution must not run at all.
     */
    @Test fun observerPathDerivesNothing() = runBlocking {
        val states = MutableStateFlow<RemotePlayerState?>(null)
        val scope = CoroutineScope(Dispatchers.Default)
        val observer = PlaybackObserver(db, learning, { "s1" })
        observer.plan(null, listOf(Track("t1", "Title", "Artist")))
        observer.start(scope, states)

        // Playing, then a position well into the track, then paused: enough for a coverage ratio
        // and a natural-end judgement to exist if anything were computing them.
        states.value = RemotePlayerState("spotify:track:t1", "Title", "Artist", false, 1_000, 200_000, 1_000)
        val confirmed = withTimeoutOrNull(5_000) {
            var row = db.intelligence().latestAttemptFor("t1")
            while (row == null) { delay(50); row = db.intelligence().latestAttemptFor("t1") }
            row
        }
        requireNotNull(confirmed) { "the start was never confirmed" }
        states.value = RemotePlayerState("spotify:track:t1", "Title", "Artist", false, 190_000, 200_000, 190_000)
        delay(300)
        observer.release(EndTrigger.SESSION_END)
        observer.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        assertEquals(0, db.intelligence().implicitOutcomeCount(), "the observer path derived an outcome")
        assertNull(db.intelligence().state("learned_archive"))
        val closed = requireNotNull(db.intelligence().latestAttemptFor("t1"))
        assertEquals("TERMINAL", closed.state, "the attempt was not closed")
        // Transitions only: a per-tick position series is the raw material for a listening ratio.
        val events = db.intelligence().eventsFor(closed.attemptId)
        assertTrue(events.size <= 2, "raw position rows were stored: ${events.size}")
    }

    /** The retention split: expiry takes derived rows and leaves the user's own ratings. */
    @Test fun expiryKeepsExplicitRatings() = runBlocking {
        val dao = db.intelligence()
        val old = 1_000L
        dao.putAttempt(attempt("aged", "t1"))
        dao.acceptOutcome(OutcomeEntity("aged", "t1", "s0", 1, -0.4, false, old, 4_000, 4_000, .02, false, "USER_NEXT", 1.0))
        dao.acceptOutcome(OutcomeEntity("explicit:s0:t9", "t9", "s0", 1, 1.0, true, old))

        learning.prune(now = old + 2592000000L + 1)

        assertEquals(0, dao.implicitOutcomeCount(), "an expired implicit row survived")
        assertEquals(1, dao.outcomes().count { it.explicit }, "an explicit rating was swept out by expiry")
    }
}
