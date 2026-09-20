package ai.drivemuse.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * §9's reducer cases. The clock is a plain counter, which is the whole point: every one of these
 * is a timing boundary or a lost event, and none of them is reachable by pressing a button on a
 * desk.
 */
class SteeringGestureReducerTest {
    private val reducer = SteeringGestureReducer()
    private val t = SteeringThresholds()
    private val all = Shortcut.entries.toSet()

    private var seq = 0
    private fun input(
        key: SteeringKey,
        action: KeyAction,
        downAt: Long,
        at: Long = downAt,
        owner: BaseHandlingOwner = BaseHandlingOwner.EXTERNAL_PLAYER,
        epoch: Long = 1,
        repeat: Int = 0,
        canceled: Boolean = false
    ) = SteeringInput("e${seq++}", "wheel", Transport.BLUETOOTH, "trip", epoch, 7, key, action, downAt, at, repeat, canceled, owner)

    private fun track(id: String = "t1", at: Long = 0, supported: Boolean = true, session: Long = 7) =
        TrackSnapshot("spotify", id, session, at, 1, supported)

    private fun run(vararg events: SteeringEvent, enabled: Set<Shortcut> = all): Pair<SteeringState, List<SteeringEffect>> {
        var state = SteeringState.initial(enabled)
        val effects = mutableListOf<SteeringEffect>()
        events.forEach { val r = reducer.reduce(state, it); state = r.state; effects += r.effects }
        return state to effects
    }

    private fun key(i: SteeringInput, snap: TrackSnapshot? = track(at = i.eventTimeElapsed)) = SteeringEvent.Key(i, snap)
    private fun List<SteeringEffect>.commands() = filterIsInstance<SteeringEffect.Emit>().map { it.command }
    private fun List<SteeringEffect>.bases() = filterIsInstance<SteeringEffect.DispatchBase>()
    private fun List<SteeringEffect>.discards() = filterIsInstance<SteeringEffect.Discard>().map { it.reason }

    // --- BASE01 / BASE02 / BASE03: the ordinary action is never delayed, doubled or stolen ---

