package me.albert.netsqueeze.client.cache;

import me.albert.netsqueeze.protocol.ChunkCacheProtocol;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;

/** Blocking storage: use exclusively from the bounded client cache worker. No game objects. */
public final class SnapshotDiskStore {
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static final Object LOCK = new Object();
    private static final int MAX_ENTRIES = 16384;
    private final Path root, scope;
    private final long maxBytes;
    private final boolean consent;

    public SnapshotDiskStore(Path root, String identity, long maxBytes, boolean consent) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        if (Objects.requireNonNull(identity).isBlank() || maxBytes < 0) throw new IllegalArgumentException("Invalid cache configuration");
        this.scope = this.root.resolve(ChunkCacheProtocol.hash(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        this.maxBytes = maxBytes;
        this.consent = consent;
    }
    public String put(byte[] data) throws IOException {
        synchronized (LOCK) {
            if (!consent) return null;
            Objects.requireNonNull(data);
            if (data.length == 0 || data.length > ChunkCacheProtocol.MAX_PACKET || data.length > maxBytes) return null;
            rejectLinks(root); Files.createDirectories(scope); rejectLinks(scope);
            String key = ChunkCacheProtocol.hash(data);
            Path target = scope.resolve(key + ".bin"), temp = Files.createTempFile(scope, ".netsqueeze-", ".tmp");
            try {
                try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE, NOFOLLOW)) {
                    ByteBuffer bytes = ByteBuffer.wrap(data);
                    while (bytes.hasRemaining()) out.write(bytes);
                }
                try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) { return null; }
                trim(root, maxBytes);
                return Files.isRegularFile(target, NOFOLLOW) ? key : null;
            } finally { Files.deleteIfExists(temp); }
        }
    }
    public byte[] get(String key) throws IOException {
        synchronized (LOCK) {
            if (!consent || !ChunkCacheProtocol.validHash(key) || !directory()) return null;
            Path path = scope.resolve(key + ".bin");
            if (!Files.isRegularFile(path, NOFOLLOW)) return null;
            byte[] data;
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, NOFOLLOW)) {
                long size = channel.size();
                if (size <= 0 || size > ChunkCacheProtocol.MAX_PACKET || size > maxBytes) return null;
                data = new byte[(int) size]; ByteBuffer bytes = ByteBuffer.wrap(data);
                while (bytes.hasRemaining()) if (channel.read(bytes) < 0) return null;
                if (channel.size() != size) return null;
            }
            if (!MessageDigest.isEqual(ChunkCacheProtocol.digest(data), HexFormat.of().parseHex(key))) return null;
            if (!Files.isSymbolicLink(path)) Files.getFileAttributeView(path, java.nio.file.attribute.BasicFileAttributeView.class, NOFOLLOW)
                .setTimes(FileTime.fromMillis(System.currentTimeMillis()), null, null);
            return data;
        }
    }
    public List<String> keys(int limit) throws IOException {
        synchronized (LOCK) {
            if (!consent || limit < 0 || limit > ChunkCacheProtocol.MAX_SEEDS || !directory()) return List.of();
            List<Entry> entries = new ArrayList<>(entries(scope).stream().filter(e -> e.path().getFileName().toString().endsWith(".bin")).toList());
            entries.sort(Comparator.comparingLong(Entry::modified).reversed());
            return entries.stream().limit(limit).map(e -> e.path().getFileName().toString().substring(0, 64)).toList();
        }
    }
    /** User-requested deletion is permitted after revocation. Does not invalidate pinned RAM. */
    public int clear() throws IOException { synchronized (LOCK) { return directory() ? delete(entries(scope)) : 0; } }
    public static int clearAll(Path root) throws IOException { synchronized (LOCK) { return delete(all(root)); } }
    public static long size(Path root) throws IOException {
        synchronized (LOCK) { long bytes = 0; for (Entry e : all(root)) bytes += e.size(); return bytes; }
    }
    public static void trim(Path root, long maxBytes) throws IOException {
        synchronized (LOCK) {
            if (maxBytes < 0) throw new IllegalArgumentException("Negative cache limit");
            List<Entry> entries = all(root);
            entries.sort(Comparator.comparingLong(Entry::modified).thenComparing(e -> e.path().toString()));
            long size = 0; for (Entry e : entries) size += e.size();
            int count = entries.size();
            for (Entry e : entries) {
                if (size <= maxBytes && count <= MAX_ENTRIES) break;
                if (Files.deleteIfExists(e.path())) { size -= e.size(); count--; }
            }
        }
    }
    private static int delete(List<Entry> entries) throws IOException {
        int count = 0; for (Entry e : entries) if (Files.deleteIfExists(e.path())) count++; return count;
    }
    private static List<Entry> all(Path root) throws IOException {
        rejectLinks(root);
        List<Entry> result = new ArrayList<>();
        if (!Files.isDirectory(root, NOFOLLOW)) return result;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) if (ChunkCacheProtocol.validHash(dir.getFileName().toString()) && Files.isDirectory(dir, NOFOLLOW))
                result.addAll(entries(dir));
        }
        return result;
    }
    private static List<Entry> entries(Path directory) throws IOException {
        rejectLinks(directory); List<Entry> result = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path p : files) {
                String name = p.getFileName().toString();
                boolean snapshot = name.length() == 68 && name.endsWith(".bin") && ChunkCacheProtocol.validHash(name.substring(0, 64));
                boolean temporary = name.matches("\\.netsqueeze-[0-9]+\\.tmp");
                if ((!snapshot && !temporary) || !Files.isRegularFile(p, NOFOLLOW)) continue;
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, NOFOLLOW);
                result.add(new Entry(p, a.size(), a.lastModifiedTime().toMillis()));
            }
        }
        return result;
    }
    private boolean directory() throws IOException { rejectLinks(scope); return Files.isDirectory(scope, NOFOLLOW); }
    private static void rejectLinks(Path path) throws IOException {
        for (Path p = path.toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("Cache path must not contain symlinks");
    }
    private record Entry(Path path, long size, long modified) { }
}
