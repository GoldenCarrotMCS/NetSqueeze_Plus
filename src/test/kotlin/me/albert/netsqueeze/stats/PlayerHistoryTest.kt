package me.albert.netsqueeze.stats

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class PlayerHistoryTest {
    @Test
    fun `offline capacity never evicts online records`() {
        val history = PlayerHistory(maxOffline = 2)
        val online = UUID.randomUUID()
        val tally = history.open(online, "online", Any())
        repeat(10) {
            val id = UUID.randomUUID()
            val session = Any()
            history.open(id, "offline-$it", session)
            history.retire(id, session)
        }
        assertEquals(3, history.entries.size)
        assertSame(tally, history.entries[online])
    }

    @Test
    fun `stale quit cannot retire a new session and name is refreshed`() {
        val history = PlayerHistory(maxOffline = 0)
        val uuid = UUID.randomUUID()
        val old = Any()
        val row = history.open(uuid, "old", old)
        assertSame(row, history.open(uuid, "old", old))
        val current = Any()
        assertNotSame(row, history.open(uuid, "new", current))
        history.retire(uuid, old)
        assertEquals("new", history.entries[uuid]?.name)
        history.retire(uuid, current)
        assertTrue(history.entries.isEmpty())
    }

    @Test
    fun `expired offline rows are evicted on next lifecycle operation`() {
        var now = 0L
        val history = PlayerHistory(ttlMillis = 100, clock = { now })
        val uuid = UUID.randomUUID()
        val session = Any()
        history.open(uuid, "old", session)
        history.retire(uuid, session)
        now = 101
        history.open(UUID.randomUUID(), "new", Any())
        assertFalse(history.entries.containsKey(uuid))
    }

    @Test
    fun `reset retains online identity and clears offline history`() {
        val history = PlayerHistory()
        val uuid = UUID.randomUUID()
        val row = history.open(uuid, "online", Any())
        row.wire.add(100)
        val offline = UUID.randomUUID()
        val session = Any()
        history.open(offline, "offline", session)
        history.retire(offline, session)
        history.reset()
        assertSame(row, history.entries[uuid])
        assertEquals(0L, row.wire.sum())
        assertEquals(1, history.entries.size)
    }
}
