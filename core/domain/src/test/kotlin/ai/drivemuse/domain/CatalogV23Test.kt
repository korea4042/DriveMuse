package ai.drivemuse.domain
import kotlin.test.*

class CatalogV23Test {
    private val now = 1_000_000L
    private fun ref(id: String = "v1", kind: RefKind = RefKind.OFFICIAL_AUDIO, version: VersionType = VersionType.UNKNOWN, trackId: String? = "t1", status: MatchStatus = MatchStatus.CONFIRMED, fetched: Long = 10, dur: Long? = 210_000) =
        PlayableRef("youtube", id, trackId, kind, version, status, listOf("m:$id"), dur, fetched, now + 100_000, Availability.AVAILABLE)
    private fun cand(id: String, title: String, artist: String, dur: Long? = 210_000, version: VersionType = VersionType.UNKNOWN, isrc: String? = null) = IdentityCandidate(id, "MB", title, artist, dur, versionType = version, isrc = isrc)

    // §27 identity
    @Test fun autoAcceptNeedsTwoEvidenceAndGap() {
        val r = IdentityResolver.resolve(ref(), "Blue Hour", "Northbound", listOf(cand("a", "Blue Hour", "Northbound"), cand("b", "Blue Hour (Live)", "Northbound", 250_000, VersionType.LIVE)))
        assertEquals(IdentityDecision.AUTO_ACCEPT, r.decision); assertEquals("a", r.best!!.candidate.recordingId)
    }
    @Test fun T24_sameTitleDifferentArtistOrVersionNotMerged() {
        val other = IdentityResolver.resolve(ref(), "Blue Hour", "Northbound", listOf(cand("x", "Blue Hour", "Someone Else")))
        assertNotEquals(IdentityDecision.AUTO_ACCEPT, other.decision)
        val live = IdentityResolver.resolve(ref(version = VersionType.LIVE), "Blue Hour (Live)", "Northbound", listOf(cand("o", "Blue Hour", "Northbound", version = VersionType.ORIGINAL)))
        assertEquals(IdentityDecision.AMBIGUOUS, live.decision); assertTrue("VERSION_CONFLICT_ONLY" in live.reasons)
    }
    @Test fun runnerUpTooCloseIsAmbiguous() {
        val r = IdentityResolver.resolve(ref(), "Blue Hour", "Northbound", listOf(cand("a", "Blue Hour", "Northbound"), cand("b", "Blue Hour", "Northbound", 211_000)))
        assertEquals(IdentityDecision.AMBIGUOUS, r.decision); assertTrue("RUNNER_UP_TOO_CLOSE" in r.reasons)
    }
    @Test fun isrcAloneNeverSufficient() {
        val r = IdentityResolver.resolve(ref().copy(matchEvidence = listOf("ISRC:KR123")), "", "", listOf(cand("a", "Other", "Other", null, isrc = "KR123")))
        assertNotEquals(IdentityDecision.AUTO_ACCEPT, r.decision)
    }
    @Test fun normalizationKeepsVersionMarkers() {
        assertEquals("blue hour (live)", TitleNormalizer.normalize("  Blue   HOUR (Live) "))
        assertEquals(VersionType.LIVE, TitleNormalizer.versionHint("Blue Hour - Live at Seoul")); assertEquals(VersionType.UNKNOWN, TitleNormalizer.versionHint("Blue Hour"))
    }

