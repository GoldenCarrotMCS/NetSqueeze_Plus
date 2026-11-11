package me.albert.netsqueeze.config

import java.util.Locale

internal enum class TransportMode {
    AUTO, DIRECT, VELOCITY;

    fun useTunnel(proxyConfigured: Boolean?): Boolean = when (this) {
        VELOCITY -> true
        AUTO -> proxyConfigured != false
        DIRECT -> {
            require(proxyConfigured != true) { "后端已启用代理转发，不能强制 direct；请使用 velocity 或 auto" }
            false
        }
    }

    companion object {
        fun parse(value: String): TransportMode = entries.firstOrNull {
            it.name.lowercase(Locale.ROOT) == value.lowercase(Locale.ROOT)
        } ?: throw IllegalArgumentException("transport-mode 必须为 auto/direct/velocity")
    }
}
