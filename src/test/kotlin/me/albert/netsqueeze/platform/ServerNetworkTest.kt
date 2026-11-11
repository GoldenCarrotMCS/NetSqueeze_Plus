package me.albert.netsqueeze.platform

import org.junit.jupiter.api.Test
import kotlin.test.*

class ServerNetworkTest {
    @Test
    fun `only exact validated version is accepted`() {
        assertTrue(ServerNetwork.supports("26.1.2"))
        for (version in listOf("26.1.20", "26.1.2-snapshot", "26.1.3", "26.2", "1.21.11")) {
            assertFalse(ServerNetwork.supports(version), version)
        }
    }
}
