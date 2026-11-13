package me.albert.netsqueeze.client.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CachePreferencesTest {
    @TempDir Path temp;
    @Test void consentDefaultsOffAndIsScopedAndPersisted() throws Exception {
        Path path = temp.resolve("prefs.properties");
        CachePreferences p = new CachePreferences(path);
        assertFalse(p.allowed("server/a")); assertFalse(Files.exists(path));
        p.allow("server/a", true);
        p = new CachePreferences(path);
        assertTrue(p.allowed("server/a")); assertFalse(p.allowed("server/b"));
        p.allow("server/a", false);
        assertFalse(new CachePreferences(path).allowed("server/a"));
    }
    @Test void capacityIsBoundedAndPersisted() throws Exception {
        Path path = temp.resolve("prefs.properties");
        CachePreferences p = new CachePreferences(path);
        assertEquals(256L << 20, p.limit());
        assertThrows(IllegalArgumentException.class, () -> p.limit(1));
        p.limit(32L << 20);
        assertEquals(32L << 20, new CachePreferences(path).limit());
    }
}
