package ai.drivemuse.domain
import kotlin.test.*

/**
 * Phase 1 §11, T14–T18: the whole path from App Remote callbacks to one score, with no Android and
 * no database. Every number here is the §8 table, so a change to the policy fails here first.
 */
class PlaybackObservationTest {
    private var seq = 0
    private fun tick(timeMs: Long, positionMs: Long, paused: Boolean = false) =
        Observation("e${seq++}", "attempt", timeMs, positionMs, if (paused) MediaState.PAUSED else MediaState.PLAYING)

    private fun score(events: List<Observation>, durationMs: Long?, trigger: EndTrigger, commandAt: Long? = null): Pair<EndJudgement, Double> {
        val totals = ListeningAggregator.aggregate(events, durationMs)
        val last = events.maxByOrNull { it.monotonicMs }
        val judgement = EndReasonResolver.resolve(AttemptClose(last?.monotonicMs ?: 0, last?.positionMs ?: 0, durationMs, trigger, commandAt))
        return judgement to PreferenceLearner.score(Outcome("attempt", "t", "s", 1, totals, judgement.reason, judgement.confidence, null, last?.monotonicMs ?: 0))
    }

    /** T14: position advancing in step with the clock is the only thing that counts as listening. */
    @Test fun matchingAdvanceCountsAsListening() {
        val totals = ListeningAggregator.aggregate(listOf(tick(0, 0), tick(3000, 3000), tick(6000, 6000)), 200_000)
        assertEquals(6000L, totals.activeMs)
        assertEquals(6000L, totals.coveredMs)
        assertFalse(totals.uncertain)
    }

    /** T15: a gap longer than five seconds is recorded as unobserved, never filled in. */
    @Test fun longGapIsNotListening() {
        val totals = ListeningAggregator.aggregate(listOf(tick(0, 0), tick(3000, 3000), tick(12000, 12000)), 200_000)
        assertEquals(3000L, totals.activeMs)
        assertTrue(totals.uncertain)
    }

    /** T16: carried past 97% and then changed — a completion, worth +0.25 once coverage is there. */
    @Test fun completionIsNaturalEnd() {
        val events = (0..19).map { tick(it * 3000L, it * 3000L) }
        val (judgement, value) = score(events, 58_000, EndTrigger.TRACK_CHANGED)
        assertEquals(EndReason.NATURAL_END, judgement.reason)
        assertEquals(1.0, judgement.confidence)
        assertEquals(.25, value)
    }

    /** T17: the app's own skip is the one skip whose cause is known, so it scores. */
    @Test fun appSkipIsNegative() {
        val events = listOf(tick(0, 0), tick(3000, 3000), tick(6000, 6000), tick(9000, 9000), tick(12000, 12000))
        val (judgement, value) = score(events, 200_000, EndTrigger.TRACK_CHANGED, commandAt = 11_500)
        assertEquals(EndReason.USER_NEXT, judgement.reason)
        assertEquals(1.0, judgement.confidence)
        assertEquals(-.4, value)
    }

    /** T18: the same skip without a command behind it is a guess, and a guess must not teach. */
    @Test fun unexplainedSkipScoresZero() {
        val events = listOf(tick(0, 0), tick(3000, 3000), tick(6000, 6000), tick(9000, 9000), tick(12000, 12000))
        val (judgement, value) = score(events, 200_000, EndTrigger.TRACK_CHANGED)
        assertEquals(EndReason.USER_NEXT, judgement.reason)
        assertEquals(.6, judgement.confidence)
        assertEquals(0.0, value)
    }

    /** A command that landed long before the change did not cause it. */
    @Test fun staleCommandIsNotAttributed() {
        val close = AttemptClose(60_000, 20_000, 200_000, EndTrigger.TRACK_CHANGED, appCommandAt = 1_000)
        assertEquals(.6, EndReasonResolver.resolve(close).confidence)
    }

    /** A dropped connection is an interruption, never a rejection of the track. */
    @Test fun disconnectIsInterrupted() {
        val (judgement, value) = score(listOf(tick(0, 0), tick(3000, 3000)), 200_000, EndTrigger.DISCONNECTED)
        assertEquals(EndReason.INTERRUPTED, judgement.reason)
        assertEquals(0.0, value)
    }

    /** No duration means no completion claim, whatever the last position was. */
    @Test fun missingDurationCannotComplete() {
        val close = AttemptClose(200_000, 199_000, null, EndTrigger.TRACK_CHANGED)
        assertEquals(EndReason.USER_NEXT, EndReasonResolver.resolve(close).reason)
        assertEquals(.6, EndReasonResolver.resolve(close).confidence)
    }
}

/** Phase 1 §5, T19–T20: artist genres become axes the survey can act on, or nothing at all. */
class GenreMapTest {
    @Test fun spotifyGenresBecomeAxes() {
        assertEquals(setOf("POP"), GenreMap.axes(listOf("k-pop", "dance pop")))
        assertEquals(.7, GenreMap.energy(listOf("k-pop", "dance pop")))
        assertEquals(setOf("HIP_HOP"), GenreMap.axes(listOf("korean hip hop")))
        assertEquals(setOf("CLASSICAL"), GenreMap.axes(listOf("classical piano")))
    }

