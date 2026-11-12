package me.albert.netsqueeze.protocol;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.UUID;

/** v3 negotiates marked Deflate/ZSTD; legacy v2 keeps its unmarked format. */
public final class TunnelProtocol {
    public static final String OFFER_PATH = "tunnel_offer_v3";
    public static final String DATA_PATH = "tunnel_data_v3";
    public static final String STOP_PATH = "tunnel_stop_v3";
    public static final String ACK_PATH = "tunnel_ack_v3/";
    public static final String LEGACY_OFFER_PATH = "tunnel_offer_v2";
    public static final String LEGACY_DATA_PATH = "tunnel_data_v2";
    public static final String LEGACY_STOP_PATH = "tunnel_stop_v2";
    public static final int HEADER_BYTES = 24;
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
    public record Offer(UUID session, int protocol, boolean markedFrames, boolean zstd) { }
    public static byte[] encodeOffer(UUID session, int protocol) {
        validateProtocol(protocol);
        return ByteBuffer.allocate(20).putLong(session.getMostSignificantBits())
            .putLong(session.getLeastSignificantBits()).putInt(protocol).array();
    }
    public static byte[] encodeOffer(UUID session, int protocol, boolean zstd) {
        validateProtocol(protocol);
        return ByteBuffer.allocate(21).putLong(session.getMostSignificantBits())
            .putLong(session.getLeastSignificantBits()).putInt(protocol).put((byte) (zstd ? 1 : 0)).array();
    }
    public static Offer decodeOffer(byte[] data) {
        if (data.length != 20 && data.length != 21) throw new IllegalArgumentException("Invalid tunnel offer length");
        ByteBuffer bytes = ByteBuffer.wrap(data);
        UUID session = new UUID(bytes.getLong(), bytes.getLong());
        int protocol = bytes.getInt();
        validateProtocol(protocol);
        boolean marked = data.length == 21;
        int flag = marked ? bytes.get() & 255 : 0;
        if (flag > 1) throw new IllegalArgumentException("Invalid ZSTD capability flag");
        return new Offer(session, protocol, marked, flag == 1);
    }
    public static String offerPath(boolean marked) { return marked ? OFFER_PATH : LEGACY_OFFER_PATH; }
    public static String dataPath(boolean marked) { return marked ? DATA_PATH : LEGACY_DATA_PATH; }
    public static String stopPath(boolean marked) { return marked ? STOP_PATH : LEGACY_STOP_PATH; }
    public static String ackPath(UUID session, int protocol) { return ackPath(session, protocol, false, false); }
    public static String ackPath(UUID session, int protocol, boolean marked, boolean zstd) {
        validateProtocol(protocol);
        if (!marked) return "tunnel_ack_v2/" + protocol + "/" + session;
        return ACK_PATH + protocol + "/" + (zstd ? "zstd" : "deflate") + "/" + session;
    }
    private static void validateProtocol(int protocol) {
        if (!MinecraftVersions.protocolMatches(protocol, protocol)) throw new IllegalArgumentException("Invalid protocol");
    }
}
