package ai.drivemuse.domain

import kotlin.test.*

/** E01–E03, E08 of the enrichment design. No device, no network: the mapping and the ordering. */
class EnrichmentTest {

    private fun tags(vararg pairs: Pair<String, Int>) = pairs.map { EnrichmentPolicy.Tag(it.first, it.second) }

    private fun assertion(
        field: String, value: String, basis: Basis, source: AssertionSource = AssertionSource.LASTFM,
        confidence: Double? = null, version: Int = 1, id: String = "$basis:$field:$version"
    ) = CandidateAssertion(id, "4cOdK2wGLETKBW3PvgPWqT", field, value, basis, source, confidence, listOf("t1"), version)

    // ---- E01 tagsToHints ----

    @Test fun positiveTagsOnlyGiveHighEnergy() {
        val h = EnrichmentPolicy.tagsToHints(tags("dance" to 40, "party" to 30))
        assertEquals(EnrichmentPolicy.ENERGY_HIGH, h.energy)
        assertTrue(h.conflicts.isEmpty())
    }

    @Test fun negativeTagsOnlyGiveLowEnergy() {
        assertEquals(EnrichmentPolicy.ENERGY_LOW, EnrichmentPolicy.tagsToHints(tags("chill" to 50, "acoustic" to 20)).energy)
    }

    @Test fun bothSidesOfTheAxisGiveNoHintAndAConflict() {
        val h = EnrichmentPolicy.tagsToHints(tags("dance" to 40, "ambient" to 35))
        assertNull(h.energy)
        assertTrue("energy" in h.conflicts)
    }

    @Test fun contestedMoodTagsGiveNoMood() {
        val h = EnrichmentPolicy.tagsToHints(tags("happy" to 40, "sad" to 30))
        assertNull(h.mood)
        assertTrue("mood" in h.conflicts)
    }

    @Test fun agreeingMoodTagsGiveOneMood() {
        assertEquals(Mood.DARK, EnrichmentPolicy.tagsToHints(tags("melancholy" to 30, "moody" to 25)).mood)
    }

    @Test fun thinTagEvidenceIsMarkedWeak() {
        val thin = EnrichmentPolicy.tagsToHints(tags("chill" to 12, "mellow" to 9))
        assertEquals(EnrichmentPolicy.WEAK, thin.confidence)
        // Weak is below the adoption floor, so the hint exists but never reaches TrackFeatures.
        assertTrue(thin.confidence < TrackFeatureResolver.TAG_FLOOR)
        assertEquals(EnrichmentPolicy.CONFIDENT, EnrichmentPolicy.tagsToHints(tags("chill" to 40)).confidence)
    }

    @Test fun languageIsAWeakHypothesisFromOneMarketTag() {
        assertEquals("ko", EnrichmentPolicy.tagsToHints(tags("k-pop" to 80)).language)
        val both = EnrichmentPolicy.tagsToHints(tags("k-pop" to 40, "j-pop" to 40))
        assertNull(both.language)
        assertTrue("language" in both.conflicts)
    }

    // ---- E02 resolver priority ----

    @Test fun providerFactBeatsTagBeatsInference() {
        val resolved = TrackFeatureResolver.resolve(listOf(
            assertion("energy", "0.80", Basis.AI_INFERRED, AssertionSource.GEMINI, .7),
            assertion("energy", "0.25", Basis.COMMUNITY_TAG, confidence = .65),
            assertion("energy", "0.50", Basis.USER_OBSERVED, AssertionSource.USER, 1.0)
        ))
        assertEquals(.50, resolved.energy?.value)
        assertEquals(Basis.USER_OBSERVED, resolved.energy?.basis)
    }

    @Test fun confidenceUnderTheFloorIsNoAnswerRatherThanAWeakOne() {
        val tagTooWeak = TrackFeatureResolver.resolve(listOf(assertion("energy", "0.25", Basis.COMMUNITY_TAG, confidence = .35)))
        assertNull(tagTooWeak.energy)
        val aiTooWeak = TrackFeatureResolver.resolve(listOf(assertion("mood", "CALM", Basis.AI_INFERRED, AssertionSource.GEMINI, .3)))
        assertNull(aiTooWeak.mood)
        // The same claim one notch above its floor is adopted.
        assertEquals(Mood.CALM, TrackFeatureResolver.resolve(listOf(assertion("mood", "CALM", Basis.AI_INFERRED, AssertionSource.GEMINI, .45))).mood?.value)
    }

