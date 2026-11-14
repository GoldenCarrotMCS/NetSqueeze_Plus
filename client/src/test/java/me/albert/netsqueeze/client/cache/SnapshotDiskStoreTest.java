package me.albert.netsqueeze.client.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotDiskStoreTest {
    @TempDir Path temp;

    @Test void consentIsRequiredAndScopesAreIsolated() throws Exception {
        Path root = temp.resolve("snapshots");
        byte[] data = new byte[] {1, 2, 3};
        SnapshotDiskStore disabled = new SnapshotDiskStore(root, "server-a", 1024, false);
        assertNull(disabled.put(data));
        assertFalse(Files.exists(root));
        SnapshotDiskStore a = new SnapshotDiskStore(root, "server-a", 1024, true);
        SnapshotDiskStore b = new SnapshotDiskStore(root, "server-b", 1024, true);
        String key = a.put(data);
        assertNotNull(key);
        assertArrayEquals(data, a.get(key));
        assertNull(b.get(key));
        assertNull(disabled.get(key));
        assertEquals(1, disabled.clear());
        assertNull(a.get(key));
    }

    @Test void corruptedFilesAndInvalidNamesAreNotUsed() throws Exception {
        SnapshotDiskStore store = new SnapshotDiskStore(temp, "server", 1024, true);
        String key = store.put(new byte[] {1, 2, 3});
        assertNotNull(key);
        Path file;
        try (var dirs = Files.list(temp)) { file = dirs.findFirst().orElseThrow().resolve(key + ".bin"); }
        Files.write(file, new byte[] {9, 9, 9});
        assertNull(store.get(key));
        assertNull(store.get("../../outside"));
        assertNull(store.get(null));
    }

    @Test void capacityEvictsEntriesAndRejectsOversizedEntries() throws Exception {
        SnapshotDiskStore store = new SnapshotDiskStore(temp, "server", 10, true);
        String first = store.put(new byte[] {1, 2, 3, 4, 5, 6});
        assertNotNull(first);
        String second = store.put(new byte[] {7, 8, 9, 10, 11, 12});
        assertTrue(store.get(first) == null || second == null || store.get(second) == null);
        assertNull(store.put(new byte[11]));
        assertNull(store.put(new byte[0]));
    }
}