    // §29/§31 validation
    private val track = TrackRecord("t1", "Blue Hour", listOf(ArtistCredit("Northbound")), 210_000, metadataStatus = MetadataStatus.ENRICHED)
    private fun assertion(field: String, value: String, basis: Basis = Basis.PROVIDER_FACT, id: String = "a-$field") = MetadataAssertion(id, "t1", field, value, basis, "MB", fetchedAt = 0)
    @Test fun T26_basicOrAmbiguousNeverValidated() {
        assertNotEquals(MetadataStatus.VALIDATED, ValidationGate.validate(ValidationInput(track, IdentityDecision.AMBIGUOUS, listOf(ref()), emptyList(), Constraints(), now)).decision)
        assertEquals(MetadataStatus.BASIC, MetadataPromotion.next(MetadataStatus.BASIC, true, true, 1))
        assertEquals(MetadataStatus.ENRICHED, MetadataPromotion.next(MetadataStatus.ENRICHED, true, true, 5))
    }
    @Test fun validatedNeedsUsableRefButAllowsUnknownGenre() {
        val ok = ValidationGate.validate(ValidationInput(track, IdentityDecision.AUTO_ACCEPT, listOf(ref()), listOf(assertion("genre", "POP")), Constraints(excludedGenres = setOf("ROCK")), now))
        assertEquals(MetadataStatus.VALIDATED, ok.decision)
        val noGenre = ValidationGate.validate(ValidationInput(track, IdentityDecision.AUTO_ACCEPT, listOf(ref()), emptyList(), Constraints(excludedGenres = setOf("ROCK")), now))
        assertEquals(MetadataStatus.VALIDATED, noGenre.decision)
        val expired = ValidationGate.validate(ValidationInput(track, IdentityDecision.AUTO_ACCEPT, listOf(ref().copy(expiresAt = now - 1)), listOf(assertion("genre", "POP")), Constraints(), now))
        assertTrue("NO_USABLE_PLAYABLE_REF" in expired.reasons)
    }
    @Test fun aiInferenceDoesNotSatisfyRequiredField() {
        val r = ValidationGate.validate(ValidationInput(track, IdentityDecision.AUTO_ACCEPT, listOf(ref()), listOf(assertion("genre", "POP", Basis.AI_INFERRED)), Constraints(), now, requiredFields = setOf("genre")))
        assertTrue("MISSING_genre" in r.reasons)
    }
    @Test fun T27_conflictingAssertionsPreserved() {
        val a = listOf(assertion("genre", "POP", id = "1"), assertion("genre", "RNB", Basis.COMMUNITY_TAG, id = "2"))
        assertEquals(2, a.filter { it.live(now) }.size); assertFalse(a[1].factual); assertTrue(a[0].factual)
    }
    @Test fun refResolverPrefersOfficialAudioAndSkipsShortsOrVersionMismatch() {
        val refs = listOf(ref("mv", RefKind.OFFICIAL_MV), ref("audio", RefKind.OFFICIAL_AUDIO), ref("short", RefKind.SHORTS), ref("live", RefKind.OFFICIAL_AUDIO, VersionType.LIVE))
        assertEquals("audio", PlayableRefResolver.choose("t1", VersionType.ORIGINAL, refs, now)!!.resourceId)
        assertNull(PlayableRefResolver.choose("t1", VersionType.ORIGINAL, listOf(ref("short", RefKind.SHORTS)), now))
        assertNull(PlayableRefResolver.choose("t2", VersionType.UNKNOWN, refs, now), "another track's video is never substituted")
    }
    @Test fun T31_aliasChainResolves() {
        val aliases = listOf(IdentityAlias("old", "mid", "d1", 1), IdentityAlias("mid", "canon", "d2", 2))
        assertEquals("canon", AliasResolver.canonical("old", aliases)); assertEquals("mid", AliasResolver.canonical("old", aliases, at = 1))
    }

    // §17 novelty
    @Test fun T01_sameArtistLikedAndUnheardDiffer() {
        val liked = NoveltyResolver.resolve(TrackExperience("a", explicitPreference = 1, historyCoverageSince = 0), true, false)
        val unheard = NoveltyResolver.resolve(TrackExperience("b", historyCoverageSince = 0), true, false)
        assertEquals(NoveltyState.KNOWN_PREFERENCE, liked.state); assertEquals(NoveltyState.NO_OBSERVED_HISTORY, unheard.state); assertTrue(unheard.knownArtist)
    }
    @Test fun exposureIsNotListeningAndResetIsUnknown() {
        assertEquals(NoveltyState.EXPOSED_ONLY, NoveltyResolver.resolve(TrackExperience("a", exposureCount = 3, historyCoverageSince = 0), false, false).state)
        assertEquals(NoveltyState.UNKNOWN, NoveltyResolver.resolve(TrackExperience("a", historyCoverageSince = null), false, false).state)
        assertEquals(NoveltyState.UNKNOWN, NoveltyResolver.resolve(null, false, true).state)
        assertEquals(NoveltyState.UNKNOWN, NoveltyResolver.resolve(TrackExperience("a", confirmedListenCount = 2, matchAmbiguous = true, historyCoverageSince = 0), false, false).state)
    }
    @Test fun T02_oldReleaseCanBeNovel() { assertEquals(1.0, CandidatePriority.score(1.0, NoveltyState.NO_OBSERVED_HISTORY, 1.0), 1e-9) }

