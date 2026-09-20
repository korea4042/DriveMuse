package ai.drivemuse.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The earlier regression test only asserted that DELIVERY_UNCERTAIN was not in a set, which would
 * have passed just as happily against the bug it was written for: clearing the whole uncertainty
 * the moment any track past the first started. These check the behaviour instead.
 */
class DeliveryLedgerTest {

    @Test fun oneObservedSlotDoesNotSettleTheRest() {
        val ledger = DeliveryLedger()
        ledger.dispatched(listOf("b", "c", "d"))

        assertFalse(ledger.observed("b"), "three unknown slots were settled by one arriving")
        assertFalse(ledger.settled)
        assertEquals(setOf("c", "d"), ledger.unresolved)

        assertFalse(ledger.observed("c"))
        assertTrue(ledger.observed("d"), "the last outstanding slot should settle the ledger")
        assertTrue(ledger.settled)
    }

    @Test fun anUnrelatedTrackSettlesNothing() {
        val ledger = DeliveryLedger()
        ledger.dispatched(listOf("b", "c"))
        // A recording that was never in doubt: accepted outright, or picked by the driver.
        assertFalse(ledger.observed("z"))
        assertEquals(setOf("b", "c"), ledger.unresolved)
    }

    @Test fun aNewDispatchReplacesTheLedger() {
        val ledger = DeliveryLedger()
        ledger.dispatched(listOf("b", "c"))
        // The driver taps a track in the list, which sends a fresh queue of its own.
        ledger.dispatched(listOf("e"))
        assertEquals(setOf("e"), ledger.unresolved, "slots from the abandoned plan were carried over")
        assertTrue(ledger.observed("e"))
    }

    @Test fun anAllAcceptedDispatchIsSettledImmediately() {
        val ledger = DeliveryLedger()
        ledger.dispatched(emptyList())
        assertTrue(ledger.settled)
        assertFalse(ledger.observed("b"), "nothing was outstanding, so nothing can be settled")
    }

    @Test fun observingTheSameSlotTwiceSettlesOnce() {
        val ledger = DeliveryLedger()
        ledger.dispatched(listOf("b", "c"))
        assertFalse(ledger.observed("b"))
        assertFalse(ledger.observed("b"), "a repeat observation must not settle a different slot")
        assertEquals(setOf("c"), ledger.unresolved)
    }

    @Test fun losingControlVoidsTheLedger() {
        val ledger = DeliveryLedger()
        ledger.dispatched(listOf("b", "c"))
        ledger.clear()
        assertTrue(ledger.settled)
        assertTrue(ledger.unresolved.isEmpty())
    }
}
