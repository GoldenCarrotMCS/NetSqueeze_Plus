package me.albert.netsqueeze.protocol;

import java.util.Arrays;
import java.util.UUID;

/** v5 negotiates dictionary frames and explicitly selects optional v4 snapshot envelopes. */
public final class DictionaryProtocol {
    public static final String HELLO = "hello_v5_zstd_dict";
    public static final String ENABLE = "enable_v5_zstd_dict";
    public static final String OFFER = "tunnel_offer_v5";
    public static final String DATA = "tunnel_data_v5";
    public static final String STOP = "tunnel_stop_v5";
    private static final String ACK = "tunnel_ack_v5/";
    private DictionaryProtocol() { }
    public record Offer(ChunkCacheProtocol.Offer base, boolean cache) { }
    public static byte[] encode(ChunkCacheProtocol.Offer base, boolean cache) {
        if (!base.zstd()) throw new IllegalArgumentException("Dictionary offer requires ZSTD");
        byte[] bytes = Arrays.copyOf(ChunkCacheProtocol.offer(base), 42);
        bytes[41] = (byte)(cache ? 1 : 0);
        return bytes;
    }
    public static Offer decode(byte[] bytes) {
        if (bytes.length != 42 || (bytes[41] != 0 && bytes[41] != 1))
            throw new IllegalArgumentException("Invalid dictionary offer");
        var base = ChunkCacheProtocol.offer(Arrays.copyOf(bytes, 41));
        if (!base.zstd()) throw new IllegalArgumentException("Dictionary offer without ZSTD");
        return new Offer(base, bytes[41] == 1);
    }
    public static String ack(UUID session, int protocol, boolean cache) {
        if (protocol <= 0) throw new IllegalArgumentException("Invalid protocol");
        return ACK + protocol + "/" + (cache ? "cache/" : "plain/") + session;
    }
}
