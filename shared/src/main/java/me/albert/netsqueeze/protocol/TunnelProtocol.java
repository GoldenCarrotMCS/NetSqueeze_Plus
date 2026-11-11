package me.albert.netsqueeze.protocol;

import java.util.Set;
import java.util.UUID;
import java.nio.ByteBuffer;

/** Both artifacts compile this source; no Minecraft, Fabric or Kotlin dependency. */
public final class TunnelProtocol {
    public static final String OFFER_PATH = "tunnel_offer_v2";
    public static final String DATA_PATH = "tunnel_data_v2";
    public static final String STOP_PATH = "tunnel_stop_v2";
    public static final String ACK_PATH = "tunnel_ack_v2/";
    public static final int HEADER_BYTES = 24; // UUID + sequence number, big endian
    public static final int FRAGMENT_BYTES = 256 * 1024;
    public static final int MAX_FRAME = 16 * 1024 * 1024;
    private static final String PREFIX = "net.minecraft.network.protocol.game.";
    private static final Set<String> SAFE_PACKETS = Set.of(
        "ClientboundMoveEntityPacket$Pos", "ClientboundMoveEntityPacket$PosRot", "ClientboundMoveEntityPacket$Rot",
        "ClientboundRotateHeadPacket", "ClientboundSetEntityMotionPacket", "ClientboundSetEntityDataPacket",
        "ClientboundSetEquipmentPacket", "ClientboundBlockUpdatePacket", "ClientboundSectionBlocksUpdatePacket",
        "ClientboundLevelParticlesPacket", "ClientboundSoundPacket", "ClientboundSoundEntityPacket",
        "ClientboundEntityPositionSyncPacket", "ClientboundTeleportEntityPacket",
        "ClientboundLevelChunkWithLightPacket", "ClientboundLightUpdatePacket",
        "ClientboundForgetLevelChunkPacket", "ClientboundBlockEntityDataPacket"
    );

    private TunnelProtocol() { }

    public static boolean allowed(String name) {
        return name.startsWith(PREFIX) && SAFE_PACKETS.contains(name.substring(PREFIX.length()));
    }

    public record Offer(UUID session, int protocol) { }

    public static byte[] encodeOffer(UUID session, int protocol) {
        if (!MinecraftVersions.protocolMatches(protocol, protocol)) throw new IllegalArgumentException("Invalid release protocol");
        return ByteBuffer.allocate(20).putLong(session.getMostSignificantBits())
            .putLong(session.getLeastSignificantBits()).putInt(protocol).array();
    }

    public static Offer decodeOffer(byte[] data) {
        if (data.length != 20) throw new IllegalArgumentException("Invalid tunnel v2 offer length");
        ByteBuffer bytes = ByteBuffer.wrap(data);
        UUID session = new UUID(bytes.getLong(), bytes.getLong());
        int protocol = bytes.getInt();
        if (!MinecraftVersions.protocolMatches(protocol, protocol)) throw new IllegalArgumentException("Invalid release protocol");
        return new Offer(session, protocol);
    }

    public static String ackPath(UUID session, int protocol) {
        if (!MinecraftVersions.protocolMatches(protocol, protocol)) throw new IllegalArgumentException("Invalid release protocol");
        return ACK_PATH + protocol + "/" + session;
    }
}