    @Test fun aShortPressIsJustTheOrdinaryAction() {
        val (_, fx) = run(
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0, owner = BaseHandlingOwner.DRIVEMUSE)),
            key(input(SteeringKey.NEXT, KeyAction.UP, 0, at = 120, owner = BaseHandlingOwner.DRIVEMUSE)))
        assertEquals(1, fx.bases().size)
        assertTrue(fx.commands().isEmpty())
        // The base dispatch is the first thing out, before any gesture timer is even set.
        assertTrue(fx.first() is SteeringEffect.DispatchBase)
    }

    @Test fun repeatedDownsDoNotRepeatTheBaseAction() {
        val (_, fx) = run(
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0, owner = BaseHandlingOwner.DRIVEMUSE)),
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0, at = 200, owner = BaseHandlingOwner.DRIVEMUSE, repeat = 1)),
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0, at = 400, owner = BaseHandlingOwner.DRIVEMUSE, repeat = 2)),
            key(input(SteeringKey.NEXT, KeyAction.UP, 0, at = 900, owner = BaseHandlingOwner.DRIVEMUSE)))
        assertEquals(1, fx.bases().size)
        assertEquals(listOf(ShortcutAction.RESET_SELECTION), fx.commands().map { it.action })
    }

    @Test fun whenThePlayerAlreadyHandledItNothingIsSent() {
        val (_, fx) = run(
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0, owner = BaseHandlingOwner.EXTERNAL_PLAYER)),
            key(input(SteeringKey.NEXT, KeyAction.UP, 0, at = 900, owner = BaseHandlingOwner.EXTERNAL_PLAYER)))
        assertEquals(0, fx.bases().size)
        assertEquals(1, fx.commands().size)
    }

    @Test fun unknownOwnershipSendsNoBaseCommandEither() {
        val (_, fx) = run(
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0, owner = BaseHandlingOwner.UNKNOWN)),
            key(input(SteeringKey.NEXT, KeyAction.UP, 0, at = 900, owner = BaseHandlingOwner.UNKNOWN)))
        assertEquals(0, fx.bases().size)
    }

    // --- LONG01 / LONG02 ---

    @Test fun theLongPressBoundariesAreExact() {
        fun held(ms: Long) = run(
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0)),
            key(input(SteeringKey.NEXT, KeyAction.UP, 0, at = ms))).second

        assertTrue(held(t.longPressMs - 1).commands().isEmpty())
        assertEquals(1, held(t.longPressMs).commands().size)
        assertEquals(1, held(t.stuckPressMs - 1).commands().size)
        assertTrue(held(t.stuckPressMs).commands().isEmpty())
        assertEquals(listOf(DiscardReason.STUCK_PRESS), held(t.stuckPressMs).discards())
    }

    @Test fun aTimerAloneNeverFiresTheCommand() {
        // The threshold passes while the button is still down. Nothing may happen until release.
        val (state, fx) = run(
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0)),
            SteeringEvent.Timeout("tick", t.longPressMs + 1))
        assertTrue(fx.commands().isEmpty())
        assertFalse(state.idle)
    }

    @Test fun aStuckPressIsAbandonedAndBlocksTheKeyUntilACleanPress() {
        var state = SteeringState.initial(all)
        val down = input(SteeringKey.NEXT, KeyAction.DOWN, 0)
        state = reducer.reduce(state, key(down)).state
        val stuck = reducer.reduce(state, SteeringEvent.Timeout("stuck:${down.pressId}", t.stuckPressMs))
        state = stuck.state
        assertEquals(listOf(DiscardReason.STUCK_PRESS), stuck.effects.discards())

        // The UP finally arrives, far too late: it must not resurrect the command.
        state = reducer.reduce(state, key(input(SteeringKey.NEXT, KeyAction.UP, 0, at = 9_000))).state

        // The next clean long press only re-establishes sync; it issues nothing.
        val resyncDown = reducer.reduce(state, key(input(SteeringKey.NEXT, KeyAction.DOWN, 10_000)))
        val resyncUp = reducer.reduce(resyncDown.state, key(input(SteeringKey.NEXT, KeyAction.UP, 10_000, at = 11_000)))
        assertTrue(resyncUp.effects.commands().isEmpty())
        assertEquals(listOf(DiscardReason.RESYNC), resyncUp.effects.discards())

        // And the one after that works normally again.
        val backDown = reducer.reduce(resyncUp.state, key(input(SteeringKey.NEXT, KeyAction.DOWN, 12_000)))
        val backUp = reducer.reduce(backDown.state, key(input(SteeringKey.NEXT, KeyAction.UP, 12_000, at = 13_000)))
        assertEquals(listOf(ShortcutAction.RESET_SELECTION), backUp.effects.commands().map { it.action })
    }

    @Test fun aCanceledEventDropsTheGesture() {
        val (state, fx) = run(
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0)),
            key(input(SteeringKey.NEXT, KeyAction.UP, 0, at = 1_000, canceled = true)))
        assertTrue(fx.commands().isEmpty())
        assertEquals(listOf(DiscardReason.CANCELED_EVENT), fx.discards())
        assertTrue(state.idle)
    }

    // --- DBL01 / DBL02 / DBL03 ---

    private fun tap(downAt: Long, held: Long = 100, trackId: String = "t1") = listOf(
        key(input(SteeringKey.PLAY_PAUSE, KeyAction.DOWN, downAt), track(trackId, downAt)),
        key(input(SteeringKey.PLAY_PAUSE, KeyAction.UP, downAt, at = downAt + held), track(trackId, downAt + held)))

    @Test fun theDoubleTapGapIsExact() {
        val inGap = run(*(tap(0) + tap(100 + t.doubleTapGapMs)).toTypedArray()).second
        assertEquals(listOf(ShortcutAction.RATE_UP), inGap.commands().map { it.action })

        val past = run(*(tap(0) + tap(100 + t.doubleTapGapMs + 1)).toTypedArray()).second
        assertTrue(past.commands().isEmpty())
    }

    @Test fun aLongSecondPressIsADislikeAndNotALike() {
        val (_, fx) = run(
            *tap(0).toTypedArray(),
            key(input(SteeringKey.PLAY_PAUSE, KeyAction.DOWN, 200), track("t1", 200)),
            key(input(SteeringKey.PLAY_PAUSE, KeyAction.UP, 200, at = 1_200), track("t1", 1_200)))
        val actions = fx.commands().map { it.action }
        assertEquals(listOf(ShortcutAction.RATE_DOWN), actions)
        assertTrue(DiscardReason.LONG_SECOND_PRESS in fx.discards())
    }

    @Test fun threeShortPressesMakeOneLikeAndNoSecondPair() {
        val (_, fx) = run(*(tap(0) + tap(200) + tap(400)).toTypedArray())
        assertEquals(listOf(ShortcutAction.RATE_UP), fx.commands().map { it.action })
    }

    @Test fun fourShortPressesMakeTwoLikes() {
        val (_, fx) = run(*(tap(0) + tap(200) + tap(400) + tap(600)).toTypedArray())
        assertEquals(2, fx.commands().size)
        // Distinct groups, so distinct ids: the executor must not treat them as a repeat.
        assertEquals(2, fx.commands().map { it.commandId }.distinct().size)
    }

    @Test fun aPendingTapExpiresWithoutSayingAnything() {
        var state = SteeringState.initial(all)
        val effects = mutableListOf<SteeringEffect>()
        tap(0).forEach { val r = reducer.reduce(state, it); state = r.state; effects += r.effects }
        val token = effects.filterIsInstance<SteeringEffect.ScheduleTimeout>().last().token
        val expired = reducer.reduce(state, SteeringEvent.Timeout(token, 100 + t.doubleTapGapMs))
        assertTrue(expired.effects.isEmpty())
        assertTrue(expired.state.idle)
    }

    // --- RATE01 / RATE04 / RATE05 ---

    @Test fun theLongPressRatesTheTrackSeenAtTheFirstPress() {
        val (_, fx) = run(
            key(input(SteeringKey.PLAY_PAUSE, KeyAction.DOWN, 0), track("first", 0)),
            key(input(SteeringKey.PLAY_PAUSE, KeyAction.UP, 0, at = 1_000), track("second", 1_000)))
        assertEquals("first", fx.commands().single().target?.trackId)
    }

    @Test fun aTrackChangeBetweenTapsDropsTheLike() {
        val (_, fx) = run(*(tap(0, trackId = "one") + tap(200, trackId = "two")).toTypedArray())
        assertTrue(fx.commands().isEmpty())
        assertEquals(listOf(DiscardReason.TRACK_CHANGED), fx.discards())
    }

    @Test fun theSameGestureAlwaysProducesTheSameCommandId() {
        fun once() = run(
            key(input(SteeringKey.PLAY_PAUSE, KeyAction.DOWN, 500), track("t1", 500)),
            key(input(SteeringKey.PLAY_PAUSE, KeyAction.UP, 500, at = 1_500), track("t1", 1_500))
        ).second.commands().single().commandId
        // Redelivery of the same physical press must be poolable to one stored rating.
        assertEquals(once(), once())
    }

    @Test fun aRatingNeedsATrackItCanActuallyName() {
        fun rate(snapshot: TrackSnapshot?) = run(
            SteeringEvent.Key(input(SteeringKey.PLAY_PAUSE, KeyAction.DOWN, 0), snapshot),
            SteeringEvent.Key(input(SteeringKey.PLAY_PAUSE, KeyAction.UP, 0, at = 1_000), snapshot)).second

        assertEquals(listOf(DiscardReason.NO_TARGET_TRACK), rate(null).discards())
        assertEquals(listOf(DiscardReason.UNSUPPORTED_CONTENT), rate(track(supported = false)).discards())
        assertEquals(listOf(DiscardReason.STALE_SNAPSHOT), rate(track(at = -t.trackSnapshotMaxAgeMs - 1)).discards())
        assertEquals(listOf(DiscardReason.SESSION_CHANGED), rate(track(session = 99)).discards())
        assertTrue(rate(track(at = 1_000 - t.trackSnapshotMaxAgeMs)).commands().isNotEmpty())
    }

    @Test fun aResetNeedsNoTrackAtAll() {
        val (_, fx) = run(
            SteeringEvent.Key(input(SteeringKey.NEXT, KeyAction.DOWN, 0), null),
            SteeringEvent.Key(input(SteeringKey.NEXT, KeyAction.UP, 0, at = 1_000), null))
        val command = fx.commands().single()
        assertEquals(ShortcutAction.RESET_SELECTION, command.action)
        assertNull(command.target)
    }

    // --- SES01 / OFF01 / §5.4 ---

    @Test fun reconnectingDropsWhateverWasInFlight() {
        val (state, fx) = run(
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0, epoch = 1)),
            key(input(SteeringKey.NEXT, KeyAction.UP, 0, at = 1_000, epoch = 2)))
        assertTrue(fx.commands().isEmpty())
        assertEquals(listOf(DiscardReason.EPOCH_CHANGED), fx.discards())
        assertTrue(state.idle)
    }

    @Test fun anotherKeyMidGestureDropsTheFirst() {
        val (_, fx) = run(
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0)),
            key(input(SteeringKey.PREVIOUS, KeyAction.DOWN, 100)),
            key(input(SteeringKey.PREVIOUS, KeyAction.UP, 100, at = 1_100)))
        assertEquals(listOf(DiscardReason.OTHER_KEY), fx.discards())
        assertEquals(listOf(ShortcutAction.REASSESS_CONTEXT), fx.commands().map { it.action })
    }

    @Test fun turningTheFeatureOffDropsAnUnfinishedGesture() {
        val (state, fx) = run(
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0)),
            SteeringEvent.Invalidate(DiscardReason.FEATURE_OFF))
        assertEquals(listOf(DiscardReason.FEATURE_OFF), fx.discards())
        assertTrue(state.idle)
    }

    @Test fun invalidatingWhenNothingIsInFlightSaysNothing() {
        val (_, fx) = run(SteeringEvent.Invalidate(DiscardReason.DISCONNECTED))
        assertTrue(fx.isEmpty())
    }

    @Test fun anUpForAPressThatWasNeverSeenIsIgnored() {
        val (_, fx) = run(key(input(SteeringKey.NEXT, KeyAction.UP, 0, at = 1_000)))
        assertTrue(fx.commands().isEmpty())
        assertTrue(fx.discards().isEmpty())
    }

    // --- a gesture that is not switched on still leaves the button working ---

    @Test fun aDisabledGestureStillPassesTheOrdinaryAction() {
        val (_, fx) = run(
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0, owner = BaseHandlingOwner.DRIVEMUSE)),
            key(input(SteeringKey.NEXT, KeyAction.UP, 0, at = 1_000, owner = BaseHandlingOwner.DRIVEMUSE)),
            enabled = setOf(Shortcut.SC02))
        assertEquals(1, fx.bases().size)
        assertTrue(fx.commands().isEmpty())
        assertEquals(listOf(DiscardReason.GESTURE_DISABLED), fx.discards())
    }

    @Test fun nothingEnabledMeansNoCommandsAtAll() {
        val (_, fx) = run(
            key(input(SteeringKey.PLAY_PAUSE, KeyAction.DOWN, 0)),
            key(input(SteeringKey.PLAY_PAUSE, KeyAction.UP, 0, at = 1_000)),
            enabled = emptySet())
        assertTrue(fx.commands().isEmpty())
    }

    // --- the four defects this round ---

    @Test fun holdingPastTheSnapshotAgeStillRates() {
        // The dislike window is 800ms to 5s. Measuring the snapshot's age at the release made the
        // hold itself count as staleness, so anything held beyond about two seconds was dropped
        // silently — with no feedback to the driver at all.
        val fresh = track("t1", at = 0)
        val (_, fx) = run(
            SteeringEvent.Key(input(SteeringKey.PLAY_PAUSE, KeyAction.DOWN, 0), fresh),
            SteeringEvent.Key(input(SteeringKey.PLAY_PAUSE, KeyAction.UP, 0, at = 4_500), track("t1", at = 4_500)))
        assertEquals(listOf(ShortcutAction.RATE_DOWN), fx.commands().map { it.action })
        assertEquals("t1", fx.commands().single().target?.trackId)
    }

    @Test fun aSnapshotAlreadyOldAtTheDownIsStillRefused() {
        val stale = track("t1", at = -t.trackSnapshotMaxAgeMs - 1)
        val (_, fx) = run(
            SteeringEvent.Key(input(SteeringKey.PLAY_PAUSE, KeyAction.DOWN, 0), stale),
            SteeringEvent.Key(input(SteeringKey.PLAY_PAUSE, KeyAction.UP, 0, at = 1_000), stale))
        assertEquals(listOf(DiscardReason.STALE_SNAPSHOT), fx.discards())
    }

    @Test fun aResyncPressHeldTooLongDoesNotClearTheBlock() {
        var state = SteeringState.initial(all)
        val first = input(SteeringKey.NEXT, KeyAction.DOWN, 0)
        state = reducer.reduce(state, key(first)).state
        state = reducer.reduce(state, SteeringEvent.Timeout("stuck:${first.pressId}", t.stuckPressMs)).state

        // The re-sync attempt is itself held past the stuck threshold, and its UP beats the timer.
        val second = reducer.reduce(state, key(input(SteeringKey.NEXT, KeyAction.DOWN, 10_000)))
        val release = reducer.reduce(second.state, key(input(SteeringKey.NEXT, KeyAction.UP, 10_000, at = 10_000 + t.stuckPressMs)))
        assertEquals(listOf(DiscardReason.STUCK_PRESS), release.effects.discards())

        // Still blocked, so the next long press is a re-sync rather than a command.
        val thirdDown = reducer.reduce(release.state, key(input(SteeringKey.NEXT, KeyAction.DOWN, 20_000)))
        val thirdUp = reducer.reduce(thirdDown.state, key(input(SteeringKey.NEXT, KeyAction.UP, 20_000, at = 21_000)))
        assertTrue(thirdUp.effects.commands().isEmpty())
        assertEquals(listOf(DiscardReason.RESYNC), thirdUp.effects.discards())
    }

    @Test fun aSessionHandoverMidPressDropsTheGesture() {
        val (state, fx) = run(
            key(input(SteeringKey.PLAY_PAUSE, KeyAction.DOWN, 0)),
            SteeringEvent.Key(
                input(SteeringKey.PLAY_PAUSE, KeyAction.UP, 0, at = 1_000).copy(mediaSessionEpoch = 8),
                track(session = 8, at = 1_000)))
        assertTrue(fx.commands().isEmpty())
        assertEquals(listOf(DiscardReason.SESSION_CHANGED), fx.discards())
        assertTrue(state.idle)
    }

    @Test fun aHandoverSeenOnlyInTheReleaseSnapshotAlsoDropsTheRating() {
        val (_, fx) = run(
            SteeringEvent.Key(input(SteeringKey.PLAY_PAUSE, KeyAction.DOWN, 0), track("t1", 0)),
            SteeringEvent.Key(input(SteeringKey.PLAY_PAUSE, KeyAction.UP, 0, at = 1_000), track("t1", 1_000, session = 8)))
        assertEquals(listOf(DiscardReason.SESSION_CHANGED), fx.discards())
    }

    @Test fun twoStuckKeysStayBlockedIndependently() {
        var state = SteeringState.initial(all)
        val a = input(SteeringKey.NEXT, KeyAction.DOWN, 0)
        state = reducer.reduce(state, key(a)).state
        state = reducer.reduce(state, SteeringEvent.Timeout("stuck:${a.pressId}", t.stuckPressMs)).state
        val b = input(SteeringKey.PREVIOUS, KeyAction.DOWN, 6_000)
        state = reducer.reduce(state, key(b)).state
        state = reducer.reduce(state, SteeringEvent.Timeout("stuck:${b.pressId}", 6_000 + t.stuckPressMs)).state

        // The second key going stuck must not un-block the first.
        val down = reducer.reduce(state, key(input(SteeringKey.NEXT, KeyAction.DOWN, 20_000)))
        val up = reducer.reduce(down.state, key(input(SteeringKey.NEXT, KeyAction.UP, 20_000, at = 21_000)))
        assertEquals(listOf(DiscardReason.RESYNC), up.effects.discards())
    }

    @Test fun theSameKeyStartingAgainIsALostRelease() {
        val (_, fx) = run(
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0)),
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 2_000)),
            key(input(SteeringKey.NEXT, KeyAction.UP, 2_000, at = 3_000)))
        // Not "another button was pressed": the previous UP simply never arrived.
        assertEquals(listOf(DiscardReason.PRESS_SUPERSEDED, DiscardReason.RESYNC), fx.discards())
        assertTrue(fx.commands().isEmpty())
    }

    @Test fun switchingAGestureOffMidPressAbandonsIt() {
        val (state, fx) = run(
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0)),
            SteeringEvent.SetEnabled(all - Shortcut.SC01),
            key(input(SteeringKey.NEXT, KeyAction.UP, 0, at = 1_000)))
        assertEquals(listOf(DiscardReason.FEATURE_OFF), fx.discards())
        assertTrue(fx.commands().isEmpty())
        assertEquals(all - Shortcut.SC01, state.enabled)
    }

    @Test fun switchingOneOnDoesNotDisturbAPressInProgress() {
        val (state, fx) = run(
            key(input(SteeringKey.NEXT, KeyAction.DOWN, 0)),
            SteeringEvent.SetEnabled(all),
            key(input(SteeringKey.NEXT, KeyAction.UP, 0, at = 1_000)),
            enabled = all - Shortcut.SC02)
        assertEquals(listOf(ShortcutAction.RESET_SELECTION), fx.commands().map { it.action })
        assertEquals(all, state.enabled)
    }

    // --- §3: capability records gate everything above ---

    @Test fun onlyAVerifiedCapabilityIsUsable() {
        assertFalse(InputCapability.UNTESTED.usable)
        assertFalse(InputCapability.UNSUPPORTED.usable)
        assertTrue(InputCapability.SUPPORTED.usable)
        assertTrue(InputCapability.LIMITED.usable)
    }

    private val verified = CapabilityRecord("car", Transport.BLUETOOTH, Shortcut.SC01,
        InputCapability.SUPPORTED, checkedAt = 1, appVersionCode = 51, osBuild = "TQ3A", playerVersion = "8.9")

    @Test fun anyChangeAsksForARecheck() {
        assertFalse(verified.stale(51, "TQ3A", "8.9"))
        assertTrue(verified.stale(52, "TQ3A", "8.9"))
        assertTrue(verified.stale(51, "UP1A", "8.9"))
        assertTrue(verified.stale(51, "TQ3A", "9.0"))
        // Never tested is not stale; it simply is not usable.
        assertFalse(CapabilityRecord("car", Transport.BLUETOOTH, Shortcut.SC01).stale(51, "TQ3A", "8.9"))
    }

    @Test fun onlyAChangeOutsideOurControlSwitchesItOff() {
        // versionCode moves on every PR here, so gating on it would disable the feature every
        // release. An OS or player update can genuinely re-route the buttons.
        assertFalse(verified.blocked("TQ3A", "8.9"))
        assertTrue(verified.active(userEnabled = true, osBuild = "TQ3A", playerVersion = "8.9"))
        assertTrue(verified.blocked("UP1A", "8.9"))
        assertTrue(verified.blocked("TQ3A", "9.0"))
        assertFalse(verified.active(true, "UP1A", "8.9"))
    }

    @Test fun theUserSwitchStillHasToBeOn() {
        assertFalse(verified.active(userEnabled = false, osBuild = "TQ3A", playerVersion = "8.9"))
        assertFalse(CapabilityRecord("car", Transport.BLUETOOTH, Shortcut.SC01)
            .active(userEnabled = true, osBuild = "TQ3A", playerVersion = "8.9"))
    }

    @Test fun eachMappingIsItsOwnResult() {
        // One gesture working says nothing about the others, so the four are separate rows.
        assertEquals(4, Shortcut.entries.size)
        assertEquals(Shortcut.SC03, Shortcut.of(SteeringKey.PLAY_PAUSE, Gesture.DOUBLE_TAP))
        assertEquals(Shortcut.SC04, Shortcut.of(SteeringKey.PLAY_PAUSE, Gesture.LONG_PRESS))
        assertNull(Shortcut.of(SteeringKey.NEXT, Gesture.DOUBLE_TAP))
    }
}
