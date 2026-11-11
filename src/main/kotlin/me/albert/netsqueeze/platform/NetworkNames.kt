package me.albert.netsqueeze.platform

/** Netty pipeline anchors are probed per connection before any format switch. */
internal object NetworkNames {
    const val PREPENDER = "prepender"
    const val COMPRESS = "compress"
    const val ENCODER = "encoder"
    const val PACKET_HANDLER = "packet_handler"
}
