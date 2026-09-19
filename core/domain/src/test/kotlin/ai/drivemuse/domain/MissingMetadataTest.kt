package ai.drivemuse.domain

import kotlin.test.*

class MissingMetadataTest {
    private val unknown = Track("0123456789ABCDEFGHIJKL0", "Song", "Artist")
    private val constraints = Constraints(excludedGenres = setOf("CLASSICAL"))

    @Test fun unknownSpotifyMetadataSurvivesSelectionCommitAndPlaybackGate() {
        val profile = SurveyProfile(emptyList(), constraints.excludedGenres, .35, emptyList())
        val prepared = TasteRanker.prepare(listOf(unknown), profile, constraints)
        val selected = SessionRanker.select(prepared, EffectiveRules(.35, .55), DiscoveryProgress())
        assertEquals(listOf(unknown.id), selected.map { it.id })
        val version = QueueVersion("session", 1, 1, 1, 1, "pool")
        assertTrue(ProposalGate.valid(version, version, selected.map { it.id }, prepared, constraints))
        assertTrue(constraints.allows(selected.single()))
        assertNull(selected.single().energy)
        assertTrue(selected.single().features.isEmpty())
    }

    @Test fun verifiedConflictsStillBlock() {
        val excluded = unknown.copy(features = listOf(VerifiedFeature("genre", "CLASSICAL", "provider", .9)))
        assertFalse(constraints.allows(excluded))
        assertFalse(Constraints(excludedIds = setOf(unknown.id)).allows(unknown))
        assertFalse(Constraints(excludedArtists = setOf(unknown.artist)).allows(unknown))
        assertFalse(constraints.allows(unknown.copy(skipped = true)))
        val energetic = unknown.copy(energy = .9)
        assertTrue(SessionRanker.select(listOf(energetic), EffectiveRules(.35, .55), DiscoveryProgress()).isEmpty())
    }

    @Test fun requiredAttributeAllowsMissingButRejectsVerifiedMismatch() {
        val required = Constraints(required = mapOf("language" to setOf("ko")))
        assertTrue(required.allows(unknown))
        assertFalse(required.allows(unknown.copy(features = listOf(VerifiedFeature("language", "en", "provider", 1.0)))))
    }

    @Test fun unknownNoveltyFillsSlotsWithoutInventingDiscoveryEvidence() {
        val pool = listOf(unknown to MixClass.UNCLASSIFIED)
        assertEquals(listOf(unknown), ExplorationMix.fill(listOf(MixClass.DISCOVERY), pool))
        assertEquals(MixClass.UNCLASSIFIED, ExplorationMix.classify(NoveltyState.UNKNOWN, false, null))
    }
}
