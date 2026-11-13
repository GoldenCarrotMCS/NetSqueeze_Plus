package me.albert.netsqueeze.protocol;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Experimental, transport-independent snapshot delta. Not a wire protocol: no caller
 * should substitute a Minecraft packet until cache negotiation and ordering exist.
 * The common prefix and suffix reference the exact base; the middle is literal.
 */
public final class ChunkSnapshotPatch {
    public static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private static final int MAGIC = 0x4e535031; // NSP1
    private static final int HASH_BYTES = 32;
    private static final int HEADER_BYTES = 4 + 4 * 3 + HASH_BYTES * 2;

    private ChunkSnapshotPatch() { }

    /** Returns null when the uncompressed patch is not smaller than the full snapshot. */
    public static byte[] create(byte[] base, byte[] target) {
        validateSnapshot(base);
        validateSnapshot(target);
        int prefix = 0;
        int shared = Math.min(base.length, target.length);
        while (prefix < shared && base[prefix] == target[prefix]) prefix++;
        int suffix = 0;
        while (suffix < shared - prefix &&
                base[base.length - suffix - 1] == target[target.length - suffix - 1]) suffix++;
        int literal = target.length - prefix - suffix;
        int size = HEADER_BYTES + literal;
        if (size >= target.length) return null;
        ByteBuffer out = ByteBuffer.allocate(size);
        out.putInt(MAGIC).putInt(target.length).putInt(prefix).putInt(suffix);
        out.put(hash(base)).put(hash(target));
        out.put(target, prefix, literal);
        return out.array();
    }

    /** Rebuilds only after validating size, structure, exact base, and target digest. */
    public static byte[] apply(byte[] base, byte[] patch) {
        validateSnapshot(base);
        if (patch == null || patch.length < HEADER_BYTES || patch.length > HEADER_BYTES + MAX_SNAPSHOT_BYTES)
            throw new IllegalArgumentException("Invalid snapshot patch size");
        ByteBuffer in = ByteBuffer.wrap(patch);
        if (in.getInt() != MAGIC) throw new IllegalArgumentException("Invalid snapshot patch version");
        int size = in.getInt();
        int prefix = in.getInt();
        int suffix = in.getInt();
        if (size < 0 || size > MAX_SNAPSHOT_BYTES || prefix < 0 || suffix < 0 ||
                (long) prefix + suffix > size || (long) prefix + suffix > base.length ||
                in.remaining() - HASH_BYTES * 2 != size - prefix - suffix)
            throw new IllegalArgumentException("Invalid snapshot patch bounds");
        byte[] baseHash = new byte[HASH_BYTES];
        byte[] targetHash = new byte[HASH_BYTES];
        in.get(baseHash).get(targetHash);
        if (!MessageDigest.isEqual(baseHash, hash(base)))
            throw new IllegalArgumentException("Snapshot base mismatch");
        byte[] result = new byte[size];
        System.arraycopy(base, 0, result, 0, prefix);
        in.get(result, prefix, size - prefix - suffix);
        System.arraycopy(base, base.length - suffix, result, size - suffix, suffix);
        if (!MessageDigest.isEqual(targetHash, hash(result)))
            throw new IllegalArgumentException("Snapshot result mismatch");
        return result;
    }

    private static void validateSnapshot(byte[] value) {
        if (value == null || value.length > MAX_SNAPSHOT_BYTES)
            throw new IllegalArgumentException("Invalid snapshot size");
    }

    public static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private static byte[] hash(byte[] value) { return sha256(value); }
}
