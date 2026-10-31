package com.mitansh.sidea.ui

import org.junit.Assert.*
import org.junit.Test

class SleepTimerRequestsTest {
    private val off = SleepTimerStatus("off", 0L)
    private val active = SleepTimerStatus("duration", 60_000L)

    @Test fun onlyActualServiceResponseChangesStatus() {
        val requests = SleepTimerRequests()
        assertFalse(requests.state.ready)
        val get = requests.begin(false)!!
        assertNull(requests.begin(false))
        assertTrue(requests.complete(get, true, off))
        assertTrue(requests.state.ready)
        val set = requests.begin(true)!!
        assertTrue(requests.state.pending)
        assertEquals(off, requests.state.status)
        assertNull(requests.begin(true))
        assertNull(requests.begin(false))
        requests.complete(set, true, active)
        assertEquals(active, requests.state.status)
        assertFalse(requests.state.pending)
        val cancel = requests.begin(true)!!
        assertEquals(active, requests.state.status)
        requests.complete(cancel, true, off)
        assertEquals(off, requests.state.status)
    }

    @Test fun failedSetProcessesReturnedSnapshotWithoutClaimingSuccess() {
        val requests = SleepTimerRequests()
        requests.complete(requests.begin(false)!!, true, active)
        requests.complete(requests.begin(true)!!, false, active, "No current song. Play a song first.")
        assertEquals(active, requests.state.status)
        assertEquals("No current song. Play a song first.", requests.state.error)
        assertFalse(requests.state.pending)
        requests.complete(requests.begin(false)!!, true, active)
        assertNotNull("Polling must not immediately hide action errors", requests.state.error)
        requests.complete(requests.begin(true)!!, false, null, "Sleep timer unavailable.")
        assertEquals(active, requests.state.status)
        assertFalse(requests.state.ready)
    }

    @Test fun unknownSuccessfulSnapshotIsNotReady() {
        val requests = SleepTimerRequests()
        requests.complete(requests.begin(false)!!, true, null)
        assertFalse(requests.state.ready)
        assertNull(requests.state.status)
        assertNotNull(requests.state.error)
    }

    @Test fun newSetFencesOlderPollAndDisposalFencesAllCallbacks() {
        val requests = SleepTimerRequests()
        val oldGet = requests.begin(false)!!
        val set = requests.begin(true)!!
        assertFalse(requests.complete(oldGet, true, off))
        assertTrue(requests.state.pending)
        assertTrue(requests.complete(set, true, active))
        val last = requests.begin(false)!!
        requests.dispose()
        assertFalse(requests.complete(last, true, off))
        assertEquals(active, requests.state.status)
        assertNull(requests.begin(true))
    }
}
