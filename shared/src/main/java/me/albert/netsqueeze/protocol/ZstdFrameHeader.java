package me.albert.netsqueeze.protocol;

/**
 * Checks the frame's memory requirements before invoking JNI. No native dependency.
 * Format: facebook/zstd/doc/zstd_compression_format.md, Frame_Header.
 * NetSqueeze uses standard, content-sized frames without trained dictionary IDs.
 * Negotiated raw-content dictionaries are verified by an outer SHA-256 envelope.
 */
public final class ZstdFrameHeader {
    public static final long MAX_WINDOW = 8L * 1024 * 1024;
    public static final int MAX_CONTENT = 8 * 1024 * 1024 + 5;
    public static final int MAX_FRAME = 16 * 1024 * 1024;
    private ZstdFrameHeader() { }

    public record Header(int contentSize, long windowSize) { }

    public static Header validate(byte[] frame) {
        if (frame == null || frame.length < 6 || frame.length > MAX_FRAME)
            throw new IllegalArgumentException("Invalid ZSTD frame size");
        if ((frame[0] & 255) != 0x28 || (frame[1] & 255) != 0xb5 ||
                (frame[2] & 255) != 0x2f || (frame[3] & 255) != 0xfd)
            throw new IllegalArgumentException("Not a standard ZSTD frame");
        int descriptor = frame[4] & 255;
        // Reserved bit must be zero; the unused bit has no decoding semantics.
        if ((descriptor & 8) != 0 || (descriptor & 3) != 0)
            throw new IllegalArgumentException("Reserved flag or external ZSTD dictionary");
        boolean single = (descriptor & 32) != 0;
        int sizeFlag = descriptor >>> 6;
        int sizeBytes = switch (sizeFlag) {
            case 0 -> single ? 1 : 0;
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 8;
            default -> throw new IllegalArgumentException("Invalid content-size flag");
        };
        if (sizeBytes == 0) throw new IllegalArgumentException("Unknown ZSTD content size");
        int offset = 5;
        long window = 0;
        if (!single) {
            int wd = frame[offset++] & 255;
            long base = 1L << (10 + (wd >>> 3));
            window = base + (base >>> 3) * (wd & 7);
            if (window > MAX_WINDOW) throw new IllegalArgumentException("ZSTD window exceeds 8 MiB");
        }
        if (frame.length - offset < sizeBytes)
            throw new IllegalArgumentException("Truncated ZSTD frame header");
        // The protocol limit fits in an int. Reject large unsigned values before shifting.
        if (sizeBytes == 8) {
            for (int i = 4; i < 8; i++) if (frame[offset + i] != 0)
                throw new IllegalArgumentException("Oversized ZSTD content size");
        }
        long content = 0;
        for (int i = 0; i < Math.min(sizeBytes, 4); i++) content |= (frame[offset + i] & 255L) << (8 * i);
        if (sizeBytes == 2) content += 256;
        if (content <= 0 || content > MAX_CONTENT)
            throw new IllegalArgumentException("ZSTD content exceeds protocol limit");
        if (single) window = content;
        if (window > MAX_WINDOW) throw new IllegalArgumentException("ZSTD window exceeds 8 MiB");
        return new Header((int) content, window);
    }
}
