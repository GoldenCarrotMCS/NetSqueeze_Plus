package me.albert.netsqueeze.protocol;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryProtocolTest {
    @Test void dictionaryCacheSelectionIsExplicitAndOldWireIsUnchanged() {
        var base = new ChunkCacheProtocol.Offer(UUID.randomUUID(), 1234, true, UUID.randomUUID(), 3);
        for (boolean cache : new boolean[]{false, true}) {
            var decoded = DictionaryProtocol.decode(DictionaryProtocol.encode(base, cache));
            assertEquals(base, decoded.base()); assertEquals(cache, decoded.cache());
            assertNotEquals(ChunkCacheProtocol.ack(base.session(), 1234, true), DictionaryProtocol.ack(base.session(), 1234, cache));
        }
        assertNotEquals(DictionaryProtocol.ack(base.session(), 1234, true), DictionaryProtocol.ack(base.session(), 1234, false));
        assertThrows(IllegalArgumentException.class, () -> DictionaryProtocol.decode(ChunkCacheProtocol.offer(base)));
        assertThrows(IllegalArgumentException.class, () -> ChunkCacheProtocol.offer(DictionaryProtocol.encode(base, true)));
        byte[] bad = DictionaryProtocol.encode(base, true); bad[41] = 2;
        assertThrows(IllegalArgumentException.class, () -> DictionaryProtocol.decode(bad));
        byte[] noZstd = DictionaryProtocol.encode(base, true); noZstd[20] = 0;
        assertThrows(IllegalArgumentException.class, () -> DictionaryProtocol.decode(noZstd));
    }
    @Test void sameSizeUpdatesReuseStorageAndMatchFreshDerivation() {
        try (ZstdDictionary dictionary = new ZstdDictionary()) {
            Random random = new Random(891);
            byte[] buffer = null;
            for (int round = 0; round < 20; round++) {
                byte[] raw = new byte[ZstdDictionary.BYTES + 1000 + round];
                random.nextBytes(raw);
                dictionary.update(raw);
                if (buffer != null) assertSame(buffer, dictionary.content(), "No replacement of the fixed 64 KiB history");
                buffer = dictionary.content();
                byte[] expected = new byte[ZstdDictionary.BYTES];
                expected[0] = 'N'; expected[1] = 'S'; expected[2] = 'D'; expected[3] = '1';
                System.arraycopy(raw, raw.length - expected.length + 4, expected, 4, expected.length - 4);
                assertArrayEquals(expected, buffer);
                assertArrayEquals(ChunkCacheProtocol.digest(expected), dictionary.digest());
            }
            byte[] finalBuffer = buffer;
            dictionary.close();
            assertArrayEquals(new byte[ZstdDictionary.BYTES], finalBuffer);
        }
    }
    @Test void resizedHistoryWipesOldStorageAndRejectedUpdatesPreserveCurrentState() {
        try (ZstdDictionary dictionary = new ZstdDictionary()) {
            dictionary.update(new byte[1024]);
            byte[] first = dictionary.content();
            byte[] shortInput = {8, 9, 10};
            dictionary.update(shortInput);
            assertNotSame(first, dictionary.content());
            assertArrayEquals(new byte[first.length], first);
            assertArrayEquals(new byte[]{'N', 'S', 'D', '1', 8, 9, 10}, dictionary.content());
            byte[] current = dictionary.content();
            byte[] hash = dictionary.digest().clone();
            assertThrows(IllegalArgumentException.class, () -> dictionary.update(new byte[0]));
            assertSame(current, dictionary.content());
            assertArrayEquals(hash, dictionary.digest());
            dictionary.update(new byte[]{11, 12, 13});
            assertSame(current, dictionary.content());
            assertArrayEquals(new byte[]{'N', 'S', 'D', '1', 11, 12, 13}, current);
            assertArrayEquals(ChunkCacheProtocol.digest(current), dictionary.digest());
        }
    }
    @Test void historyIsBoundedClearedAndIsolated() {
        byte[] raw = new byte[1024 * 1024]; new Random(667).nextBytes(raw);
        ZstdDictionary a = new ZstdDictionary();
        try (ZstdDictionary b = new ZstdDictionary()) {
            a.update(raw); b.update(raw);
            assertEquals(ZstdDictionary.BYTES, a.content().length);
            assertArrayEquals(a.digest(), b.digest());
            b.verify(a.digest());
            byte[] retained = a.content();
            a.close(); assertNull(a.content());
            assertArrayEquals(new byte[retained.length], retained);
            assertTrue(b.ready());
            byte[] wrong = b.digest().clone(); wrong[0] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> b.verify(wrong));
            assertThrows(IllegalStateException.class, () -> a.update(raw));
        } finally { a.close(); }
    }
}
