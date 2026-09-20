package ai.drivemuse.app
import ai.drivemuse.app.context.KmaGrid
import ai.drivemuse.app.gemini.JsonGate
import org.json.JSONObject
import org.json.JSONArray
import kotlin.test.*
class ContractsTest {
    @Test fun nonIntegerRevisionRejected() { assertFails { JsonGate.integer(JSONObject("{\"v\":1.5}"),"v") } }
    @Test fun numericStringRejected() { assertFails { JsonGate.number(JSONObject("{\"v\":\"0.9\"}"),"v") } }
    @Test fun unknownFieldsRejected() { assertFails { JsonGate.keys(JSONObject("{\"a\":1,\"execute\":true}"),"a") } }
    @Test fun idsMustBeStrings() { assertFails { JsonGate.strings(JSONArray("[1]"),3) } }
    @Test fun fourthTrackRejected() { assertFails { JsonGate.strings(JSONArray("[\"a\",\"b\",\"c\",\"d\"]"),3) } }
    // R03: the selector contract is Policy.BATCH_SIZE, not the literal 3 that outlived the 3->8 change.
    private fun ids(n: Int) = JSONArray((1..n).map { "t$it" })
    @Test fun batchSizeIsEight() { assertEquals(8,ai.drivemuse.domain.Policy.BATCH_SIZE) }
    @Test fun emptySelectionAccepted() { assertEquals(0,JsonGate.strings(ids(0),ai.drivemuse.domain.Policy.BATCH_SIZE).size) }
    @Test fun oneTrackAccepted() { assertEquals(1,JsonGate.strings(ids(1),ai.drivemuse.domain.Policy.BATCH_SIZE).size) }
    @Test fun threeTracksAccepted() { assertEquals(3,JsonGate.strings(ids(3),ai.drivemuse.domain.Policy.BATCH_SIZE).size) }
    @Test fun fullBatchAccepted() { assertEquals(8,JsonGate.strings(ids(8),ai.drivemuse.domain.Policy.BATCH_SIZE).size) }
    @Test fun overfullBatchRejected() { assertFails { JsonGate.strings(ids(9),ai.drivemuse.domain.Policy.BATCH_SIZE) } }
    // The review window is a different number and must not follow the batch size.
    @Test fun reviewWindowStaysThree() { assertFails { JsonGate.strings(ids(4),3) } }
    @Test fun seoulGrid() { assertEquals(60 to 127,KmaGrid.from(37.5665,126.978));assertNull(KmaGrid.from(0.0,0.0)) }
}