    @Test fun aTagOrInferenceWithNoStatedConfidenceDoesNotPassTheFloor() {
        // It used to read as 1.0, so an inference that said nothing about its own strength cleared
        // the floor and then showed on screen as certain.
        assertNull(TrackFeatureResolver.resolve(listOf(assertion("mood", "CALM", Basis.AI_INFERRED, AssertionSource.GEMINI))).mood)
        assertNull(TrackFeatureResolver.resolve(listOf(assertion("energy", "0.25", Basis.COMMUNITY_TAG))).energy)
        // A fact has no floor to clear, so a missing confidence still reads as full.
        val fact = TrackFeatureResolver.resolve(listOf(assertion("energy", "0.25", Basis.PROVIDER_FACT, AssertionSource.MUSICBRAINZ)))
        assertEquals(.25, fact.energy?.value)
        assertEquals(1.0, fact.energy?.confidence)
    }

    @Test fun anExpiredAssertionIsNotRead() {
        val expired = assertion("energy", "0.25", Basis.COMMUNITY_TAG, confidence = .65).copy(expiresAt = 100)
        assertNull(TrackFeatureResolver.resolve(listOf(expired), now = 200).energy)
        assertEquals(.25, TrackFeatureResolver.resolve(listOf(expired), now = 50).energy?.value)
    }

    @Test fun featuresCarryTheirEvidence() {
        val resolved = TrackFeatureResolver.resolve(listOf(assertion("mood", "BRIGHT", Basis.COMMUNITY_TAG, confidence = .65)))
        assertEquals(listOf("t1"), resolved.mood?.evidenceIds)
        assertEquals(Basis.COMMUNITY_TAG, resolved.mood?.basis)
    }

    @Test fun unparsableValuesStayUnknown() {
        val junk = TrackFeatureResolver.resolve(listOf(
            assertion("energy", "very high", Basis.PROVIDER_FACT, AssertionSource.MUSICBRAINZ, 1.0),
            assertion("mood", "SPARKLY", Basis.PROVIDER_FACT, AssertionSource.MUSICBRAINZ, 1.0),
            assertion("language", "korean", Basis.PROVIDER_FACT, AssertionSource.MUSICBRAINZ, 1.0)
        ))
        assertNull(junk.energy); assertNull(junk.mood); assertNull(junk.language)
    }

    @Test fun releaseYearComesFromTheProviderDate() {
        assertEquals(2019, TrackFeatureResolver.resolve(listOf(
            assertion("releaseDate", "2019-03", Basis.PROVIDER_FACT, AssertionSource.MUSICBRAINZ, 1.0)
        )).releaseYear)
    }

    // ---- E03 metadataVersion ----

    @Test fun theHigherMetadataVersionWinsWithinABasis() {
        val resolved = TrackFeatureResolver.resolve(listOf(
            assertion("mood", "DARK", Basis.AI_INFERRED, AssertionSource.GEMINI, .6, version = 2, id = "old"),
            assertion("mood", "CALM", Basis.AI_INFERRED, AssertionSource.GEMINI, .6, version = 5, id = "new")
        ))
        assertEquals(Mood.CALM, resolved.mood?.value)
    }

    // ---- E08 enrichment order ----

    @Test fun queuedTracksAreEnrichedFirstThenTheHeadOfThePool() {
        val pool = (1..60).map { "track$it" }
        val order = EnrichmentPolicy.pending(pool, queued = listOf("track59"), states = emptyMap(), now = 0, limit = 3, head = 40)
        assertEquals("track59", order.first())
        assertEquals(listOf("track1", "track2"), order.drop(1))
    }

    @Test fun aTrackWaitingOutItsBackoffIsSkipped() {
        val states = mapOf("track1" to EnrichmentState("track1", nextEligibleAt = 5_000))
        assertFalse("track1" in EnrichmentPolicy.pending(listOf("track1", "track2"), emptyList(), states, now = 1_000))
        assertTrue("track1" in EnrichmentPolicy.pending(listOf("track1", "track2"), emptyList(), states, now = 9_000))
    }

    @Test fun aFullyEnrichedTrackIsNotAskedAgain() {
        val done = mapOf("track1" to EnrichmentState("track1", EnrichStatus.DONE, EnrichStatus.DONE, EnrichStatus.PENDING))
        assertEquals(listOf("track2"), EnrichmentPolicy.pending(listOf("track1", "track2"), emptyList(), done, now = 1_000))
    }

    @Test fun theBatchCeilingHolds() {
        val pool = (1..100).map { "track$it" }
        assertEquals(Policy.ENRICH_BATCH, EnrichmentPolicy.pending(pool, emptyList(), emptyMap(), now = 0).size)
    }

    // ---- the policy boundary, as a type ----

    @Test fun assertionSourceHasNoSpotifyMember() {
        // §2: a function taking List<CandidateAssertion> cannot be handed a Spotify-derived value,
        // because there is no source to label one with. This is the compile-time half of E07.
        assertEquals(setOf("MUSICBRAINZ", "LASTFM", "GEMINI", "USER"), AssertionSource.entries.map { it.name }.toSet())
    }
}
