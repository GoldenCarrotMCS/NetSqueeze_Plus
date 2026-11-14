package me.albert.netsqueeze.client;

import com.github.luben.zstd.ZstdCompressCtx;
import me.albert.netsqueeze.protocol.ZstdDictionary;
import java.util.Random;
import java.util.Arrays;

/** Probe the exact dictionary API on the actual modpack runtime (including Axiom's older JNI classes). */
final class ZstdDictionarySupport {
    private ZstdDictionarySupport() { }
    private static final class Probe { static final boolean AVAILABLE = probe(); }
    static boolean available() {
        return ZstdSupport.available() && !Boolean.getBoolean("netsqueeze.zstd.dictionary.disable") && Probe.AVAILABLE;
    }
    private static boolean probe() {
        try (ZstdDictionary dictionary = new ZstdDictionary(); ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            byte[] raw = new byte[24 * 1024];
            new Random(739016).nextBytes(raw);
            dictionary.update(raw);
            ctx.setLevel(1).setContentSize(true).setChecksum(true).loadDict(dictionary.content());
            byte[] compressed = ctx.compress(raw);
            byte[] decoded = ZstdFrameDecoder.decode(compressed, dictionary.content());
            if (!Arrays.equals(raw, decoded)) throw new IllegalStateException("Dictionary probe mismatch");
            NetSqueezeClient.LOGGER.info("ZSTD raw-content dictionary probe passed (64 KiB/session)");
            return true;
        } catch (LinkageError | RuntimeException e) {
            NetSqueezeClient.LOGGER.warn("ZSTD dictionary unavailable; retaining independent ZSTD", e);
            return false;
        }
    }
}
