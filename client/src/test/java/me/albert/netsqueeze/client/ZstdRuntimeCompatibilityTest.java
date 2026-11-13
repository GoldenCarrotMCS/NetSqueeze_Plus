package me.albert.netsqueeze.client;

import com.github.luben.zstd.ZstdDecompressCtx;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A separate Gradle Test JVM prevents JNI and the cached probe leaking between versions. */
class ZstdRuntimeCompatibilityTest {
    @Test void productionProbeWorksWithTheActualSelectedRuntime() {
        String expected = System.getProperty("netsqueeze.zstd.compat.version");
        if (expected != null) {
            String source = ZstdDecompressCtx.class.getProtectionDomain().getCodeSource().getLocation().toString();
            assertTrue(source.contains("zstd-jni-" + expected), "Unexpected implementation: " + source);
            assertThrows(NoSuchMethodException.class, () -> ZstdDecompressCtx.class.getMethod("setLongMax", int.class),
                "The regression environment must reproduce the missing API");
        }
        assertTrue(ZstdSupport.available(), "The actual production decoder must pass, not skip JNI");
        // Complete, content-sized standard raw-block frame; no external dictionary or native compressor required.
        byte[] frame = {0x28, (byte)0xb5, 0x2f, (byte)0xfd, 0x20, 1, 9, 0, 0, 42};
        assertArrayEquals(new byte[]{42}, ZstdFrameDecoder.decode(frame));
    }
}
