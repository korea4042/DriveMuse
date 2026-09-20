package ai.drivemuse.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * The codec carries the only record of what a car was proven to do, and the run decides what may
 * be claimed from a stationary test. Neither had a test; both are places where a quiet mistake
 * turns into a wrong support claim on screen.
 */
class CapabilityCodecTest {

    private fun record(
        shortcut: Shortcut = Shortcut.SC01,
        capability: InputCapability = InputCapability.LIMITED,
        conditions: Set<CapabilityCondition> = setOf(CapabilityCondition.FOREGROUND_ONLY),
        note: String = "정차 진단에서 10회 확인"
    ) = CapabilityRecord("sportage", Transport.BLUETOOTH, shortcut, capability, conditions,
        checkedAt = 1_700_000_000_000, appVersionCode = 55, osBuild = "UP1A.231005", playerVersion = "8.9.10", note = note)

    @Test fun everyFieldSurvivesTheRoundTrip() {
        val all = listOf(
            record(),
            record(Shortcut.SC04, InputCapability.UNSUPPORTED, emptySet(), "길게 누름이 seek으로 바뀜"),
            record(Shortcut.SC03, InputCapability.SUPPORTED,
                setOf(CapabilityCondition.SCREEN_ON, CapabilityCondition.PLAYING_ONLY), ""))
        assertEquals(all, CapabilityCodec.decode(CapabilityCodec.encode(all)))
    }

    @Test fun anEmptyStoreIsAnEmptyList() {
        assertEquals(emptyList<CapabilityRecord>(), CapabilityCodec.decode(""))
        assertEquals(emptyList<CapabilityRecord>(), CapabilityCodec.decode(CapabilityCodec.encode(emptyList())))
    }

    @Test fun oneUnreadableRecordDoesNotTakeTheOthersWithIt() {
        val good = record()
        val raw = CapabilityCodec.encode(listOf(good)) + "\u001e" + "c1\u001fcar\u001fNOT_A_TRANSPORT"
        assertEquals(listOf(good), CapabilityCodec.decode(raw))
        assertEquals(emptyList<CapabilityRecord>(), CapabilityCodec.decode("garbage"))
    }

    @Test fun anUnknownConditionIsDroppedWithoutLosingTheRecord() {
        val raw = CapabilityCodec.encode(listOf(record(conditions = setOf(CapabilityCondition.SCREEN_ON))))
            .replace("SCREEN_ON", "SCREEN_ON,FROM_A_LATER_VERSION")
        val decoded = CapabilityCodec.decode(raw).single()
        assertEquals(setOf(CapabilityCondition.SCREEN_ON), decoded.conditions)
    }

    @Test fun eachVehicleTransportAndMappingIsItsOwnRow() {
        // §3: one gesture working says nothing about the others, and nothing about another car.
        val rows = listOf(
            record(Shortcut.SC01),
            record(Shortcut.SC02).copy(transport = Transport.ANDROID_AUTO),
            record(Shortcut.SC01).copy(vehicleId = "other"))
        val back = CapabilityCodec.decode(CapabilityCodec.encode(rows))
        assertEquals(3, back.map { Triple(it.vehicleId, it.transport, it.shortcut) }.distinct().size)
    }

    // --- what a stationary run is allowed to claim ---

    @Test fun aForegroundRunNeverProposesFullSupport() {
        // §3: passing while the app is on screen is not passing while driving with the screen off.
        val clean = DiagnosticRun(Shortcut.SC01, attempts = 10, gesturesConfirmed = 10, baseDispatches = 10)
        assertTrue(clean.clean)
        assertEquals(InputCapability.LIMITED, clean.proposed())
        assertTrue(clean.proposed() != InputCapability.SUPPORTED)
    }

    @Test fun nothingConfirmedIsAnAnswerToo() {
        val nothing = DiagnosticRun(Shortcut.SC03, attempts = 10, gesturesConfirmed = 0)
        assertEquals(InputCapability.UNSUPPORTED, nothing.proposed())
        assertFalse(nothing.clean)
    }

    @Test fun anUnattemptedMappingStaysUntested() {
        val idle = DiagnosticRun(Shortcut.SC02)
        assertEquals(InputCapability.UNTESTED, idle.proposed())
        assertFalse(idle.clean)
    }

    @Test fun aPartialRunIsNotClean() {
        val partial = DiagnosticRun(Shortcut.SC04, attempts = 10, gesturesConfirmed = 7)
        assertFalse(partial.clean)
        // Still usable evidence, but only as the conditional claim.
        assertEquals(InputCapability.LIMITED, partial.proposed())
    }

    @Test fun aRunWithDiscardsIsNotClean() {
        val noisy = DiagnosticRun(Shortcut.SC01, attempts = 10, gesturesConfirmed = 10,
            discards = listOf(DiscardReason.PRESS_SUPERSEDED))
        assertFalse(noisy.clean)
    }

    @Test fun aSavedForegroundResultDoesNotSurviveAPlayerUpdate() {
        val saved = record(capability = InputCapability.LIMITED)
        assertTrue(saved.active(userEnabled = true, osBuild = "UP1A.231005", playerVersion = "8.9.10"))
        assertFalse(saved.active(userEnabled = true, osBuild = "UP1A.231005", playerVersion = "9.0.0"))
        assertFalse(saved.active(userEnabled = true, osBuild = "TQ3A.230901", playerVersion = "8.9.10"))
    }
}
