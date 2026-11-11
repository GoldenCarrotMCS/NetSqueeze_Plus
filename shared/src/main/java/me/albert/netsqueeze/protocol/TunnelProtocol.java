package me.albert.netsqueeze.protocol;

import java.util.Set;
import java.util.UUID;

/** Both artifacts compile this source; no Minecraft, Fabric or Kotlin dependency. */
public final class TunnelProtocol {
    public static final String MINECRAFT_VERSION = "26.1.2";
    public static final String OFFER_PATH = "tunnel_offer_v1_26_1_2";
    public static final String DATA_PATH = "tunnel_data_v1";
    public static final String STOP_PATH = "tunnel_stop_v1";
    public static final String ACK_PATH = "tunnel_ack_v1/";
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

    public static String ackPath(UUID session) { return ACK_PATH + session; }
}
