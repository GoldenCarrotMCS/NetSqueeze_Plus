package me.albert.netsqueeze.protocol;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;

/** Event-loop confined. Immutable snapshots; advertised disk seeds stay pinned until close. */
public final class SnapshotCodec implements AutoCloseable {
    private final LinkedHashMap<Long, byte[]> snapshots = new LinkedHashMap<>();
    private final Map<String, byte[]> seeds = new HashMap<>();
    private final Set<String> remoteSeeds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicLong sharedBudget;
    private final long maxBudget;
    private int bytes, seedBytes;
    private volatile boolean closed;
    public long full, hits, patches, rawBytes, encodedBytes;
    public record Decoded(byte[] packet, boolean snapshot) { }
    public SnapshotCodec() { this(new AtomicLong(), Long.MAX_VALUE); }
    public SnapshotCodec(AtomicLong sharedBudget, long maxBudget) { this.sharedBudget = sharedBudget; this.maxBudget = maxBudget; }
    public synchronized boolean pin(byte[] packet) {
        if (closed || packet.length > ChunkCacheProtocol.MAX_PACKET || seeds.size() >= ChunkCacheProtocol.MAX_SEEDS ||
                seedBytes + packet.length > ChunkCacheProtocol.SEED_BYTES) return false;
        String hash = ChunkCacheProtocol.hash(packet);
        if (seeds.containsKey(hash)) return true;
        seeds.put(hash, packet); seedBytes += packet.length;
        return true;
    }
    public void advertise(String hash) {
        if (!closed && ChunkCacheProtocol.validHash(hash) && remoteSeeds.size() < ChunkCacheProtocol.MAX_SEEDS) remoteSeeds.add(hash);
    }
    /** 0 ordinary, 1 full snapshot, 2 exact reference, 3 verified patch. */
    public synchronized byte[] encode(byte[] packet, boolean chunk) {
        if (closed) throw new IllegalStateException("Codec closed");
        if (!chunk || packet.length < 128 || packet.length > ChunkCacheProtocol.MAX_PACKET) return ordinary(packet);
        long key = key(packet);
        byte[] old = snapshots.get(key);
        byte[] hash = ChunkCacheProtocol.digest(packet);
        String hex = HexFormat.of().formatHex(hash);
        if (!store(key, packet)) return ordinary(packet);
        byte[] result;
        if (remoteSeeds.contains(hex) || (old != null && Arrays.equals(old, packet))) {
            result = ByteBuffer.allocate(41).put((byte)2).putLong(key).put(hash).array(); hits++;
        } else {
            byte[] patch = old == null ? null : ChunkSnapshotPatch.create(old, packet);
            // A conservative independent-Deflate estimate, NOT actual VC egress savings.
            if (patch != null && compressedSize(patch) + 128 < compressedSize(packet)) {
                result = ByteBuffer.allocate(9 + patch.length).put((byte)3).putLong(key).put(patch).array(); patches++;
            } else {
                result = ByteBuffer.allocate(9 + packet.length).put((byte)1).putLong(key).put(packet).array(); full++;
            }
        }
        rawBytes += packet.length; encodedBytes += result.length;
        return result;
    }
    public synchronized Decoded decode(byte[] envelope) {
        if (closed || envelope.length < 2) throw new IllegalArgumentException("Invalid snapshot envelope");
        ByteBuffer in = ByteBuffer.wrap(envelope);
        int op = in.get() & 255;
        if (op == 0) { byte[] packet = new byte[in.remaining()]; in.get(packet); return new Decoded(packet, false); }
        if (op > 3 || in.remaining() < 8 || envelope.length > ChunkCacheProtocol.MAX_PACKET + 9)
            throw new IllegalArgumentException("Invalid snapshot operation");
        long key = in.getLong();
        byte[] packet;
        if (op == 1) {
            packet = new byte[in.remaining()]; in.get(packet);
        } else if (op == 2) {
            if (in.remaining() != 32) throw new IllegalArgumentException("Invalid snapshot reference");
            byte[] hash = new byte[32]; in.get(hash);
            packet = snapshots.get(key);
            if (packet == null || !MessageDigest.isEqual(hash, ChunkCacheProtocol.digest(packet))) packet = seeds.get(HexFormat.of().formatHex(hash));
            if (packet == null || !MessageDigest.isEqual(hash, ChunkCacheProtocol.digest(packet)))
                throw new IllegalArgumentException("Unpinned snapshot reference");
        } else {
            byte[] old = snapshots.get(key);
            if (old == null) throw new IllegalArgumentException("Missing patch base");
            byte[] patch = new byte[in.remaining()]; in.get(patch);
            packet = ChunkSnapshotPatch.apply(old, patch);
        }
        if (packet.length < 128 || packet.length > ChunkCacheProtocol.MAX_PACKET || key(packet) != key || !store(key, packet))
            throw new IllegalArgumentException("Invalid reconstructed snapshot");
        return new Decoded(packet, true);
    }
    private byte[] ordinary(byte[] packet) {
        byte[] out = new byte[packet.length + 1]; System.arraycopy(packet, 0, out, 1, packet.length); return out;
    }
    private static long key(byte[] packet) {
        int offset = 0;
        while (offset < 5 && offset < packet.length && (packet[offset++] & 128) != 0) { }
        if (offset == 0 || offset > 5 || (packet[offset - 1] & 128) != 0 || packet.length - offset < 8)
            throw new IllegalArgumentException("Invalid encoded chunk key");
        return ByteBuffer.wrap(packet, offset, 8).getLong();
    }
    private boolean store(long key, byte[] value) {
        byte[] old = snapshots.get(key);
        int oldSize = old == null ? 0 : old.length;
        long increase = Math.max(0, value.length - oldSize);
        long next = sharedBudget.addAndGet(increase);
        if (increase > 0 && next > maxBudget) { sharedBudget.addAndGet(-increase); return false; }
        if (value.length < oldSize) sharedBudget.addAndGet(value.length - oldSize);
        snapshots.remove(key); snapshots.put(key, value); bytes += value.length - oldSize;
        while (bytes > ChunkCacheProtocol.MEMORY_BYTES || snapshots.size() > ChunkCacheProtocol.MAX_ENTRIES) {
            var it = snapshots.entrySet().iterator(); byte[] removed = it.next().getValue(); it.remove();
            bytes -= removed.length; sharedBudget.addAndGet(-removed.length);
        }
        return true;
    }
    private static int compressedSize(byte[] data) {
        Deflater d = new Deflater(1, true);
        try {
            d.setInput(data); d.finish(); int size = 0; byte[] buffer = new byte[8192];
            while (!d.finished()) size += d.deflate(buffer);
            return size;
        } finally { d.end(); }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; sharedBudget.addAndGet(-bytes); bytes = 0; seedBytes = 0;
        snapshots.clear(); seeds.clear(); remoteSeeds.clear();
    }
}
