package me.albert.netsqueeze.client.cache;

import me.albert.netsqueeze.protocol.ChunkCacheProtocol;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Local-only grants keyed by a hash of the user-selected server address and local profile. */
public final class CachePreferences {
    private final Path file;
    private final Properties values = new Properties();
    public CachePreferences(Path file) throws IOException {
        this.file = file;
        if (Files.isSymbolicLink(file)) throw new IOException("Preferences must not be a symlink");
        if (Files.exists(file)) {
            if (Files.size(file) > 128 * 1024) throw new IOException("Preferences too large");
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { values.load(in); }
        }
    }
    public boolean allowed(String identity) { return "true".equals(values.getProperty("allow." + key(identity))); }
    public void allow(String identity, boolean allowed) throws IOException {
        String key = "allow." + key(identity);
        if (allowed && values.size() >= 1024 && !values.containsKey(key)) throw new IOException("Too many cache grants");
        if (allowed) values.setProperty(key, "true"); else values.remove(key);
        save();
    }
    public long limit() {
        try { long value = Long.parseLong(values.getProperty("limit", "268435456")); return Math.clamp(value, 16L << 20, 1024L << 20); }
        catch (NumberFormatException e) { return 256L << 20; }
    }
    public void limit(long bytes) throws IOException {
        if (bytes < (16L << 20) || bytes > (1024L << 20)) throw new IllegalArgumentException("容量必须为 16..1024 MiB");
        values.setProperty("limit", Long.toString(bytes)); save();
    }
    private void save() throws IOException {
        if (Files.isSymbolicLink(file.getParent()) || Files.isSymbolicLink(file)) throw new IOException("Invalid preference path");
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".nsq-prefs-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp, LinkOption.NOFOLLOW_LINKS)) { values.store(out, "NetSqueeze local cache consent"); }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private static String key(String identity) { return ChunkCacheProtocol.hash(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
}
