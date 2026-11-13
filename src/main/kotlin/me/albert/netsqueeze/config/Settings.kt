package me.albert.netsqueeze.config

import org.bukkit.configuration.file.FileConfiguration

/** 仅读取一次；压缩器持有不可变快照，配置修改需要重启。 */
internal data class Settings(
    val enabled: Boolean = true,
    val compressionLevel: Int = 6,
    val maxFrameRawBytes: Int = 8 * 1024 * 1024,
    val logFirstFailure: Boolean = true,
    val transportMode: TransportMode = TransportMode.AUTO,
    val tunnelMinPacketBytes: Int = 256,
    val zstdMinPacketBytes: Int = 64 * 1024,
    val zstdLevel: Int = 1,
    val zstdEnabled: Boolean = true,
    val chunkCacheEnabled: Boolean = false,
    val chunkCacheNamespace: String = "00000000-0000-0000-0000-000000000000",
    val chunkCacheEpoch: Int = 0
) {
    init {
        java.util.UUID.fromString(chunkCacheNamespace)
        require(chunkCacheEpoch >= 0) { "chunk-cache-epoch 不能为负数" }
        require(zstdMinPacketBytes in 1024..8 * 1024 * 1024) { "zstd-min-packet-bytes 必须在 1024..8388608 之间" }
        require(zstdLevel in 1..5) { "zstd-level 必须在 1..5 之间" }
        require(tunnelMinPacketBytes in 64..8 * 1024 * 1024) { "tunnel-min-packet-bytes 必须在 64..8388608 之间" }
        require(compressionLevel in 0..9) { "compression-level 必须在 0..9 之间" }
        require(maxFrameRawBytes in 1024..8 * 1024 * 1024) {
            "max-frame-raw-bytes 必须在 1024..8388608 之间，不能超过协议安全边界"
        }
    }

    companion object {
        fun load(config: FileConfiguration): Settings {
            for (key in listOf("enabled", "log-first-failure", "zstd-enabled", "chunk-cache-enabled")) {
                require(!config.contains(key) || config.isBoolean(key)) { "$key 必须是布尔值 true/false" }
            }
            for (key in listOf("compression-level", "max-frame-raw-bytes", "tunnel-min-packet-bytes", "zstd-min-packet-bytes", "zstd-level", "chunk-cache-epoch")) {
                require(!config.contains(key) || config.isInt(key)) { "$key 必须是整数" }
            }
            return Settings(
                enabled = config.getBoolean("enabled", true),
                compressionLevel = config.getInt("compression-level", 6),
                maxFrameRawBytes = config.getInt("max-frame-raw-bytes", 8 * 1024 * 1024),
                logFirstFailure = config.getBoolean("log-first-failure", true),
                transportMode = TransportMode.parse(config.getString("transport-mode", "auto") ?: "auto"),
                tunnelMinPacketBytes = config.getInt("tunnel-min-packet-bytes", 256),
                zstdMinPacketBytes = config.getInt("zstd-min-packet-bytes", 64 * 1024),
                zstdLevel = config.getInt("zstd-level", 1),
                zstdEnabled = config.getBoolean("zstd-enabled", true),
                chunkCacheEnabled = config.getBoolean("chunk-cache-enabled", false),
                chunkCacheNamespace = config.getString("chunk-cache-namespace", "00000000-0000-0000-0000-000000000000")!!,
                chunkCacheEpoch = config.getInt("chunk-cache-epoch", 0)
            )
        }
    }
}