    // §16/§29 planner and budget
    @Test fun T29_collectionBudgetIsNotPlaybackMix() {
        val b = CollectionBudget.of(100); assertEquals(70, b.adjacent); assertEquals(20, b.expansion); assertEquals(10, b.experiment)
        assertEquals(MixTarget(.5, .4, .1), MixTarget.resolve(SurveyProfile(emptyList(), emptySet(), .25, listOf("Q4"))))
        val q4 = MixTarget.resolve(SurveyProfile(emptyList(), emptySet(), .55, emptyList())); assertEquals(.55, q4.exploration, 1e-9); assertEquals(.44, q4.discovery, 1e-9); assertEquals(.11, q4.experimental, 1e-9)
    }
    @Test fun plannerOrdersAdjacentBeforeRecentAndFiltersExclusions() {
        val seeds = listOf(DiscoverySeed("r", SeedKind.RECENT_RELEASE, "2026", 1), DiscoverySeed("a", SeedKind.PREFERRED_ARTIST, "Northbound", 1), DiscoverySeed("x", SeedKind.GENRE_TAG, "ROCK", 1), DiscoverySeed("old", SeedKind.PREFERRED_ARTIST, "Old", 0), DiscoverySeed("wait", SeedKind.PREFERRED_ARTIST, "Wait", 1, nextEligibleAt = now + 1))
        val plan = DiscoveryPlanner.plan(seeds, 1, now, setOf("ROCK"))
        assertEquals(listOf("a", "r"), plan.map { it.seedId }); assertEquals(BudgetBand.ADJACENT, plan[0].band); assertEquals(BudgetBand.EXPANSION, plan[1].band)
    }
    @Test fun T12_aiPlanWithUnknownSeedRejected() {
        val allowed = listOf(DiscoverySeed("a", SeedKind.PREFERRED_ARTIST, "N", 1))
        assertNull(DiscoveryPlanner.accept(listOf(CollectionPlan("ghost", Strategy.OTHER_TRACKS, BudgetBand.ADJACENT, 1, "")), allowed, Strategy.values().toSet()))
        assertNull(DiscoveryPlanner.accept(listOf(CollectionPlan("a", Strategy.ADJACENT_TRAITS, BudgetBand.ADJACENT, 1, "")), allowed, setOf(Strategy.OTHER_TRACKS)))
        assertNotNull(DiscoveryPlanner.accept(listOf(CollectionPlan("a", Strategy.OTHER_TRACKS, BudgetBand.EXPERIMENT, 2, "")), allowed, Strategy.values().toSet()))
    }
    @Test fun poolHealthCountsEligibleOnly() { val h = PoolHealth(eligible = 3); assertTrue(h.needsRefill); assertTrue(h.canStart); assertTrue(h.belowStartTarget); assertEquals(20, h.insertAllowance(30)) }
    @Test fun T05_leaseSingleHolder() {
        val l = LeasePolicy.acquire(null, "s", "w1", now, 60_000, 1)!!
        assertNull(LeasePolicy.acquire(l, "s", "w2", now + 10, 60_000, 1)); assertNotNull(LeasePolicy.acquire(l, "s", "w2", now + 60_001, 60_000, 1)); assertNotNull(LeasePolicy.acquire(l, "s", "w1", now + 10, 60_000, 1))
    }
    @Test fun diversityFlagsConcentration() {
        val tracks = (1..7).map { TrackRecord("t$it", "x", listOf(ArtistCredit("A"))) } + (1..3).map { TrackRecord("u$it", "y", listOf(ArtistCredit("B$it"))) }
        val r = DiversityAudit.report(tracks, { null }, { null }, { null }); assertTrue(r.artistConcentrated); assertEquals(10, r.buckets.getValue("decade").getValue("UNKNOWN"))
    }

