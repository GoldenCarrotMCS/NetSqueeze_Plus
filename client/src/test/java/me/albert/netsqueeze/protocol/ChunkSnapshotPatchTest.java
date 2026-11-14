package me.albert.netsqueeze.protocol;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ChunkSnapshotPatchTest {
    @Test void reconstructsEqualEditedInsertedAndDeletedSnapshots() {
        byte[] base = new byte[4096];
        new Random(42).nextBytes(base);
        byte[] changed = base.clone();
        Arrays.fill(changed, 1000, 1020, (byte) 9);
        byte[] inserted = new byte[base.length + 3];
        System.arraycopy(base, 0, inserted, 0, 2000);
        System.arraycopy(base, 2000, inserted, 2003, base.length - 2000);
        byte[] deleted = new byte[base.length - 3];
        System.arraycopy(base, 0, deleted, 0, 2000);
        System.arraycopy(base, 2003, deleted, 2000, base.length - 2003);
        for (byte[] target : new byte[][] {base, changed, inserted, deleted}) {
            byte[] patch = ChunkSnapshotPatch.create(base, target);
            assertNotNull(patch);
            assertArrayEquals(target, ChunkSnapshotPatch.apply(base, patch));
        }
    }

    @Test void declinesPatchesWithoutRawSizeSavings() {
        assertNull(ChunkSnapshotPatch.create(new byte[10], new byte[10]));
        byte[] base = new byte[512];
        byte[] target = new byte[512];
        Arrays.fill(target, (byte) 1);
        assertNull(ChunkSnapshotPatch.create(base, target));
    }

    @Test void rejectsWrongBaseCorruptionTruncationAndInvalidBounds() {
        byte[] base = new byte[1024];
        byte[] target = base.clone();
        target[500] = 1;
        byte[] patch = ChunkSnapshotPatch.create(base, target);
        assertNotNull(patch);
        byte[] wrongBase = base.clone();
        wrongBase[10] = 1;
        assertThrows(IllegalArgumentException.class, () -> ChunkSnapshotPatch.apply(wrongBase, patch));
        byte[] corrupt = patch.clone();
        corrupt[corrupt.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> ChunkSnapshotPatch.apply(base, corrupt));
        assertThrows(IllegalArgumentException.class, () -> ChunkSnapshotPatch.apply(base, Arrays.copyOf(patch, patch.length - 1)));
        byte[] badBounds = patch.clone();
        ByteBuffer.wrap(badBounds).putInt(8, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> ChunkSnapshotPatch.apply(base, badBounds));
        byte[] tooLarge = patch.clone();
        ByteBuffer.wrap(tooLarge).putInt(4, ChunkSnapshotPatch.MAX_SNAPSHOT_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> ChunkSnapshotPatch.apply(base, tooLarge));
    }

    @Test void enforcesSnapshotInputLimits() {
        assertThrows(IllegalArgumentException.class, () -> ChunkSnapshotPatch.create(null, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> ChunkSnapshotPatch.create(new byte[0], new byte[ChunkSnapshotPatch.MAX_SNAPSHOT_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> ChunkSnapshotPatch.apply(new byte[0], null));
    }
}
