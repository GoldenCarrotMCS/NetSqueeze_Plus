package me.albert.netsqueeze.protocol;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotCodecTest {
    private static byte[] chunk(int x, int z, int size, long seed) {
        byte[] data = new byte[size]; new Random(seed).nextBytes(data);
        data[0] = 7; ByteBuffer.wrap(data, 1, 8).putInt(x).putInt(z); return data;
    }
    @Test void fullHitPatchAndChangedContentStayInLockstep() {
        try (SnapshotCodec sender = new SnapshotCodec(); SnapshotCodec receiver = new SnapshotCodec()) {
            byte[] first = chunk(3, -4, 4096, 123);
            byte[] full = sender.encode(first, true);
            assertEquals(1, full[0]); assertArrayEquals(first, receiver.decode(full).packet());
            byte[] hit = sender.encode(first, true);
            assertEquals(2, hit[0]); assertArrayEquals(first, receiver.decode(hit).packet());
            byte[] changed = first.clone(); changed[2000] ^= 1;
            byte[] patch = sender.encode(changed, true);
            assertEquals(3, patch[0]); assertArrayEquals(changed, receiver.decode(patch).packet());
            byte[] different = chunk(3, -4, 4096, 456);
            assertArrayEquals(different, receiver.decode(sender.encode(different, true)).packet());
            assertEquals(0, sender.encode(new byte[] {5}, false)[0]);
        }
    }
    @Test void evictionAndRemoteSeedsAreSafe() {
        try (SnapshotCodec sender = new SnapshotCodec(); SnapshotCodec receiver = new SnapshotCodec()) {
            byte[] seed = chunk(9, 11, 2048, 5);
            assertTrue(receiver.pin(seed)); sender.advertise(ChunkCacheProtocol.hash(seed));
            byte[] reference = sender.encode(seed, true);
            assertEquals(2, reference[0]); assertArrayEquals(seed, receiver.decode(reference).packet());
            for (int i = 0; i < 100; i++) {
                byte[] packet = chunk(i, i + 1, 1024, i);
                assertArrayEquals(packet, receiver.decode(sender.encode(packet, true)).packet());
            }
            assertArrayEquals(seed, receiver.decode(sender.encode(seed, true)).packet());
        }
    }
    @Test void missingOrCorruptedReferencesAreRejected() {
        try (SnapshotCodec sender = new SnapshotCodec(); SnapshotCodec receiver = new SnapshotCodec()) {
            byte[] data = chunk(1, 2, 1024, 7);
            byte[] full = sender.encode(data, true);
            byte[] ref = sender.encode(data, true);
            assertThrows(IllegalArgumentException.class, () -> receiver.decode(ref));
            receiver.decode(full);
            byte[] damaged = ref.clone(); damaged[10] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> receiver.decode(damaged));
            assertThrows(IllegalArgumentException.class, () -> receiver.decode(Arrays.copyOf(ref, 10)));
        }
    }
    @Test void memoryBudgetRejectionProducesOrdinaryPacketAndReleasesOnClose() {
        AtomicLong budget = new AtomicLong();
        SnapshotCodec sender = new SnapshotCodec(budget, 1500);
        byte[] first = chunk(1, 2, 1024, 1), second = chunk(2, 3, 1024, 2);
        assertEquals(1, sender.encode(first, true)[0]);
        assertEquals(0, sender.encode(second, true)[0]);
        assertEquals(1024, budget.get());
        sender.close(); assertEquals(0, budget.get()); sender.close(); assertEquals(0, budget.get());
    }
    @Test void offerRoundTripAndMalformedAreBounded() {
        var original = new ChunkCacheProtocol.Offer(UUID.randomUUID(), 1000, true, UUID.randomUUID(), 1);
        assertEquals(original, ChunkCacheProtocol.offer(ChunkCacheProtocol.offer(original)));
        assertThrows(IllegalArgumentException.class, () -> ChunkCacheProtocol.offer(new byte[2]));
        assertThrows(IllegalArgumentException.class, () -> ChunkCacheProtocol.seed(original.session(), "bad"));
    }
}