    // §29 mix allocation
    @Test fun allocationCorrectsSessionDrift() {
        assertEquals(listOf(MixClass.KNOWN, MixClass.DISCOVERY, MixClass.KNOWN), ExplorationMix.allocate(MixTarget.DEFAULT, MixProgress()))
        assertEquals(MixClass.DISCOVERY, ExplorationMix.allocate(MixTarget.DEFAULT, MixProgress(known = 6, discovery = 1), 1).single())
        assertEquals(MixClass.UNCLASSIFIED, ExplorationMix.classify(NoveltyState.UNKNOWN, true, 1.0))
    }
    @Test fun adjustmentBoundedAndGated() {
        assertEquals(.35, ExplorationMix.adjust(.35, .2, 2, 50, 0.0)); assertEquals(.35, ExplorationMix.adjust(.35, .2, 5, 19, 0.0))
        assertEquals(.40, ExplorationMix.adjust(.35, .2, 3, 20, 0.0), 1e-9); assertEquals(.37, ExplorationMix.adjust(.35, .2, 3, 20, .03), 1e-9); assertEquals(.35, ExplorationMix.adjust(.35, null, 9, 99, 0.0))
    }

    // §30 slot queue
    private fun batch(vararg ids: String) = SlotBatch("b", "s", 1, 1, 1, "c", ids.mapIndexed { i, id -> Slot("i$i", i, id) })
    /** A has been played and skipped: LOCKED → START_CONFIRMED → TERMINAL. */
    private fun afterFirstSkip(vararg ids: String) = SlotQueuePolicy.terminate(SlotQueuePolicy.confirmStart(SlotQueuePolicy.lock(batch(*ids), 0, "c0")!!, ids[0], "att0")!!, "att0")
    private val pool = setOf("A", "B", "C", "D", "E")
    @Test fun scenarioReplacePlannedAfterSkip() {
        val b = afterFirstSkip("A", "B", "C")
        val r = SlotQueuePolicy.apply(b, SlotQueuePolicy.request(b).copy(evidenceVersion = 2), listOf(SlotProposal(1, "D", listOf("e1")), SlotProposal(2, "E", listOf("e1"))), pool, now) { "n$it" } as QueueResult.Applied
        assertEquals(listOf("D", "E"), r.batch.slots.filter { it.state == SlotState.PLANNED }.map { it.trackId }); assertEquals(2, r.cancelled.size); assertEquals(b.queueRevision + 1, r.batch.queueRevision)
        assertEquals(SlotState.TERMINAL, r.batch.slots[0].state, "the skipped track is never touched")
        assertIs<QueueResult.Rejected>(SlotQueuePolicy.apply(r.batch, SlotQueuePolicy.request(b), listOf(SlotProposal(2, "C", emptyList())), pool, now) { "x" }, "stale baseQueueRevision is discarded")
    }
    @Test fun T30_lockedSlotIsImmutable() {
        val b = SlotQueuePolicy.lock(afterFirstSkip("A", "B", "C"), 1, "cmd1")!!
        val rejected = SlotQueuePolicy.apply(b, SlotQueuePolicy.request(b), listOf(SlotProposal(1, "D", emptyList())), pool, now) { "x" }
        assertEquals("ORDINAL_NOT_REPLACEABLE", (rejected as QueueResult.Rejected).reason)
        val ok = SlotQueuePolicy.apply(b, SlotQueuePolicy.request(b), listOf(SlotProposal(2, "E", emptyList())), pool, now) { "x" } as QueueResult.Applied
        assertEquals("B", ok.batch.slots.single { it.state == SlotState.LOCKED }.trackId); assertEquals(listOf(2), ok.batch.replaceableOrdinals)
        assertEquals("DUPLICATES_LOCKED_OR_ACTIVE", (SlotQueuePolicy.apply(b, SlotQueuePolicy.request(b), listOf(SlotProposal(2, "B", emptyList())), pool, now) { "x" } as QueueResult.Rejected).reason)
        assertNull(SlotQueuePolicy.lock(b, 2, "cmd1"), "commandId is unique")
    }
    @Test fun startConfirmRequiresObservedMatchAndSingleActive() {
        val locked = SlotQueuePolicy.lock(batch("A", "B"), 0, "c1")!!
        assertNull(SlotQueuePolicy.confirmStart(locked, "B", "att"))
        val active = SlotQueuePolicy.confirmStart(locked, "A", "att1")!!
        assertEquals(BatchStatus.ACTIVE, active.status); assertNull(SlotQueuePolicy.confirmStart(active, "A", "att2"))
        val done = SlotQueuePolicy.terminate(SlotQueuePolicy.terminate(active, "att1"), "att1"); assertEquals(1, done.slots.count { it.state == SlotState.TERMINAL })
    }
    @Test fun manualSelectionSuspendsAndRestartDoesNotResend() {
        val b = SlotQueuePolicy.lock(batch("A", "B", "C"), 0, "c1")!!
        val s = SlotQueuePolicy.suspend(b); assertEquals(BatchStatus.SUSPENDED, s.status); assertTrue(s.slots.all { it.state == SlotState.CANCELLED })
        val r = SlotQueuePolicy.recoverAfterRestart(b); assertEquals(SlotState.PLANNED, r.slots[0].state); assertNull(r.slots[0].commandId)
    }

