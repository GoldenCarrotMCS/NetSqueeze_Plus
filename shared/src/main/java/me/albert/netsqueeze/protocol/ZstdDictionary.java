package me.albert.netsqueeze.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** One connection/session, 64 KiB raw-content dictionary. Never persisted or shared with another player.
 * Borrowed arrays are valid only until the next update/close; all access is connection-thread confined.
 */
public final class ZstdDictionary implements AutoCloseable {
    public static final int BYTES = 64 * 1024;
    public static final int HASH_BYTES = 32;
    public static final int MARKER = 2;
    private static final byte[] PREFIX = {'N', 'S', 'D', '1'};
    private final MessageDigest hasher = newHasher();
    private byte[] content;
    private byte[] digest;
    private boolean closed;
    public byte[] content() { return content; }
    public byte[] digest() { return digest; }
    public boolean ready() { return content != null; }
    public void verify(byte[] expected) {
        if (closed || expected == null || digest == null || !MessageDigest.isEqual(digest, expected))
            throw new IllegalArgumentException("ZSTD dictionary missing or out of sync");
    }
    /** Called only for a complete successful ZSTD frame, before downstream callbacks can close this session. */
    public void update(byte[] raw) {
        if (closed) throw new IllegalStateException("Dictionary closed");
        if (raw.length == 0 || raw.length > ZstdFrameHeader.MAX_CONTENT)
            throw new IllegalArgumentException("Invalid dictionary source size");
        int size = Math.min(raw.length, BYTES - PREFIX.length);
        // Prepare the digest before touching published history. Same-length updates
        // overwrite every byte, avoiding a 64 KiB allocation and redundant zero fill.
        hasher.reset();
        hasher.update(PREFIX);
        hasher.update(raw, raw.length - size, size);
        byte[] hash = hasher.digest();
        byte[] previous = content;
        byte[] next = previous != null && previous.length == PREFIX.length + size
            ? previous : new byte[PREFIX.length + size];
        // A non-dictionary magic prefix forces libzstd's raw-content interpretation.
        System.arraycopy(PREFIX, 0, next, 0, PREFIX.length);
        System.arraycopy(raw, raw.length - size, next, PREFIX.length, size);
        if (previous != null && previous != next) Arrays.fill(previous, (byte)0);
        content = next; digest = hash;
    }
    private static MessageDigest newHasher() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    @Override public void close() {
        closed = true;
        if (content != null) Arrays.fill(content, (byte)0);
        hasher.reset();
        content = null; digest = null;
    }
}
