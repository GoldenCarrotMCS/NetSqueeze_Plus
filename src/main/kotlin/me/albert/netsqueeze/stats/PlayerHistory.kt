package me.albert.netsqueeze.stats

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 只锁进退服和淘汰，不锁 Netty 逐包计数。容量只限制离线历史，绝不淘汰在线 tally。 */
internal class PlayerHistory(
    private val maxOffline: Int = 10_000,
    private val ttlMillis: Long = 24L * 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    val entries = ConcurrentHashMap<UUID, PlayerTally>()
    private val sessions = HashMap<UUID, Any>()
    private val retiredAt = HashMap<UUID, Long>()

    @Synchronized
    fun open(uuid: UUID, name: String, session: Any): PlayerTally {
        if (sessions[uuid] === session) {
            return entries.getValue(uuid).also { it.name = name }
        }
        // 先保护正在加入的玩家，再淘汰历史，防止高并发下新 tally 被删除。
        sessions[uuid] = session
        retiredAt.remove(uuid)
        // 不把旧 Channel 手里的计数器交给新 Channel，防重连时旧写入污染新口径。
        val previous = entries[uuid]
        val tally = PlayerTally(name)
        if (previous != null) {
            tally.startSession(previous.streaming)
            tally.raw.add(previous.raw.sum())
            tally.wire.add(previous.wire.sum())
        }
        entries[uuid] = tally
        prune()
        return tally
    }

    @Synchronized
    fun retire(uuid: UUID, session: Any) {
        // 旧连接的 quit 不能把同 UUID 的新会话标成离线。
        if (sessions[uuid] !== session) return
        sessions.remove(uuid)
        retiredAt[uuid] = clock()
        prune()
    }

    @Synchronized
    fun reset() {
        // 保留原来的 reset 语义：离线记录删除，在线计数器对象不替换。
        entries.keys.removeIf { it !in sessions }
        retiredAt.clear()
        entries.values.forEach { it.reset() }
    }

    private fun prune() {
        val now = clock()
        val expired = retiredAt.filterValues { now - it >= ttlMillis }.keys
        expired.forEach { retiredAt.remove(it); entries.remove(it) }
        if (retiredAt.size > maxOffline) {
            retiredAt.entries.sortedBy { it.value }.take(retiredAt.size - maxOffline)
                .map { it.key }.forEach { retiredAt.remove(it); entries.remove(it) }
        }
    }
}