    // §22/§23/§28 providers and integration
    @Test fun T32_credentialContractsPerProvider() {
        assertTrue(ProviderRequirements.fields(ProviderId.MUSICBRAINZ).isEmpty()); assertEquals(listOf("apiKey"), ProviderRequirements.fields(ProviderId.LASTFM).map { it.key })
        assertTrue(ProviderRequirements.fields(ProviderId.LISTENBRAINZ).none { it.required }); assertTrue(ProviderId.values().none { p -> ProviderRequirements.fields(p).any { it.key == "clientSecret" || it.key == "sharedSecret" } })
        assertEquals(setOf(Capability.LOOKUP_IDENTITY, Capability.FETCH_METADATA, Capability.DISCOVER_RELATED), ProviderRequirements.capabilities(ProviderId.MUSICBRAINZ, emptySet()))
        assertTrue(ProviderRequirements.capabilities(ProviderId.LASTFM, emptySet()).isEmpty())
    }
    @Test fun T18_T19_formatAndEffectiveEnabled() {
        assertFalse(IntegrationPolicy.formatOk(ProviderId.LASTFM, mapOf("apiKey" to "k", "sharedSecret" to "s"))); assertTrue(IntegrationPolicy.formatOk(ProviderId.LASTFM, mapOf("apiKey" to "k")))
        assertFalse(IntegrationPolicy.effectiveEnabled(true, false)); assertTrue(IntegrationPolicy.effectiveEnabled(true, true))
    }
    @Test fun T15_T17_failedDraftKeepsActiveAndOldVersionRejected() {
        val active = IntegrationConfig(ProviderId.YOUTUBE, status = IntegrationStatus.READY, configVersion = 3, presentKeys = setOf("apiKey"))
        val draft = IntegrationPolicy.beginValidation(active, setOf("apiKey"))
        assertEquals(active, IntegrationPolicy.fail(draft, active, IntegrationError.KEY_RESTRICTED))
        val promoted = IntegrationPolicy.promote(active, draft, now); assertEquals(4, promoted.configVersion); assertTrue(promoted.ready)
        assertFalse(IntegrationPolicy.accepts(4, 3)); assertEquals(IntegrationStatus.UNCONFIGURED, IntegrationPolicy.remove(promoted).status); assertEquals(5, IntegrationPolicy.remove(promoted).configVersion)
    }
    @Test fun rateLimiterSerializesToOnePerSecond() { val l = RateLimiter(); assertEquals(0, l.reserve(0)); assertEquals(1000, l.reserve(0)); assertEquals(0, l.reserve(5000)) }
    @Test fun breakerOpensAfterThreeAndProbesOnce() {
        val b = CircuitBreaker(); repeat(3) { b.transientFailure(0) }; assertFalse(b.allow(1)); assertTrue(b.allow(15 * 60_000)); assertFalse(b.allow(15 * 60_000 + 1)); b.success(); assertTrue(b.allow(15 * 60_000 + 2))
    }
    @Test fun quotaWindowConservative() { val w = QuotaWindow("s", "d", "search", limit = 300, resetAt = 10).reserve(100)!!; assertNull(w.reserve(201)); assertEquals(100, w.settle(100).consumed) }
    @Test fun backoffHonorsRetryAfter() { assertEquals(7000, Backoff.delayMs(5, 7)); assertEquals(60_000, Backoff.delayMs(1)); assertEquals(30 * 60_000, Backoff.delayMs(10)) }
    @Test fun listenBrainzStatusContract() { assertEquals(RecommendationLookup.NONE_GENERATED, ListenBrainzContract.classify(204)); assertEquals(RecommendationLookup.NO_USER, ListenBrainzContract.classify(404)) }
}

