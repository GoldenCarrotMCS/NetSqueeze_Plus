package me.albert.netsqueeze.client;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;

/** Probe once per client process; a broken/unsupported native library never advertises ZSTD. */
final class ZstdSupport {
    private ZstdSupport() { }
    private static final class Probe {
        private static final boolean AVAILABLE = probe();
    }
    static boolean available() {
        return !Boolean.getBoolean("netsqueeze.zstd.disable") && Probe.AVAILABLE;
    }
    private static boolean probe() {
        try {
            byte[] compressed = Zstd.compress(new byte[]{1}, 1);
            ZstdDecompressCtx ctx = new ZstdDecompressCtx();
            try { return ctx.setLongMax(23).decompress(compressed, 1)[0] == 1; }
            finally { ctx.close(); }
        } catch (LinkageError | RuntimeException e) {
            NetSqueezeClient.LOGGER.warn("ZSTD native support unavailable; retaining Deflate", e);
            return false;
        }
    }
}
