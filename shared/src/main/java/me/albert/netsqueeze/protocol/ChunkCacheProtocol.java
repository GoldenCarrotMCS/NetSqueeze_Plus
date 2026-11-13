package me.albert.netsqueeze.protocol;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** v4 keeps v3 compression markers; each inner packet has a snapshot opcode. */
public final class ChunkCacheProtocol {
    public static final String OFFER = "tunnel_offer_v4";
    public static final String DATA = "tunnel_data_v4";
    public static final String STOP = "tunnel_stop_v4";
    public static final String ACK = "tunnel_ack_v4/";
    public static final String SEED = "cache_seed_v1/";
    public static final int MAX_PACKET = 256 * 1024;
    public static final int MAX_ENTRIES = 64;
    public static final int MEMORY_BYTES = 4 * 1024 * 1024;
    public static final int MAX_SEEDS = 64;
    public static final int SEED_BYTES = 8 * 1024 * 1024;
    private ChunkCacheProtocol() { }
    public record Offer(UUID session, int protocol, boolean zstd, UUID namespace, int epoch) { }
    public static byte[] offer(Offer o) {
        return ByteBuffer.allocate(41).putLong(o.session.getMostSignificantBits()).putLong(o.session.getLeastSignificantBits())
            .putInt(o.protocol).put((byte)(o.zstd ? 1 : 0)).putLong(o.namespace.getMostSignificantBits())
            .putLong(o.namespace.getLeastSignificantBits()).putInt(o.epoch).array();
    }
    public static Offer offer(byte[] bytes) {
        if (bytes.length != 41) throw new IllegalArgumentException("Invalid v4 offer size");
        ByteBuffer in = ByteBuffer.wrap(bytes);
        UUID session = new UUID(in.getLong(), in.getLong());
        int protocol = in.getInt(), zstd = in.get() & 255;
        if (protocol <= 0 || zstd > 1) throw new IllegalArgumentException("Invalid v4 offer");
        UUID namespace = new UUID(in.getLong(), in.getLong());
        int epoch = in.getInt();
        if (epoch < 0) throw new IllegalArgumentException("Invalid cache epoch");
        return new Offer(session, protocol, zstd == 1, namespace, epoch);
    }
    public static String ack(UUID session, int protocol, boolean zstd) {
        return ACK + protocol + "/" + (zstd ? "zstd" : "deflate") + "/" + session;
    }
    public static String seed(UUID session, String hash) {
        if (!validHash(hash)) throw new IllegalArgumentException("Invalid seed hash");
        return SEED + session + "/" + hash;
    }
    public static boolean validHash(String hash) { return hash != null && hash.matches("[0-9a-f]{64}"); }
    public static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest(bytes)); }
}