class VideoFormTest {
    @Test fun broadcastAndStageCutsAreNotPlaybackCandidates() {
        assertTrue(VideoForm.isBroadcastOrStage("아티스트 - 곡 (교차편집/Stage Mix)"))
        assertTrue(VideoForm.isBroadcastOrStage("[뮤직뱅크] 아티스트 - 곡", "KBS Kpop"))
        assertTrue(VideoForm.isBroadcastOrStage("아티스트 곡 직캠 fancam"))
        assertTrue(VideoForm.isBroadcastOrStage("곡 커버 cover by someone"))
        assertTrue(VideoForm.isBroadcastOrStage("겨울 감성 노래 모음 1시간"))
        assertTrue(VideoForm.isBroadcastOrStage("아티스트 - 곡 M/V Teaser"))
    }
    @Test fun studioUploadsSurvive() {
        assertTrue(!VideoForm.isBroadcastOrStage("아티스트 - 곡 (Official Audio)"))
        assertTrue(!VideoForm.isBroadcastOrStage("아티스트 - 곡", "아티스트 - Topic"))
        assertTrue(!VideoForm.isBroadcastOrStage("Artist - Song"))
    }
    @Test fun musicVideosAreExcluded() {
        assertTrue(VideoForm.isMusicVideo("Artist - Song (Official Music Video)"))
        assertTrue(VideoForm.isMusicVideo("아티스트 - 곡 M/V"))
        assertTrue(VideoForm.isMusicVideo("아티스트 - 곡 뮤비"))
        assertTrue(VideoForm.isBroadcastOrStage("Artist - Song (Official Video)"))
        // An auto-generated audio channel keeps its upload even when the title mentions a video.
        assertTrue(!VideoForm.isMusicVideo("Song (From the Music Video)", "Artist - Topic"))
    }
    @Test fun topicChannelOutranksOfficialTitleAndMv() {
        val topic = VideoForm.audioPreference("곡", "아티스트 - Topic")
        val audio = VideoForm.audioPreference("곡 (Official Audio)", "아티스트")
        val mv = VideoForm.audioPreference("곡 (Official M/V)", "아티스트")
        val plain = VideoForm.audioPreference("곡", "아티스트")
        assertTrue(topic > audio && audio > mv && mv > plain)
    }
}

class RankPenaltyTest {
    @Test fun audioUploadsOutrankMusicVideos() {
        assertEquals(0.0, VideoForm.rankPenalty("곡 (Official Audio)", "아티스트"))
        assertEquals(0.0, VideoForm.rankPenalty("곡", "아티스트 - Topic"))
        assertTrue(VideoForm.rankPenalty("곡", "아티스트") > VideoForm.rankPenalty("곡 (Lyric Video)", "아티스트"))
    }
}