    /** A track can sit on two axes; the map does not pick a winner it has no basis to pick. */
    @Test fun severalAxesAreKept() {
        assertEquals(setOf("ROCK", "POP"), GenreMap.axes(listOf("indie rock", "art pop")))
    }

    /** Unrecognised strings produce no axis and no energy rather than a nearest guess. */
    @Test fun unknownGenreProducesNothing() {
        assertTrue(GenreMap.axes(listOf("gqom", "")).isEmpty())
        assertNull(GenreMap.energy(listOf("gqom")))
    }

    /** T20: a candidate with no confirmed genre survives an exclusion (SEL01). */
    @Test fun emptyGenreSurvivesExclusion() {
        val unknown = Track("abcdefghijklmnopqrstuv", "A", "B")
        val classical = Track("abcdefghijklmnopqrstuw", "A", "B", features = listOf(VerifiedFeature("genre", "CLASSICAL", "SPOTIFY_ARTIST_GENRE", .8)))
        val constraints = Constraints(excludedGenres = setOf("CLASSICAL"))
        assertTrue(constraints.allows(unknown))
        assertFalse(constraints.allows(classical))
    }
}

/** Phase 1 §7, T21: diversity is a preference in the ranking, never a reason to return fewer. */
class SameArtistFillTest {
    private fun track(id: String, artist: String, affinity: Double = .8) =
        Track(id.padEnd(22, 'x'), "T$id", artist, familiar = true, affinity = affinity)

    @Test fun oneArtistStillFillsTheBatch() {
        val pool = listOf(track("a", "Solo"), track("b", "Solo"), track("c", "Solo"))
        val chosen = SessionRanker.select(pool, EffectiveRules(.35, 1.0), DiscoveryProgress())
        assertEquals(3, chosen.size)
        assertEquals(3, chosen.map { it.id }.distinct().size)
    }

    /** With a real alternative available, the other artist is preferred over a repeat. */
    @Test fun anotherArtistWinsWhenCloseEnough() {
        val pool = listOf(track("a", "Solo", .9), track("b", "Solo", .85), track("c", "Other", .8))
        val chosen = SessionRanker.select(pool, EffectiveRules(.35, 1.0), DiscoveryProgress(), count = 2)
        assertEquals(listOf("Solo", "Other"), chosen.map { it.artist })
    }

    /** A clearly better track by the same artist still wins: the penalty is .10, not a veto. */
    @Test fun penaltyDoesNotOverrideALargeGap() {
        val pool = listOf(track("a", "Solo", .9), track("b", "Solo", .85), track("c", "Other", .2))
        assertEquals(listOf("Solo", "Solo"), SessionRanker.select(pool, EffectiveRules(.35, 1.0), DiscoveryProgress(), count = 2).map { it.artist })
    }
}

/** Diversity and recognisability: a batch must not be one artist, and new must not mean obscure. */
class BatchDiversityTest {
    private fun track(id: String, artist: String, affinity: Double = .8, popularity: Int? = null, familiar: Boolean = true) =
        Track(id.padEnd(22, 'x'), "T$id", artist, familiar = familiar, affinity = affinity, popularity = popularity)

    /** A pool dominated by one artist still yields a varied batch. */
    @Test fun oneArtistCannotOwnTheBatch() {
        val pool = (1..12).map { track("a$it", "Dominant", .9) } + (1..6).map { track("b$it", "Other$it", .5) }
        val chosen = SessionRanker.select(pool, EffectiveRules(.35, 1.0), DiscoveryProgress())
        assertEquals(Policy.BATCH_SIZE, chosen.size)
        assertEquals(Policy.MAX_PER_ARTIST, chosen.count { it.artist == "Dominant" })
    }

    /** The cap yields rather than return a short batch, per §30. */
    @Test fun capNeverShrinksTheBatch() {
        val pool = (1..10).map { track("a$it", "Solo") }
        assertEquals(Policy.BATCH_SIZE, SessionRanker.select(pool, EffectiveRules(.35, 1.0), DiscoveryProgress()).size)
    }

    /** Between two equally good unheard tracks, the one people actually play wins. */
    @Test fun recognisabilityBreaksTies() {
        val known = track("k", "A", .6, popularity = 80, familiar = false)
        val obscure = track("o", "B", .6, popularity = 5, familiar = false)
        assertEquals("A", SessionRanker.select(listOf(obscure, known), EffectiveRules(.35, 1.0), DiscoveryProgress(), count = 1).single().artist)
    }

    /** Missing popularity is neutral, so a track without the field is not pushed to the bottom. */
    @Test fun unknownPopularityIsNeutral() {
        assertEquals(.5, track("u", "A").recognisability)
        assertEquals(.8, track("v", "A", popularity = 80).recognisability)
    }

    /** Recognisability is a nudge: a much better match still beats a more famous track. */
    @Test fun recognisabilityDoesNotOverridePreference() {
        val liked = track("l", "A", .95, popularity = 10)
        val famous = track("f", "B", .30, popularity = 100)
        assertEquals("A", SessionRanker.select(listOf(famous, liked), EffectiveRules(.35, 1.0), DiscoveryProgress(), count = 1).single().artist)
    }
}
