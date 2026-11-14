package me.albert.netsqueeze.client;

import com.github.luben.zstd.Zstd;

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
            byte[] restored = ZstdFrameDecoder.decode(compressed);
            if (restored.length != 1 || restored[0] != 1) throw new IllegalStateException("ZSTD probe round-trip mismatch");
            NetSqueezeClient.LOGGER.info("ZSTD compatibility probe passed; implementation: {}", implementationSource());
            return true;
        } catch (LinkageError | RuntimeException e) {
            NetSqueezeClient.LOGGER.warn("ZSTD Java API/native compatibility probe failed; retaining Deflate. Implementation: " + implementationSource(), e);
            return false;
        }
    }

    private static String implementationSource() {
        try {
            var source = Zstd.class.getProtectionDomain().getCodeSource();
            return source == null ? "unknown" : source.getLocation().toString();
        } catch (RuntimeException e) { return "unknown"; }
    }
}
