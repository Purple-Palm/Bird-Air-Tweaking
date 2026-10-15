package io.github.purplepalm.birdair.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.concurrent.thread

class GattQueueTest {
    @Test fun completesFromAnotherThread() = runBlocking {
        val q = GattQueue()
        val r = q.run(GattQueue.Op.WRITE_DESC, 2000) {
            thread { Thread.sleep(20); q.complete(GattQueue.Op.WRITE_DESC, 0) }
            true
        }
        assertTrue(r.ok)
    }

    @Test fun oneOperationAtATime() = runBlocking(Dispatchers.Default) {
        val q = GattQueue()
        val log = Collections.synchronizedList(ArrayList<String>())
        val a = async {
            q.run(GattQueue.Op.WRITE_CHAR, 2000) {
                log.add("start a")
                thread { Thread.sleep(80); log.add("done a"); q.complete(GattQueue.Op.WRITE_CHAR, 0) }
                true
            }
        }
        delay(10)
        val b = async {
            q.run(GattQueue.Op.WRITE_CHAR, 2000) {
                log.add("start b")
                thread { Thread.sleep(10); log.add("done b"); q.complete(GattQueue.Op.WRITE_CHAR, 0) }
                true
            }
        }
        assertTrue(a.await().ok)
        assertTrue(b.await().ok)
        assertEquals(listOf("start a", "done a", "start b", "done b"), log.toList())
    }

    @Test fun timeoutRefusalAndWrongCallback() = runBlocking {
        val q = GattQueue()
        assertEquals(GattQueue.TIMEOUT, q.run(GattQueue.Op.DISCOVER, 50) { true }.status)
        assertEquals(GattQueue.NOT_STARTED, q.run(GattQueue.Op.DISCOVER, 50) { false }.status)
        assertEquals(GattQueue.NOT_STARTED, q.run(GattQueue.Op.DISCOVER, 50) { throw SecurityException("no permission") }.status)
        // a write callback does not finish a discovery
        val r = q.run(GattQueue.Op.DISCOVER, 80) { assertFalse(q.complete(GattQueue.Op.WRITE_CHAR, 0)); true }
        assertEquals(GattQueue.TIMEOUT, r.status)
        // nothing waiting: a late callback is ignored
        assertFalse(q.complete(GattQueue.Op.DISCOVER, 0))
        // a failing status comes through as is (133 GATT_ERROR)
        assertEquals(133, q.run(GattQueue.Op.DISCOVER, 1000) { q.complete(GattQueue.Op.DISCOVER, 133); true }.status)
    }

    @Test fun closeFailsTheWaitingOneAndAllLater() = runBlocking(Dispatchers.Default) {
        val q = GattQueue()
        val a = async { q.run(GattQueue.Op.WRITE_CHAR, 5000) { true } }
        delay(30)
        q.close()
        assertEquals(GattQueue.CANCELLED, a.await().status)
        var started = false
        assertEquals(GattQueue.CANCELLED, q.run(GattQueue.Op.WRITE_CHAR, 5000) { started = true; true }.status)
        assertFalse(started)
    }
}
